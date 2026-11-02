package com.aimediasorter.app
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aimediasorter.app.data.local.CloudStateStore
import com.aimediasorter.app.data.preferences.GeminiKeyVault
import com.aimediasorter.app.domain.cloud.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CloudNativeTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val options=CloudOptions(mode=AnalysisMode.GEMINI,model="models/gemini-test",
        maxRequests=2,maxDailyRequests=3,estimatedMicrosPerAttempt=100,maxEstimatedBatchMicros=200,retries=0)
    private fun expect(kind: CloudErrorKind,block: ()->Unit) {
        try { block();fail("Expected safe rejection") } catch(e: CloudFailure) { assertEquals(kind,e.kind) }
    }
    @Test fun keystoreEncryptsKeyOutsideBackupsAndClearsIt() {
        val namespace="test-"+UUID.randomUUID()
        val vault=GeminiKeyVault(context,namespace)
        val secret="AQ."+"SyntheticCredential".repeat(32)
        try {
            vault.save(secret);assertTrue(vault.hasKey());assertEquals(secret,GeminiKeyVault(context,namespace).read())
            val encrypted=File(context.noBackupFilesDir,"gemini-key-"+namespace+".v1").readBytes()
            assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains(secret))
            val revision=vault.revision
            vault.save(secret);assertEquals(revision,vault.revision)
            vault.clear();assertFalse(vault.hasKey());assertTrue(vault.revision>revision)
        } finally { vault.clear() }
    }
    @Test fun independentVaultInstancesCannotRaceReadWithReplacement() {
        val namespace="concurrent-"+UUID.randomUUID()
        val writer=GeminiKeyVault(context,namespace)
        val reader=GeminiKeyVault(context,namespace)
        val running=AtomicBoolean(true)
        val failure=AtomicReference<Throwable?>(null)
        var thread: Thread?=null
        try {
            writer.save("AQ.SyntheticInitialCredentialForConcurrentRead")
            thread=Thread {
                while(running.get()) {
                    try { reader.snapshot() } catch(e: Throwable) { failure.compareAndSet(null,e) }
                    Thread.yield()
                }
            }.apply { start() }
            repeat(15) { index ->
                val replacement="AQ.SyntheticReplacementCredential"+index
                writer.save(replacement)
                assertEquals(replacement,reader.read())
                assertEquals(writer.revision,reader.snapshot().revision)
            }
            assertTrue("Concurrent vault reads must not remove an active AtomicFile replacement.",failure.get()==null)
        } finally {
            running.set(false)
            thread?.join(5_000)
            assertTrue("Vault reader must terminate.",thread?.isAlive!=true)
            writer.clear()
        }
    }
    @Test fun durableAttemptBudgetsCacheAndRevocationSurviveReopen() {
        val name="cloud-test-"+UUID.randomUUID()+".db"
        var db=CloudStateStore(context,name)
        try {
            db.replaceModels(7,listOf(options.model))
            val batch=db.authorize("signature",7,options)
            db.reserve(batch,"signature",7)
            db.close();db=CloudStateStore(context,name)
            db.reserve(batch,"signature",7)
            expect(CloudErrorKind.BUDGET) { db.reserve(batch,"signature",7) }
            assertTrue(db.usage().startsWith("2 /"))
            db.begin(batch,"original-hash-and-config")
            expect(CloudErrorKind.NETWORK) { db.begin(batch,"original-hash-and-config") }
            val decision=CloudDecision(listOf("cat_food"),listOf(CloudSubject("Meal","main")),"","",emptyList(),"Meal","CLEAR")
            db.complete(batch,"original-hash-and-config",CloudResponse(decision,11,9))
            assertEquals(decision,db.cached("original-hash-and-config"))
            assertNull(db.cached("different-config"))
            val second=db.authorize("second",7,options)
            expect(CloudErrorKind.CONSENT) { db.reserve(second,"changed",7) }
            expect(CloudErrorKind.CONSENT) { db.reserve(second,"second",8) }
            db.reserve(second,"second",7)
            expect(CloudErrorKind.BUDGET) { db.reserve(second,"second",7) }
            db.revokeAll()
            expect(CloudErrorKind.CONSENT) { db.reserve(second,"second",7) }
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun photoUploadIsBoundedAspectCorrectAndOriginalUnchanged() = runBlocking {
        val directory=File(context.cacheDir,"cloud-photo-"+UUID.randomUUID()).apply { mkdirs() }
        val file=File(directory,"photo.png")
        Bitmap.createBitmap(1200,600,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE);file.outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) };recycle()
        }
        val original=file.readBytes()
        try {
            val images=CloudMediaEncoder(context).images(Uri.fromFile(file),file.path,false,90,false)
            assertEquals(1,images.size);assertNull(images.single().timestampMs)
            val jpeg=images.single().bytes
            val bitmap=BitmapFactory.decodeByteArray(jpeg,0,jpeg.size)
            try { assertEquals(256,bitmap.width);assertEquals(512,bitmap.height) } finally { bitmap.recycle() }
            assertArrayEquals(original,file.readBytes())
            assertTrue(jpeg.size<349_000)
            assertFalse(jpeg.toString(Charsets.ISO_8859_1).contains("Exif"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun videoUploadSamplesSeveralDecodedFramesWithoutChangingOriginal() = runBlocking {
        val file=File(context.cacheDir,"cloud-video-"+UUID.randomUUID()+".mp4")
        instrumentation.context.assets.open("test_multiframe.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val original=MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        try {
            val images=CloudMediaEncoder(context).images(Uri.fromFile(file),file.path,true,0,false)
            assertEquals(3,images.size)
            assertEquals(3,images.map { it.timestampMs }.distinct().size)
            assertTrue(images.all { (it.timestampMs ?: 0) in 1..2999 })
            assertTrue(images.sumOf { it.bytes.size }<=1_048_576)
            assertArrayEquals(original,MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        } finally { file.delete() }
    }
    @Test fun officialLauncherIconIsAdaptiveAndRoundResourceResolves() {
        // Instrumented release tests always use the isolated QA package. Never
        // install a newly signed QA build over the user's production installation.
        val debuggable=(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
        assertEquals(if(debuggable) "com.aimediasorter.app.debug" else "com.aimediasorter.app.qa",context.packageName)
        val info=context.packageManager.getApplicationInfo(context.packageName,0)
        val labelId=context.resources.getIdentifier(if(debuggable) "app_name" else "app_name_qa","string",context.packageName)
        assertTrue(labelId!=0)
        val label=context.getString(labelId)
        assertEquals(label,info.loadLabel(context.packageManager).toString())
        assertTrue(context.getDrawable(info.icon) is AdaptiveIconDrawable)
        val round=context.resources.getIdentifier("ic_launcher_round","mipmap",context.packageName)
        assertTrue("Round launcher resource must survive shrinking.",round!=0)
        assertNotNull(context.getDrawable(round))
    }
    @Test fun cloudSchemaTwoUpgradesAndSentCountsSurviveRestartSeparatelyFromReservations() {
        val name="cloud-migration-"+UUID.randomUUID()+".db"
        var state=CloudStateStore(context,name)
        try {
            state.replaceModels(3,listOf(options.model))
            val batch=state.authorize("fixture",3,options)
            state.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE request_counts");db.version=2
            }
            state=CloudStateStore(context,name)
            assertEquals(listOf(options.model),state.models(3))
            state.reserve(batch,"fixture",3,traceKey="opaque-test-fingerprint")
            assertEquals(0,state.sentInBatch(batch))
            state.recordSent(batch,"opaque-test-fingerprint")
            state.close();state=CloudStateStore(context,name)
            assertEquals(1,state.sentInBatch(batch))
            assertTrue(state.usage().startsWith("1 /"))
        } finally { state.close();context.deleteDatabase(name) }
    }

    /** NEVER enabled by CI. The operator must configure BYOK/model in app settings first.
     * Uses a synthetic JPEG only. This verifies live protocol, not semantic accuracy.
     */
    @Test fun optionalLiveGeminiProtocolRequiresExplicitOperatorConfiguration() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowCloudUpload")=="true")
        val model=args.getString("liveGeminiModel").orEmpty()
        assumeTrue(validModel(model))
        val vault=GeminiKeyVault(context)
        assumeTrue(vault.hasKey())
        val key=vault.read()
        val dbName="live-opt-in-"+UUID.randomUUID()+".db"
        val db=CloudStateStore(context,dbName)
        val file=File(context.cacheDir,"live-synthetic-"+UUID.randomUUID()+".png")
        try {
            val client=GeminiClient(GeminiHttpTransport())
            val accessible=client.models(key)
            require(model in accessible)
            val limits=options.copy(model=model,maxRequests=1,maxDailyRequests=1)
            db.replaceModels(vault.revision,accessible)
            val batch=db.authorize("explicit-synthetic-live-test",vault.revision,limits)
            Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE);file.outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) };recycle()
            }
            val images=CloudMediaEncoder(context).images(Uri.fromFile(file),file.path,false,0,false)
            val cat=com.aimediasorter.app.data.local.CategoryEntity("cat_other","Other","Unknown scene",0,"label",1,destinationSubfolder="Other")
            val response=client.classify(key,limits,listOf(cat),images) {
                db.reserve(batch,"explicit-synthetic-live-test",vault.revision)
            }
            assertTrue(response.decision.categories.all { it=="cat_other" })
        } finally { db.close();context.deleteDatabase(dbName);file.delete() }
    }
}
