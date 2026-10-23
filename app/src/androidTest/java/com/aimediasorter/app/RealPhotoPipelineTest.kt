package com.aimediasorter.app

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.preferences.AppSettings
import com.aimediasorter.app.domain.cloud.*
import com.aimediasorter.app.domain.storage.OriginalMediaAccess
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real photographic JPEG, isolated files, mock HTTP only. No Google requests or personal media. */
@RunWith(AndroidJUnit4::class)
class RealPhotoPipelineTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val categories=listOf(CategoryEntity("cat_food","Food","Meals and fruit",0,"label",1,destinationSubfolder="Food"))
    private fun response()=JSONObject().put("candidates",JSONArray().put(JSONObject()
        .put("finishReason","STOP").put("content",JSONObject().put("parts",JSONArray().put(JSONObject()
        .put("text","""{"primary_subject":"Fruit","primary_category":"cat_food","independently_significant_secondary_subjects":[],"contextual_objects":[],"pose":"","scene":"table","details":[],"category_decision_reason":"Fruit is the main subject.","review_required":false}""")))))).toString()
    private fun jpeg(): File=File(context.cacheDir,"jpeg-qa-"+UUID.randomUUID()+".jpg").also { file ->
        instrumentation.context.assets.open("real_photo_fruits.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    private fun hash(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** Models Android's documented setRequireOriginal rejection without that permission.
     * Selected pixels remain readable. This must not weaken OriginalMediaAccess for transfers.
     */
    private fun redactingContext(file: File): Context {
        val provider=object: ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri: Uri)="image/jpeg"
            override fun query(uri: Uri,p: Array<out String>?,s: String?,a: Array<out String>?,o: String?): Cursor?=null
            override fun insert(uri: Uri,v: ContentValues?): Uri?=null
            override fun update(uri: Uri,v: ContentValues?,s: String?,a: Array<out String>?)=0
            override fun delete(uri: Uri,s: String?,a: Array<out String>?)=0
            override fun openAssetFile(uri: Uri,mode: String): AssetFileDescriptor {
                check(mode=="r")
                if(MediaStore.getRequireOriginal(uri)) throw UnsupportedOperationException("Synthetic permission rejection")
                return AssetFileDescriptor(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY),0,file.length())
            }
            override fun openTypedAssetFile(uri: Uri,mime: String,opts: Bundle?,signal: CancellationSignal?)=
                openAssetFile(uri,"r")
        }
        val resolver=ContentResolver.wrap(provider)
        return object: ContextWrapper(context) { override fun getContentResolver()=resolver }
    }
    private suspend fun analyze(file: File,readContext: Context,uri: Uri): Pair<String,Int> {
        var requests=0
        val name="photo-regression-"+UUID.randomUUID()+".db"
        val cloud=CloudAnalysisService(readContext,GeminiClient(CloudTransport { _,_,body ->
            requests++
            val inline=JSONObject(body!!).getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData")
            assertEquals("image/jpeg",inline.getString("mimeType"))
            val bytes=android.util.Base64.decode(inline.getString("data"),android.util.Base64.DEFAULT)
            assertEquals(0xff,bytes[0].toInt() and 255);assertEquals(0xd8,bytes[1].toInt() and 255)
            val image=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            assertNotNull(image);assertTrue(maxOf(image.width,image.height)<=512);image.recycle()
            CloudHttpResponse(200,response())
        }),namespace="photo-regression-"+UUID.randomUUID(),databaseName=name)
        try {
            cloud.vault.save("AQ.SyntheticPhotographicFixtureCredential")
            val options=CloudOptions(model="models/gemini-test",retries=0,minimumIntervalSeconds=0)
            cloud.state.replaceModels(cloud.vault.revision,listOf(options.model))
            cloud.state.markModelVerified(cloud.vault.revision,options.model)
            val batch=cloud.authorize("fixture",options)
            val result=cloud.analyze(AppSettings(cloudOptions=options),batch,"fixture",hash(file),categories,
                uri,null,false,0,false,liveCheck={})
            return result.analysisStatus to requests
        } finally { cloud.vault.clear();cloud.state.close();context.deleteDatabase(name) }
    }
    @Test fun realPhotographicJpegReachesMockApiAndValidatesStructuredResponse()=runBlocking {
        val file=jpeg();val original=file.readBytes()
        try {
            assertEquals("PROPOSED" to 1,analyze(file,context,Uri.fromFile(file)))
            assertArrayEquals(original,file.readBytes())
        } finally { file.delete() }
    }
    @Test fun readableJpegMustReachAnalysisEvenWhenUnredactedOriginalAccessIsDenied()=runBlocking {
        val file=jpeg();val original=file.readBytes()
        val uri=Uri.parse("content://media/external/images/media/123")
        val redacted=redactingContext(file)
        try {
            try { OriginalMediaAccess(redacted).input(uri).close();fail("Original transfer access must remain blocked") }
            catch(_: UnsupportedOperationException) {}
            assertEquals("PROPOSED" to 1,analyze(file,redacted,uri))
            assertArrayEquals(original,file.readBytes())
        } finally { file.delete() }
    }
}
