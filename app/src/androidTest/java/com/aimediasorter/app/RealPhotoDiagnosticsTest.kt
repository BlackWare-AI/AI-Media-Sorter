package com.aimediasorter.app

import android.content.Context
import android.content.ContextWrapper
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
// Fixture mutation uses the platform API: AndroidX write methods are correctly unused/stripped in the app.
import android.media.ExifInterface
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real JPEG decoder/request/database regression suite. All Google responses are mocked. */
@RunWith(AndroidJUnit4::class)
class RealPhotoDiagnosticsTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Test fun structuredMockSubjectContextSurvivesRoomWithoutExtraGalleryCategories()=runBlocking {
        // This is transport/Room/safety evidence, not recognition: the JPEG and semantic mock are independent.
        val fixture=Fixture(context,instrumentation.context)
        try {
            fixture.configure()
            val original=fixture.photo.readBytes();val modified=fixture.photo.lastModified()
            val payload=JSONObject().put("primary_subject","PRIVATE_MOCK_SUBJECT").put("primary_category","cat_people")
                .put("independently_significant_secondary_subjects",JSONArray())
                .put("contextual_objects",JSONArray().put("PRIVATE_MOCK_CONTEXT food buildings nature"))
                .put("pose","standing").put("scene","restaurant").put("details",JSONArray())
                .put("category_decision_reason","Composition emphasizes the person; other objects are incidental.")
                .put("review_required",false)
            fixture.reply=Fixture.response(listOf("cat_people"),text=payload.toString())
            val batch=fixture.analyze()
            val item=fixture.item()
            assertEquals("PROPOSED",item.analysisStatus)
            val stored=JSONObject(item.analysisJson)
            assertEquals("PRIVATE_MOCK_SUBJECT",stored.getString("primary_subject"))
            assertEquals(1,stored.getJSONArray("contextual_objects").length())
            assertEquals(listOf("cat_people"),fixture.db.virtualGalleryDao().links(item.id)
                .filter { it.origin=="EFFECTIVE" }.map { it.categoryId })
            val snapshot=com.aimediasorter.app.data.diagnostics.Diagnostics.initialize(context).snapshot()
            val events=snapshot.events.filter { it.batchId==batch }
            assertTrue(events.any { it.metrics[com.aimediasorter.app.domain.diagnostics.DiagnosticMetric.ANALYSIS_PROTOCOL_VERSION]==3L })
            assertFalse(events.joinToString { com.aimediasorter.app.domain.diagnostics.DiagnosticPrivacy.encode(it) }.contains("PRIVATE_MOCK"))
            assertArrayEquals(original,fixture.photo.readBytes());assertEquals(modified,fixture.photo.lastModified())
            assertTrue(fixture.destination.listFiles()!!.isEmpty())
        } finally { fixture.close() }
    }
    @Test fun realJpegDiagnosticReportKeepsExactFailureStageAndAnonymizesOriginal()=runBlocking {
        val fixture=Fixture(context,instrumentation.context)
        try {
            com.aimediasorter.app.data.diagnostics.Diagnostics.initialize(context).snapshot()
            fixture.configure()
            val before=fixture.photo.readBytes()
            fixture.reply="""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\"reason\":\"AQ.untrusted-invalid-json\""}]}}]}"""
            val batch=fixture.analyze()
            val snapshot=com.aimediasorter.app.data.diagnostics.Diagnostics.initialize(context).snapshot()
            val prepared=com.aimediasorter.app.domain.diagnostics.DiagnosticExport.prepare(snapshot,
                com.aimediasorter.app.domain.diagnostics.DiagnosticScope.LAST_ANALYSIS,
                com.aimediasorter.app.domain.diagnostics.DiagnosticFormat.JSON,
                com.aimediasorter.app.domain.diagnostics.DiagnosticEnvironment("qa",5,"14",34,"emulator"))
            val report=com.aimediasorter.app.domain.diagnostics.DiagnosticExport.decodeReport(prepared.text)
            assertEquals(batch,report.batchId)
            val file=report.files.single()
            assertEquals(com.aimediasorter.app.domain.diagnostics.DiagnosticRequestStatus.SENT,file.requestStatus)
            assertEquals(com.aimediasorter.app.domain.diagnostics.DiagnosticStage.JSON_PARSE,file.lastStage)
            assertEquals(com.aimediasorter.app.domain.diagnostics.DiagnosticValidation.JSON_SYNTAX,file.validation)
            assertEquals("RESPONSE_JSON",file.errorCode);assertEquals(200,file.httpStatus);assertEquals(1L,file.requestsSent)
            assertTrue(report.events.any { it.stage==com.aimediasorter.app.domain.diagnostics.DiagnosticStage.JPEG_ENCODE })
            assertEquals(1L,report.batchSummary!!.metrics[com.aimediasorter.app.domain.diagnostics.DiagnosticMetric.FAILED])
            assertFalse(prepared.text.contains(fixture.photo.name));assertFalse(prepared.text.contains(fixture.source.path))
            assertFalse(prepared.text.contains("AQ.untrusted"));assertArrayEquals(before,fixture.photo.readBytes())
        } finally { fixture.close() }
    }
    @Test fun savedPreLoggerFailureCanBeExportedWithoutReadingOrReanalyzingMedia()=runBlocking {
        val fixture=Fixture(context,instrumentation.context)
        try {
            fixture.configure();fixture.analyze()
            val row=fixture.item()
            fixture.db.mediaItemDao().updateMediaItem(row.copy(analysisStatus="FAILED",diagnosticCode="UNRECORDED",
                apiRequestsSent=-1,requestReservations=-1,responseHttpStatus=0,
                primarySubjectNote="AQ.synthetic-secret /storage/private.jpg untrusted-provider-text"))
            val session=fixture.db.analysisSessionDao().latest()!!
            fixture.db.analysisSessionDao().upsertSession(session.copy(totalClassified=0,uncertainCount=0,failedCount=1,
                successfulCount=-1,pendingCount=-1,apiRequestsSent=-1))
            val requests=fixture.posts.size
            // Make source access impossible for this call; the export must use saved metadata only.
            assertTrue(fixture.photo.delete())
            val stored=fixture.repo.storedAnalysisDiagnostics()!!
            assertNull(fixture.repo.storedAnalysisDiagnostics(System.currentTimeMillis()+1000))
            val prepared=com.aimediasorter.app.domain.diagnostics.DiagnosticExport.prepare(stored.second,
                com.aimediasorter.app.domain.diagnostics.DiagnosticScope.LAST_ANALYSIS,
                com.aimediasorter.app.domain.diagnostics.DiagnosticFormat.JSON,
                com.aimediasorter.app.domain.diagnostics.DiagnosticEnvironment("qa",5,"14",34,"emulator"))
            val report=com.aimediasorter.app.domain.diagnostics.DiagnosticExport.decodeReport(prepared.text)
            assertTrue(report.includesHistoricalStoredResults)
            assertEquals(1L,report.batchSummary!!.metrics[com.aimediasorter.app.domain.diagnostics.DiagnosticMetric.FAILED])
            assertFalse(report.batchSummary!!.metrics.containsKey(com.aimediasorter.app.domain.diagnostics.DiagnosticMetric.REQUESTS_SENT))
            assertNull(report.files.single().requestsSent);assertNull(report.files.single().elapsedMs)
            assertFalse(prepared.text.contains("AQ.synthetic"));assertFalse(prepared.text.contains("private.jpg"));assertFalse(prepared.text.contains("untrusted-provider-text"))
            assertEquals(requests,fixture.posts.size)
        } finally { fixture.close() }
    }
    private class Fixture(val target: Context,val control: Context) {
        val root=File(target.cacheDir,"photo-diagnostics-"+UUID.randomUUID()).apply { mkdirs() }
        val source=File(root,"selected").apply { mkdirs() }
        val destination=File(root,"destination").apply { mkdirs() }
        val photo=File(source,"public-fixture.jpg")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val db=Room.inMemoryDatabaseBuilder(target,AppDatabase::class.java).build()
        val settings=SettingsRepository(target,PreferenceDataStoreFactory.create(scope=scope,produceFile={File(root,"qa.preferences_pb")}))
        val name="photo-diagnostics-"+UUID.randomUUID()+".db"
        val posts=mutableListOf<JSONObject>()
        var http=200
        var reply: String?=null
        var multi=false
        val cloud=CloudAnalysisService(target,GeminiClient(CloudTransport { _,_,body ->
            if(body==null) CloudHttpResponse(200,"""{"models":[{"name":"models/gemini-test","supportedGenerationMethods":["generateContent"],"inputModalities":["TEXT","IMAGE"]}]}""")
            else {
                val request=JSONObject(body);posts+=request
                val enum=request.getJSONObject("generationConfig").getJSONObject("responseSchema").getJSONObject("properties")
                    .getJSONObject("primary_category").getJSONArray("enum")
                val test=enum.length()==2 && enum.getString(1)=="test_scene"
                CloudHttpResponse(http,reply ?: response(if(test) listOf("test_scene") else if(multi) listOf("cat_food","cat_people") else listOf("cat_food")))
            }
        }),namespace="photo-diagnostics-"+UUID.randomUUID(),databaseName=name)
        val repo=MediaRepository(target,db,settings,cloud)
        init {
            control.assets.open("real_photo_fruits.jpg").use { input->photo.outputStream().use { input.copyTo(it) } }
            check(photo.setLastModified(1700000000000))
        }
        suspend fun configure(limit: Int=100) {
            repo.ensureDefaultCategories()
            settings.updateSourceUri(source.path);settings.updateDestinationUri(destination.path)
            settings.updateCloudOptions(CloudOptions(model="models/gemini-test",maxRequests=limit,maxDailyRequests=100,retries=0,minimumIntervalSeconds=0))
            cloud.vault.save("AQ.SyntheticRealPhotoDiagnosticsCredential")
            cloud.state.replaceModels(cloud.vault.revision,listOf("models/gemini-test"))
            cloud.state.markModelVerified(cloud.vault.revision,"models/gemini-test")
        }
        suspend fun analyze(force: Boolean=false): String {
            val options=settings.settingsFlow.first()
            val batch=repo.authorizeCloudBatch(options,force=force)
            repo.executeMediaScanAndAnalysis(options,batch,forceReanalysis=force)
            return batch
        }
        suspend fun item()=db.mediaItemDao().getCurrentMediaItems(source.path).single()
        fun close() {
            cloud.vault.clear();cloud.state.close();target.deleteDatabase(name)
            db.close();scope.cancel();root.deleteRecursively()
        }
        companion object {
            fun response(ids: List<String>,finish: String="STOP",text: String?=null): String {
                val d=JSONObject().put("primary_subject","Important visible subject").put("primary_category",ids.firstOrNull().orEmpty())
                    .put("independently_significant_secondary_subjects",JSONArray(ids.drop(1).map { id ->
                        JSONObject().put("description","Independently important subject").put("category",id)
                            .put("significance_reason","This subject is deliberately featured in the composition.")
                    })).put("contextual_objects",JSONArray())
                    .put("pose","").put("scene","table").put("details",JSONArray()).put("category_decision_reason","Meaningful visual subject.")
                    .put("review_required",false)
                return JSONObject().put("candidates",JSONArray().put(JSONObject().put("finishReason",finish).put("content",
                    JSONObject().put("parts",JSONArray().put(JSONObject().put("thought",true).put("text","ignored synthetic thought"))
                        .put(JSONObject().put("text",text ?: d.toString()))))))
                    .put("usageMetadata",JSONObject().put("promptTokenCount",50).put("candidatesTokenCount",70).put("thoughtsTokenCount",15)).toString()
            }
        }
    }
    private fun fixture()=Fixture(context,instrumentation.context)

    @Test fun modelTestAndRealPhotoShareGenerationSettingsSchemaAndJpegMime()=runBlocking {
        val f=fixture()
        try {
            f.configure();val original=f.photo.readBytes()
            f.cloud.testSelectedModel(f.settings.settingsFlow.first().cloudOptions)
            f.analyze()
            assertEquals(2,f.posts.size)
            val synthetic=f.posts[0];val actual=f.posts[1]
            val a=synthetic.getJSONObject("generationConfig");val b=actual.getJSONObject("generationConfig")
            assertEquals(4096,a.getInt("maxOutputTokens"));assertEquals(a.getInt("maxOutputTokens"),b.getInt("maxOutputTokens"))
            assertEquals("application/json",b.getString("responseMimeType"))
            val realProperties=b.getJSONObject("responseSchema").getJSONObject("properties")
            val syntheticProperties=a.getJSONObject("responseSchema").getJSONObject("properties")
            val realEnum=realProperties.getJSONObject("primary_category").getJSONArray("enum")
            assertEquals(10,realEnum.length()) // Nine configured IDs plus explicit abstention.
            syntheticProperties.getJSONObject("primary_category").put("enum",realEnum)
            syntheticProperties.getJSONObject("independently_significant_secondary_subjects").getJSONObject("items").getJSONObject("properties")
                .getJSONObject("category").put("enum",realProperties.getJSONObject("independently_significant_secondary_subjects")
                    .getJSONObject("items").getJSONObject("properties").getJSONObject("category").getJSONArray("enum"))
            assertTrue("The schema and generation configuration must match after configured IDs are substituted.",a.toString()==b.toString())
            assertTrue(synthetic.getJSONObject("systemInstruction").toString()==actual.getJSONObject("systemInstruction").toString())
            for(request in f.posts) {
                val inline=request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData")
                assertEquals("image/jpeg",inline.getString("mimeType"))
                val bytes=android.util.Base64.decode(inline.getString("data"),android.util.Base64.DEFAULT)
                assertTrue(bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte())
                val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                assertNotNull(bitmap);assertTrue(maxOf(bitmap.width,bitmap.height)<=512);bitmap.recycle()
                assertFalse(request.toString().contains(f.photo.name))
                assertFalse(request.toString().contains("AQ.SyntheticRealPhotoDiagnosticsCredential"))
            }
            val item=f.item()
            assertEquals("PROPOSED",item.analysisStatus);assertEquals("SUCCESS",item.diagnosticCode)
            assertEquals(1,item.apiRequestsSent);assertEquals(1,item.requestReservations);assertEquals(200,item.responseHttpStatus)
            assertEquals(1,f.repo.analysisProgress.value.successfulCount)
            // The synthetic model-test call is NOT counted in the media batch.
            assertEquals(1,f.repo.analysisProgress.value.apiRequestsSent)
            assertArrayEquals(original,f.photo.readBytes());assertTrue(f.destination.listFiles()!!.isEmpty())
        } finally { f.close() }
    }

    @Test fun cameraSizedRotatedJpegProducesValidBoundedJpegWithoutExifOrSourceMutation()=runBlocking {
        val f=fixture()
        try {
            val input=BitmapFactory.decodeFile(f.photo.path)
            val large=Bitmap.createScaledBitmap(input,2048,1536,true)
            f.photo.outputStream().use { check(large.compress(Bitmap.CompressFormat.JPEG,92,it)) }
            large.recycle();input.recycle()
            ExifInterface(f.photo.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,"Public synthetic regression metadata")
                saveAttributes()
            }
            val original=f.photo.readBytes()
            val images=CloudMediaEncoder(context).images(Uri.fromFile(f.photo),f.photo.path,false,0,false)
            val bytes=images.single().bytes
            assertNull(images.single().timestampMs);assertTrue(bytes.size<=349_000)
            val result=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            try { assertEquals(384,result.width);assertEquals(512,result.height) } finally { result.recycle() }
            val exif=ExifInterface(bytes.inputStream())
            assertNull(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
            assertArrayEquals(original,f.photo.readBytes())
        } finally { f.close() }
    }

    @Test fun corruptJpegFailsLocallyWithoutReservingQuotaOrSendingApiRequest()=runBlocking {
        val f=fixture()
        try {
            f.configure();f.photo.writeBytes(byteArrayOf(0xff.toByte(),0xd8.toByte(),1,2,3))
            val original=f.photo.readBytes();f.analyze()
            val item=f.item()
            assertEquals("FAILED",item.analysisStatus);assertEquals("LOCAL_IMAGE_DECODE",item.diagnosticCode)
            assertEquals(0,item.apiRequestsSent);assertEquals(0,item.requestReservations)
            assertEquals(0,f.posts.size);assertTrue(f.cloud.state.usage().startsWith("0 /"))
            assertEquals(1,f.repo.analysisProgress.value.failedCount)
            assertEquals(0,f.repo.analysisProgress.value.apiRequestsSent)
            assertArrayEquals(original,f.photo.readBytes())
        } finally { f.close() }
    }

    @Test fun httpJsonSchemaCategoryAndTokenFailuresRemainDistinctAndSanitized()=runBlocking {
        val cases=listOf(
            Triple(400,"DO_NOT_EXPOSE_PROVIDER_BODY",AnalysisCode.HTTP_REQUEST),
            Triple(401,"DO_NOT_EXPOSE_PROVIDER_BODY",AnalysisCode.HTTP_AUTH),
            Triple(403,"DO_NOT_EXPOSE_PROVIDER_BODY",AnalysisCode.HTTP_AUTH),
            Triple(404,"DO_NOT_EXPOSE_PROVIDER_BODY",AnalysisCode.HTTP_MODEL),
            Triple(429,"DO_NOT_EXPOSE_PROVIDER_BODY",AnalysisCode.HTTP_QUOTA),
            Triple(503,"DO_NOT_EXPOSE_PROVIDER_BODY",AnalysisCode.HTTP_SERVER),
            Triple(200,"not JSON",AnalysisCode.RESPONSE_ENVELOPE),
            Triple(200,Fixture.response(listOf("cat_food"),text="not JSON"),AnalysisCode.RESPONSE_JSON),
            Triple(200,Fixture.response(listOf("cat_food"),text="{}"),AnalysisCode.RESPONSE_SCHEMA),
            Triple(200,Fixture.response(listOf("unsupported")),AnalysisCode.RESPONSE_CATEGORIES),
            Triple(200,Fixture.response(listOf("cat_food"),finish="MAX_TOKENS"),AnalysisCode.RESPONSE_MAX_TOKENS))
        for((http,body,code) in cases) {
            val f=fixture()
            try {
                f.configure();f.http=http;f.reply=body;val original=f.photo.readBytes();f.analyze()
                val item=f.item()
                assertEquals(code.name,item.diagnosticCode);assertEquals("FAILED",item.analysisStatus)
                assertEquals(1,item.apiRequestsSent);assertEquals(1,item.requestReservations);assertEquals(http,item.responseHttpStatus)
                assertEquals(1,f.posts.size);assertEquals("GEMINI",item.inferenceEngine)
                assertFalse(item.primarySubjectNote.contains("DO_NOT_EXPOSE_PROVIDER_BODY"))
                assertFalse(item.primarySubjectNote.contains(f.photo.name))
                assertEquals(0,f.db.virtualGalleryDao().links(item.id).size)
                assertArrayEquals(original,f.photo.readBytes());assertTrue(f.destination.listFiles()!!.isEmpty())
            } finally { f.close() }
        }
    }

    @Test fun quotaPendingAndCacheHitAreNotCountedAsNewSentRequests()=runBlocking {
        val f=fixture()
        try {
            f.configure(limit=1)
            val second=File(f.source,"second.jpg")
            f.photo.copyTo(second)
            ExifInterface(second.path).apply { setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,"Distinct fixture bytes");saveAttributes() }
            f.analyze()
            val items=f.db.mediaItemDao().getCurrentMediaItems(f.source.path)
            assertEquals(1,items.count { it.analysisStatus=="PROPOSED" })
            val pending=items.single { it.analysisStatus=="PENDING" }
            assertEquals("LIMIT_REACHED",pending.diagnosticCode);assertEquals(0,pending.apiRequestsSent);assertEquals(0,pending.requestReservations)
            val first=f.repo.analysisProgress.value
            assertEquals(1,first.successfulCount);assertEquals(0,first.needsReviewCount);assertEquals(0,first.failedCount)
            assertEquals(1,first.pendingCount);assertEquals(1,first.apiRequestsSent)
            f.analyze()
            assertEquals(2,f.posts.size)
            assertEquals(0,f.repo.analysisProgress.value.pendingCount);assertEquals(1,f.repo.analysisProgress.value.apiRequestsSent)
            assertEquals(1,f.db.mediaItemDao().getCurrentMediaItems(f.source.path).count { it.diagnosticCode=="CACHE_HIT" && it.apiRequestsSent==0 })
            assertTrue(f.destination.listFiles()!!.isEmpty())
        } finally { f.close() }
    }

    @Test fun thirtySixRealJpegInputsCompleteWithAccurateSummaryAndNoPhysicalChanges()=runBlocking {
        val f=fixture()
        try {
            f.configure()
            repeat(35) { i ->
                val file=File(f.source,"fixture-"+i+".jpg")
                f.photo.copyTo(file)
                ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,"Synthetic unique fixture "+i);saveAttributes() }
            }
            val originals=f.source.listFiles()!!.associateWith { it.readBytes() }
            f.analyze()
            val summary=f.repo.analysisProgress.value
            assertEquals(36,summary.processedFiles);assertEquals(36,summary.successfulCount)
            assertEquals(0,summary.needsReviewCount);assertEquals(0,summary.failedCount);assertEquals(0,summary.pendingCount)
            assertEquals(36,summary.apiRequestsSent);assertEquals(36,f.posts.size)
            val session=f.db.analysisSessionDao().latestForSource(f.source.path)!!
            assertEquals(36,session.successfulCount);assertEquals(36,session.apiRequestsSent)
            assertEquals(36,f.db.mediaItemDao().getCurrentMediaItems(f.source.path).size)
            originals.forEach { (file,bytes)->assertArrayEquals(bytes,file.readBytes()) }
            assertTrue(f.destination.listFiles()!!.isEmpty())
        } finally { f.close() }
    }

    @Test fun multiCategoryRealJpegCountsAsReviewAndNeverTriggersTransfer()=runBlocking {
        val f=fixture()
        try {
            f.configure();f.multi=true;val original=f.photo.readBytes();f.analyze()
            val item=f.item()
            assertEquals("NEEDS_REVIEW",item.analysisStatus);assertEquals("SUCCESS",item.diagnosticCode)
            assertEquals(2,f.db.virtualGalleryDao().links(item.id).count { it.origin=="EFFECTIVE" })
            assertEquals(0,f.repo.analysisProgress.value.successfulCount);assertEquals(1,f.repo.analysisProgress.value.needsReviewCount)
            assertEquals(0,f.repo.analysisProgress.value.failedCount);assertEquals(1,f.repo.analysisProgress.value.apiRequestsSent)
            assertArrayEquals(original,f.photo.readBytes());assertTrue(f.destination.listFiles()!!.isEmpty())
        } finally { f.close() }
    }
    /** Selected SAF grant can read pixels; requireOriginal is deliberately denied.
     * Models the documented redaction contract without altering system permissions or user media.
     */
    private fun selectedDocumentContext(photo: ()->File): Context {
        val provider=object: ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri: Uri)=if(uri.path.orEmpty().endsWith("/selected"))
                DocumentsContract.Document.MIME_TYPE_DIR else "image/jpeg"
            override fun query(uri: Uri,p: Array<out String>?,selection: String?,args: Array<out String>?,sort: String?): Cursor {
                val columns=p ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_FLAGS)
                val cursor=MatrixCursor(columns)
                val child=uri.path.orEmpty().endsWith("/children") || uri.path.orEmpty().endsWith("/fixture.jpg")
                val id=if(child) "selected/fixture.jpg" else "selected"
                val values=columns.map { c -> when(c) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID->id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME->if(child) "fixture.jpg" else "selected"
                    DocumentsContract.Document.COLUMN_MIME_TYPE->if(child) "image/jpeg" else DocumentsContract.Document.MIME_TYPE_DIR
                    DocumentsContract.Document.COLUMN_SIZE->if(child) photo().length() else 0L
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED->if(child) photo().lastModified() else 0L
                    DocumentsContract.Document.COLUMN_FLAGS->0
                    else->null
                } }.toTypedArray()
                cursor.addRow(values);return cursor
            }
            override fun insert(uri: Uri,v: ContentValues?): Uri?=null
            override fun update(uri: Uri,v: ContentValues?,s: String?,a: Array<out String>?)=0
            override fun delete(uri: Uri,s: String?,a: Array<out String>?)=0
            override fun openAssetFile(uri: Uri,mode: String): AssetFileDescriptor {
                check(mode=="r")
                if(MediaStore.getRequireOriginal(uri)) throw UnsupportedOperationException("Synthetic original-byte denial")
                return AssetFileDescriptor(ParcelFileDescriptor.open(photo(),ParcelFileDescriptor.MODE_READ_ONLY),0,photo().length())
            }
            override fun openTypedAssetFile(uri: Uri,mime: String,opts: Bundle?,signal: CancellationSignal?)=openAssetFile(uri,"r")
        }
        val resolver=ContentResolver.wrap(provider)
        return object: ContextWrapper(context) {
            override fun getContentResolver()=resolver
            override fun checkCallingOrSelfUriPermission(uri: Uri,mode: Int)=PackageManager.PERMISSION_GRANTED
        }
    }

    @Test fun selectedDocumentAnalysisWorksWithoutTreatingRedactedFingerprintAsOriginalHash()=runBlocking {
        lateinit var f: Fixture
        val selected=selectedDocumentContext { f.photo }
        f=Fixture(selected,instrumentation.context)
        val sourceUri="content://media/tree/selected"
        try {
            f.configure();f.settings.updateSourceUri(sourceUri)
            val original=f.photo.readBytes();f.analyze()
            val item=f.db.mediaItemDao().getCurrentMediaItems(sourceUri).single()
            assertEquals(1,f.posts.size);assertEquals("SUCCESS",item.diagnosticCode)
            assertEquals("NEEDS_REVIEW",item.analysisStatus);assertEquals(1,item.apiRequestsSent)
            assertNull(item.sha256Checksum)
            assertEquals(listOf("cat_food"),f.db.virtualGalleryDao().links(item.id).filter { it.origin=="EFFECTIVE" }.map { it.categoryId })
            assertEquals(1,f.repo.analysisProgress.value.needsReviewCount)
            f.repo.updateVirtualCategories(setOf(item.id),setOf("cat_food"),false)
            assertTrue(f.db.mediaItemDao().getMediaItemById(item.id)!!.needsManualReview)
            try { f.repo.previewOperations(setOf(item.id),TransferMethod.MOVE,emptyMap());fail("Unknown original bytes must block physical operations") }
            catch(_: IllegalArgumentException) {}
            assertArrayEquals(original,f.photo.readBytes());assertTrue(f.destination.listFiles()!!.isEmpty())
        } finally { f.close() }
    }

}
