package com.aimediasorter.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aimediasorter.app.data.local.*
import com.aimediasorter.app.data.model.*
import com.aimediasorter.app.data.preferences.SettingsRepository
import com.aimediasorter.app.data.repository.MediaRepository
import com.aimediasorter.app.domain.cloud.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Isolated temporary media and mocked HTTP; never touches the user's archive or credentials. */
@RunWith(AndroidJUnit4::class)
class VirtualGalleryNativeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class Fixture(val context: Context) {
        val root=File(context.cacheDir,"virtual-qa-"+UUID.randomUUID()).apply { mkdirs() }
        val source=File(root,"source").apply { mkdirs() }
        val destination=File(root,"destination").apply { mkdirs() }
        val image=File(source,"photo.png")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val preferences=SettingsRepository(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={File(root,"qa.preferences_pb")}))
        val cloudName="virtual-cloud-"+UUID.randomUUID()+".db"
        var requests=0
        var httpCode=200
        val cloud=CloudAnalysisService(context,GeminiClient(CloudTransport { _,key,body ->
            check(key.startsWith("AQ."));check(body?.contains("inlineData")==true);requests++
            CloudHttpResponse(httpCode,if(httpCode==200) response() else """{"error":{"message":"synthetic sensitive error"}}""")
        }),namespace="qa-"+UUID.randomUUID(),databaseName=cloudName)
        val repository=MediaRepository(context,database,preferences,cloud)
        init {
            Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
                image.outputStream().use { check(compress(Bitmap.CompressFormat.PNG,100,it)) };recycle()
            }
            check(image.setLastModified(1700000000000))
        }
        fun hash(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        suspend fun configure() {
            repository.ensureDefaultCategories()
            preferences.updateSourceUri(source.path);preferences.updateDestinationUri(destination.path)
            preferences.updateCloudOptions(CloudOptions(model="models/gemini-mock",maxRequests=3,maxDailyRequests=10,retries=0,minimumIntervalSeconds=0))
            cloud.vault.save("AQ."+"SyntheticCredential".repeat(20))
            cloud.state.replaceModels(cloud.vault.revision,listOf("models/gemini-mock"))
            cloud.state.markModelVerified(cloud.vault.revision,"models/gemini-mock")
        }
        suspend fun analyze(force: Boolean=false) {
            val settings=preferences.settingsFlow.first()
            val batch=repository.authorizeCloudBatch(settings,force=force)
            repository.executeMediaScanAndAnalysis(settings,batch,forceReanalysis=force)
        }
        suspend fun item()=database.mediaItemDao().getCurrentMediaItems(source.path).single()
        fun close() {
            cloud.vault.clear();cloud.state.close();context.deleteDatabase(cloudName)
            database.close();scope.cancel();root.deleteRecursively()
        }
        companion object {
            fun response(): String {
                val decision="""{"primary_subject":"A person","primary_category":"cat_people","independently_significant_secondary_subjects":[{"description":"A meal","category":"cat_food","significance_reason":"The meal is deliberately presented with the person."}],"contextual_objects":[],"pose":"standing","scene":"indoor","details":[],"category_decision_reason":"Two independently important subjects.","review_required":false}"""
                return JSONObject().put("candidates",JSONArray().put(
                    JSONObject().put("finishReason","STOP").put("content",
                        JSONObject().put("parts",JSONArray().put(JSONObject().put("text",decision))))
                )).toString()
            }
        }
    }
    @Test fun analysisAndVirtualCorrectionsNeverCreatePhysicalDuplicates()=runBlocking {
        val f=Fixture(context)
        try {
            f.configure();val hash=f.hash(f.image);f.analyze()
            var item=f.item()
            assertEquals("NEEDS_REVIEW",item.analysisStatus);assertTrue(item.needsManualReview)
            assertEquals("models/gemini-mock",item.analysisModel);assertEquals("GEMINI",item.inferenceEngine)
            assertEquals(2,f.database.virtualGalleryDao().links(item.id).count { it.origin=="EFFECTIVE" })
            assertEquals(1,f.database.mediaItemDao().getAllMediaItems().size)
            val people=f.database.categoryDao().getAllCategories().first { it.id=="cat_people" }
            f.repository.upsertCategory(people.copy(description="Edited description must not delete gallery links"))
            assertEquals(2,f.database.virtualGalleryDao().links(item.id).count { it.origin=="EFFECTIVE" })
            assertTrue(f.destination.listFiles()!!.isEmpty());assertEquals(hash,f.hash(f.image))
            f.repository.updateVirtualCategories(setOf(item.id),setOf("cat_people"),false)
            item=f.item();assertEquals("APPROVED",item.analysisStatus)
            val current=f.repository.galleryLinksFlow.first().filter { it.origin=="EFFECTIVE" }
            assertEquals(listOf("cat_people"),current.map { it.categoryId })
            assertEquals(2,f.database.virtualGalleryDao().links(item.id).count { it.origin=="PROPOSED" })
            assertTrue(f.destination.listFiles()!!.isEmpty());assertEquals(hash,f.hash(f.image))
            f.repository.updateVirtualCategories(setOf(item.id),setOf("cat_people","cat_food"),false)
            item=f.item();assertFalse(item.needsManualReview)
            val preview=f.repository.previewOperations(setOf(item.id),TransferMethod.COPY,emptyMap())
            assertEquals(2,preview.steps.size);assertTrue(f.destination.listFiles()!!.isEmpty())
            f.preferences.updateOperationMode(OperationMode.MOVE_FILES)
            val ids=f.repository.approveOperationPreview(preview)
            assertTrue(f.destination.listFiles()!!.isEmpty())
            f.repository.executePhysicalPlans(f.preferences.settingsFlow.first(),ids)
            assertTrue(f.image.exists());assertEquals(hash,f.hash(f.image))
            assertEquals(hash,f.hash(File(f.destination,"People/photo.png")))
            assertEquals(hash,f.hash(File(f.destination,"Food/photo.png")))
            try { f.repository.approveOperationPreview(preview);fail("Duplicate plan must be blocked") } catch(_: IllegalArgumentException) {}
        } finally { f.close() }
    }
    @Test fun cachedSuccessAvoidsUploadsAndExplicitReanalysisUsesANewConsentedRequest()=runBlocking {
        val f=Fixture(context)
        try {
            f.configure();f.analyze();assertEquals(1,f.requests)
            f.analyze();assertEquals(1,f.requests)
            f.analyze(force=true);assertEquals(2,f.requests)
            assertTrue(f.image.exists());assertTrue(f.destination.listFiles()!!.isEmpty())
        } finally { f.close() }
    }
    @Test fun failedGeminiNeverFallsBackAndCanRetryWithFreshConsent()=runBlocking {
        val f=Fixture(context)
        try {
            f.configure();f.httpCode=404;f.analyze()
            val item=f.item()
            assertEquals("FAILED",item.analysisStatus);assertEquals("__failed__",item.detectedCategoryId)
            assertEquals("GEMINI",item.inferenceEngine)
            assertTrue(f.database.virtualGalleryDao().links(item.id).isEmpty())
            assertFalse(item.primarySubjectNote.contains("synthetic sensitive"))
            f.httpCode=200;f.analyze()
            assertEquals(2,f.requests);assertEquals("NEEDS_REVIEW",f.item().analysisStatus)
            assertTrue(f.destination.listFiles()!!.isEmpty())
        } finally { f.close() }
    }
    @Test fun exhaustedBatchLeavesUnsentMediaPendingAndResumeSkipsPreviouslyAnalyzedBytes()=runBlocking {
        val f=Fixture(context)
        try {
            f.configure()
            val second=File(f.source,"second.png")
            Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.GREEN);second.outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) };recycle()
            }
            val o=f.preferences.settingsFlow.first().cloudOptions.copy(maxRequests=1)
            f.preferences.updateCloudOptions(o)
            f.analyze()
            assertEquals(1,f.requests)
            var items=f.database.mediaItemDao().getCurrentMediaItems(f.source.path)
            assertEquals(1,items.count { it.analysisStatus=="PENDING" })
            assertEquals(1,f.database.analysisSessionDao().latestForSource(f.source.path)!!.totalClassified)
            f.analyze()
            assertEquals(2,f.requests)
            items=f.database.mediaItemDao().getCurrentMediaItems(f.source.path)
            assertEquals(0,items.count { it.analysisStatus=="PENDING" })
            assertTrue(f.image.exists());assertTrue(second.exists());assertTrue(f.destination.listFiles()!!.isEmpty())
        } finally { f.close() }
    }

    @Test fun explicitReanalysisUploadsOnlyTheSelectedMediaId()=runBlocking {
        val f=Fixture(context)
        try {
            f.configure()
            Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.GREEN);File(f.source,"second.png").outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) };recycle()
            }
            f.analyze();assertEquals(2,f.requests)
            val selected=setOf(f.database.mediaItemDao().getCurrentMediaItems(f.source.path).first().id)
            val settings=f.preferences.settingsFlow.first()
            val batch=f.repository.authorizeCloudBatch(settings,selected,true)
            f.repository.executeMediaScanAndAnalysis(settings,batch,selected,true)
            assertEquals(3,f.requests)
            assertEquals(2,f.database.mediaItemDao().getCurrentMediaItems(f.source.path).size)
            assertTrue(f.destination.listFiles()!!.isEmpty())
        } finally { f.close() }
    }

    @Test fun consentForOneSelectedSourceCannotAuthorizeAnotherSource()=runBlocking {
        val f=Fixture(context)
        try {
            f.configure()
            val old=f.preferences.settingsFlow.first()
            val batch=f.repository.authorizeCloudBatch(old)
            val other=File(f.root,"other-source").apply { mkdirs() }
            f.image.copyTo(File(other,"other.png"))
            f.preferences.updateSourceUri(other.path)
            f.repository.executeMediaScanAndAnalysis(f.preferences.settingsFlow.first(),batch)
            assertEquals(0,f.requests)
            assertEquals("PENDING",f.database.mediaItemDao().getCurrentMediaItems(other.path).single().analysisStatus)
            assertTrue(f.destination.listFiles()!!.isEmpty());assertTrue(f.image.exists())
        } finally { f.close() }
    }

    @Test fun analysisNeedsNoDestinationAndInvalidPhysicalDestinationCannotBlockVirtualResults()=runBlocking {
        val f=Fixture(context)
        try {
            f.configure();f.preferences.updateDestinationUri("")
            f.analyze();assertEquals("NEEDS_REVIEW",f.item().analysisStatus)
            assertEquals(1,f.requests);assertTrue(f.destination.listFiles()!!.isEmpty())
            val invalid=File(f.source,"Sorted")
            f.preferences.updateDestinationUri(invalid.path)
            f.analyze();assertEquals(1,f.requests)
            val item=f.item()
            f.repository.updateVirtualCategories(setOf(item.id),setOf("cat_people"),false)
            try { f.repository.previewOperations(setOf(item.id),TransferMethod.COPY,emptyMap());fail("Nested physical destination must be blocked") }
            catch(_: IllegalArgumentException) {}
            assertFalse(invalid.exists());assertTrue(f.image.exists())
        } finally { f.close() }
    }

    @Test fun cancellationBeforeExecutionRevokesAllUnstartedPhysicalSteps()=runBlocking {
        val f=Fixture(context)
        try {
            f.configure();f.analyze();val item=f.item()
            f.repository.updateVirtualCategories(setOf(item.id),setOf("cat_people","cat_food"),false)
            val preview=f.repository.previewOperations(setOf(item.id),TransferMethod.MOVE,mapOf(item.id to "cat_people"))
            f.preferences.updateOperationMode(OperationMode.MOVE_FILES)
            val ids=f.repository.approveOperationPreview(preview)
            f.repository.cancelPhysicalPlans()
            assertTrue(f.database.virtualGalleryDao().plans(ids).all { it.status=="CANCELLED" })
            try { f.repository.executePhysicalPlans(f.preferences.settingsFlow.first(),ids);fail("Cancelled plan must never execute") }
            catch(_: IllegalArgumentException) {}
            assertTrue(f.image.exists());assertTrue(f.destination.listFiles()!!.isEmpty())
        } finally { f.close() }
    }

    @Test fun staleDecisionsBlockPreviewApprovalAndMultiMoveCopiesBeforeDeletion()=runBlocking {
        val f=Fixture(context)
        try {
            f.configure();f.analyze();val item=f.item()
            f.repository.updateVirtualCategories(setOf(item.id),setOf("cat_people","cat_food"),false)
            val stale=f.repository.previewOperations(setOf(item.id),TransferMethod.MOVE,mapOf(item.id to "cat_people"))
            f.preferences.updateOperationMode(OperationMode.MOVE_FILES)
            f.repository.updateVirtualCategories(setOf(item.id),setOf("cat_people"),false)
            try { f.repository.approveOperationPreview(stale);fail("Stale decision must be blocked") } catch(_: IllegalArgumentException) {}
            assertTrue(f.destination.listFiles()!!.isEmpty());assertTrue(f.image.exists())
            f.repository.updateVirtualCategories(setOf(item.id),setOf("cat_people","cat_food"),false)
            val fresh=f.repository.previewOperations(setOf(item.id),TransferMethod.MOVE,mapOf(item.id to "cat_people"))
            assertEquals(listOf("COPY","MOVE"),fresh.steps.map { it.method })
            val hash=f.hash(f.image)
            val ids=f.repository.approveOperationPreview(fresh)
            f.repository.executePhysicalPlans(f.preferences.settingsFlow.first(),ids)
            assertEquals(hash,f.hash(File(f.destination,"Food/photo.png")))
            assertEquals(hash,f.hash(File(f.destination,"People/photo.png")))
            assertFalse("Local verified move should remove the original only after both destinations verify.",f.image.exists())
        } finally { f.close() }
    }
}
