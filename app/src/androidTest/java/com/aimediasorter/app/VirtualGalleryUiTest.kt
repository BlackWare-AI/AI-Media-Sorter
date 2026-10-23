package com.aimediasorter.app

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aimediasorter.app.data.local.*
import com.aimediasorter.app.data.preferences.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Installed gallery, actual image decode and byte preservation; no cloud accuracy claim. */
@RunWith(AndroidJUnit4::class)
class VirtualGalleryUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val settings=SettingsRepository(context)
    private val db=AppDatabase.getInstance(context)
    private val root=File(context.cacheDir,"gallery-ui-"+UUID.randomUUID())
    private val photo=File(root,"gallery-qa.png")
    private val categoryId="qa-ui-"+UUID.randomUUID()
    private val sessionId=UUID.randomUUID().toString()
    private var oldSource=""
    private var id=0L
    private var original=byteArrayOf()
    private val activity=InstalledActivityRule()
    private val compose=AndroidComposeTestRule(activity) { it.activity() }
    private val fixture=object: ExternalResource() {
        override fun before()=runBlocking {
            oldSource=settings.settingsFlow.first().sourceUri
            check(root.mkdirs())
            Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE);photo.outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) };recycle()
            }
            original=photo.readBytes()
            db.categoryDao().upsertCategory(CategoryEntity(categoryId,"QA Visual Category","Temporary UI fixture",0,"label",10,
                destinationSubfolder="QA"))
            db.analysisSessionDao().upsertSession(AnalysisSessionEntity(sessionId,1,2,root.path,"",1,1,0,0,0,"COMPLETED","qa"))
            id=db.mediaItemDao().upsertMediaItem(MediaItemEntity(
                uriString=Uri.fromFile(photo).toString(),filePath=photo.path,fileName=photo.name,extension="png",
                isVideo=false,mimeType="image/png",fileSizeBytes=photo.length(),width=96,height=64,durationMs=0,
                originalLastModifiedMs=photo.lastModified(),exifCaptureDateMs=null,quickFingerprint="qa",
                detectedCategoryId=categoryId,detectedCategoryName="QA Visual Category",effectiveCategoryId=categoryId,
                effectiveCategoryName="QA Visual Category",calibratedConfidence=0f,rawSimilarityScore=0f,topMargin=0f,
                secondaryCategoryName=null,analyzedAtMs=1,sourceRootUri=root.path,scanSessionId=sessionId,
                inferenceEngine="GEMINI",analysisStatus="PROPOSED",
                analysisJson="""{"categories":["$categoryId"],"subjects":[{"description":"QA primary subject","importance":"main"}],"pose":"","scene":"","details":[],"reason":"QA composition summary","review_status":"CLEAR","primary_subject":"QA primary subject","primary_category":"$categoryId","contextual_objects":["QA incidental context"],"category_decision_reason":"QA composition summary","review_required":false}"""))
            db.virtualGalleryDao().insertLinks(listOf(MediaCategoryLink(id,categoryId,"EFFECTIVE"),MediaCategoryLink(id,categoryId,"PROPOSED")))
            settings.updateSourceUri(root.path)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        }
        override fun after() { runBlocking {
            settings.updateSourceUri(oldSource)
            if(id!=0L) db.openHelper.writableDatabase.execSQL("DELETE FROM media_items WHERE id=?",arrayOf(id))
            db.categoryDao().deleteCustomCategory(categoryId)
            db.openHelper.writableDatabase.execSQL("DELETE FROM analysis_sessions WHERE sessionId=?",arrayOf(sessionId))
            root.deleteRecursively()
        } }
    }
    // Data is installed before launch; cleanup follows complete Activity/ViewModel destruction.
    @get:Rule val rules: RuleChain=RuleChain.outerRule(fixture).around(compose)

    @Test fun virtualFolderOpensRealThumbnailAndFullScreenPreview() {
        compose.onNodeWithTag("nav-folders").performClick()
        compose.onNodeWithTag("virtual-folder-grid")
            .performScrollToNode(hasTestTag("virtual-folder-"+categoryId))
        compose.onNodeWithTag("virtual-folder-"+categoryId).performClick()
        compose.onNodeWithTag("virtual-media-"+id).assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(photo.name).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("virtual-media-"+id).performClick()
        compose.onNodeWithTag("analysis-primary-subject").performScrollTo().assertTextContains("QA primary subject",substring=true)
        compose.onNodeWithTag("analysis-context-only").performScrollTo().assertTextContains("QA incidental context",substring=true)
        compose.onNodeWithTag("view-full-image").performScrollTo().performClick()
        compose.onNodeWithTag("full-media-viewer",useUnmergedTree=true).assertExists()
        val decodedPreview=hasContentDescription(photo.name) and hasAnyAncestor(hasTestTag("full-media-viewer"))
        compose.waitUntil(10_000) {
            compose.onAllNodes(decodedPreview,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()
        }
        assertArrayEquals(original,photo.readBytes())
        assertTrue(root.listFiles()!!.all { it.name==photo.name })
    }
}
