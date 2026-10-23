package com.aimediasorter.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aimediasorter.app.data.local.*
import com.aimediasorter.app.data.model.*
import com.aimediasorter.app.domain.ai.*
import com.aimediasorter.app.domain.storage.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativePipelineTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun categories() = DefaultCategories.ALL.map {
        CategoryEntity(it.id, it.defaultName, it.defaultDescription, it.colorHex, it.iconKey,
            it.priority, true, true, it.subfolderName)
    }
    private fun temporaryDirectory(): File = File(context.cacheDir, "qa-" + UUID.randomUUID()).apply {
        check(mkdirs())
    }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private fun bitmap(): Bitmap = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawColor(Color.rgb(90, 170, 235))
        canvas.drawRect(0f, 125f, 256f, 192f, Paint().apply { color = Color.GREEN })
        canvas.drawCircle(60f, 40f, 24f, Paint().apply { color = Color.YELLOW })
        canvas.drawRect(110f, 70f, 230f, 160f, Paint().apply { color = Color.RED })
    }

    @Test fun nativePageSizeAndPassthroughCapabilitiesMatchTestEnvironment() {
        val expected = InstrumentationRegistry.getArguments().getString("expectedPageSize")?.toLong()
        if (expected != null) assertEquals(expected, android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val resourceId=context.resources.getIdentifier("media_capabilities","xml",context.packageName)
            assertTrue("The minified app must retain its media-capabilities resource.",resourceId!=0)
            context.resources.getXml(resourceId).use {
                val capabilities = android.media.ApplicationMediaCapabilities.createFromXml(it)
                assertTrue(capabilities.isVideoMimeTypeSupported("video/hevc"))
                assertTrue(capabilities.isHdrTypeSupported(android.media.MediaFeature.HdrType.HDR10))
            }
        }
    }

    @Test fun bundledMlKitActuallyExecutesWithoutInternetPermission() = runBlocking {
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(Manifest.permission.INTERNET, context.packageName))
        val classifier = OnDeviceAiClassifier(context, VisualFeatureExtractor(context))
        val image = bitmap()
        try {
            val result = classifier.classifyBitmap(image, categories(),
                AiEngineMode.HYBRID_ONNX_AND_MLKIT, "", false, .8f)
            assertEquals("ML_KIT_IMAGE_LABELING", result.inferenceEngine)
            assertTrue("Actual SDK labels must be returned.", result.modelLabels.isNotEmpty())
            assertTrue(result.modelLabels.values.all { it.isFinite() && it in 0f..1f })
            assertTrue(result.calibratedConfidence.isFinite())
            println("Real bundled ML Kit labels: " + result.modelLabels)
            // This verifies executable inference, not semantic accuracy on labeled photographs.
        } finally {
            image.recycle()
            classifier.close()
        }
    }

    @Test fun diagnosticOnnxLoadsButCannotApproveAutomaticTransfers() = runBlocking {
        val classifier = OnDeviceAiClassifier(context, VisualFeatureExtractor(context))
        val image = bitmap()
        try {
            val result = classifier.classifyBitmap(image, categories(),
                AiEngineMode.ONNX_EMBEDDER_ONLY, "", false, .5f)
            assertEquals("ONNX_DIAGNOSTIC", result.inferenceEngine)
            assertTrue(result.needsManualReview)
            assertTrue(result.calibratedConfidence.isFinite())
        } finally {
            image.recycle()
            classifier.close()
        }
    }

    @Test fun realVideoDecodingInfersSeveralDistinctFramesAndPreservesBytes() = runBlocking {
        val directory = temporaryDirectory()
        val file = File(directory, "fixture.mp4")
        instrumentation.context.assets.open("test_multiframe.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val before = digest(file)
        val classifier = OnDeviceAiClassifier(context, VisualFeatureExtractor(context))
        try {
            val result = VideoFrameAnalyzer(context, classifier).analyzeVideo(
                Uri.fromFile(file), file.path, 0, categories(),
                AiEngineMode.HYBRID_ONNX_AND_MLKIT, "", false, .8f)
            assertTrue("Expected several decoded distinct frames: " + result.primarySubjectNote,
                result.frameSamples.size >= 3)
            assertTrue(result.frameSamples.map { it.timestampMs }.distinct().size >= 3)
            assertTrue(result.frameSamples.all { it.timestampMs in 1..2999 })
            assertArrayEquals(before, digest(file))
        } finally {
            classifier.close()
            directory.deleteRecursively()
        }
    }

    @Test fun corruptedVideoIsReviewOnlyAndUnchanged() = runBlocking {
        val directory = temporaryDirectory()
        val file = File(directory, "broken.mp4").apply { writeBytes(byteArrayOf(1, 3, 9, 0)) }
        val before = digest(file)
        val classifier = OnDeviceAiClassifier(context, VisualFeatureExtractor(context))
        try {
            val result = VideoFrameAnalyzer(context, classifier).analyzeVideo(
                Uri.fromFile(file), file.path, 0, categories(),
                AiEngineMode.HYBRID_ONNX_AND_MLKIT, "", false, .8f)
            assertTrue(result.needsManualReview)
            assertEquals("cat_other", result.categoryId)
            assertTrue(result.frameSamples.isEmpty())
            assertArrayEquals(before, digest(file))
        } finally {
            classifier.close()
            directory.deleteRecursively()
        }
    }

    @Test fun decoderBoundsPanoramasAndAppliesMirroring() {
        val directory = temporaryDirectory()
        try {
            val panorama = File(directory, "wide.png")
            Bitmap.createBitmap(8192, 32, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
                panorama.outputStream().use { assertTrue(compress(Bitmap.CompressFormat.PNG, 100, it)) }
                recycle()
            }
            val extractor = VisualFeatureExtractor(context)
            val decoded = requireNotNull(extractor.decodeAndNormalizeBitmap(
                Uri.fromFile(panorama), panorama.path, 0, 512))
            assertTrue(decoded.width <= 512 && decoded.height <= 512)
            assertTrue(decoded.width > decoded.height)
            decoded.recycle()
            val asymmetric = File(directory, "asymmetric.png")
            Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.RED)
                for (x in 0..3) for (y in 0..3) setPixel(x, y, Color.BLUE)
                asymmetric.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
            val mirrored = requireNotNull(extractor.decodeAndNormalizeBitmap(
                Uri.fromFile(asymmetric), asymmetric.path, 0, 512, true))
            assertEquals(Color.RED, mirrored.getPixel(0, 0))
            assertEquals(Color.BLUE, mirrored.getPixel(mirrored.width - 1, 0))
            mirrored.recycle()
        } finally { directory.deleteRecursively() }
    }

    @Test fun thousandsOfFilesAreDiscoveredWithoutHiddenPhotoLossOrNestedLoops() = runBlocking {
        val root = temporaryDirectory()
        try {
            repeat(2000) { File(root, "image-" + it + ".jpg").writeBytes(byteArrayOf(1)) }
            File(root, ".private-photo.jpg").writeBytes(byteArrayOf(1))
            File(root, ".aimediasorter-staging.jpg").writeBytes(byteArrayOf(1))
            File(root, "nested").mkdir()
            File(root, "nested/movie.mp4").writeBytes(byteArrayOf(1))
            File(root, "unrelated.txt").writeText("ignore")
            val scanner = StorageScanner(context)
            val all = scanner.scanDirectory(root.path, "", true, MediaTypeFilter.ALL, setOf("jpg", "mp4"))
            assertEquals(2002, all.size)
            assertEquals(2002, all.map { it.uri }.distinct().size)
            assertTrue(all.any { it.fileName == ".private-photo.jpg" })
            assertEquals(2001, scanner.scanDirectory(root.path, "", false,
                MediaTypeFilter.PHOTOS_ONLY, setOf("jpg")).size)
            assertEquals(10, scanner.scanCandidates(root.path, "", true,
                MediaTypeFilter.ALL, setOf("jpg")).take(10).toList().size)
            assertTrue(scanner.isDestinationInsideSource(root.path, File(root, "sorted").path))
        } finally { root.deleteRecursively() }
    }

    @Test fun externalStorageVolumeRootRejectsEveryDestinationOnThatVolume() {
        val scanner = StorageScanner(context)
        val authority = "com.android.externalstorage.documents"
        fun tree(id: String) = android.provider.DocumentsContract.buildTreeDocumentUri(authority, id).toString()
        assertTrue(scanner.isDestinationInsideSource(tree("primary:"), tree("primary:Sorted")))
        assertTrue(scanner.isDestinationInsideSource(tree("primary:Pictures"), tree("primary:Pictures/Sorted")))
        assertFalse(scanner.isDestinationInsideSource(tree("primary:Pictures"), tree("primary:Pictures2")))
        assertFalse(scanner.isDestinationInsideSource(tree("primary:"), tree("ABCD-1234:Sorted")))
    }

    private fun item(uri: String, source: String, session: String) = MediaItemEntity(
        uriString = uri, filePath = null, fileName = "same.jpg", extension = "jpg",
        isVideo = false, mimeType = "image/jpeg", fileSizeBytes = 4, width = 10, height = 10,
        durationMs = 0, originalLastModifiedMs = 1700000000000, exifCaptureDateMs = null,
        quickFingerprint = "same:4:1700000000000", detectedCategoryId = "cat_people",
        detectedCategoryName = "People", effectiveCategoryId = "cat_people", effectiveCategoryName = "People",
        calibratedConfidence = .9f, rawSimilarityScore = 0f, topMargin = .5f, secondaryCategoryName = null,
        analyzedAtMs = 100, sourceRootUri = source, scanSessionId = session, sha256Checksum = "abc",
        analysisSignature = "config", inferenceEngine = "ML_KIT_IMAGE_LABELING"
    )
    private fun session(id: String, source: String, started: Long) = AnalysisSessionEntity(
        id, started, null, source, "destination", 1, 1, 0, 0, 0, "COMPLETED", "config")

    @Test fun roomScopesIdenticalNamesToLatestSourceSessionEvenIfClockGoesBackwards() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            database.analysisSessionDao().upsertSession(session("a-old", "source-a", 1000))
            database.mediaItemDao().upsertMediaItem(item("content://test/a/old", "source-a", "a-old"))
            database.analysisSessionDao().upsertSession(session("b", "source-b", 2000))
            database.mediaItemDao().upsertMediaItem(item("content://test/b/new", "source-b", "b"))
            database.analysisSessionDao().upsertSession(session("a-new", "source-a", 500))
            val id = database.mediaItemDao().upsertMediaItem(item("content://test/a/new", "source-a", "a-new"))
            assertEquals(listOf("content://test/a/new"),
                database.mediaItemDao().getCurrentMediaItems("source-a").map { it.uriString })
            assertEquals(1, database.mediaItemDao().getCurrentMediaItems("source-b").size)
            assertTrue(database.mediaItemDao().getCurrentMediaItems("source-unknown").isEmpty())
            database.mediaItemDao().updateMediaItem(item("content://test/a/new", "source-a", "a-new")
                .copy(id = id, sha256Checksum = null, needsManualReview = true))
            database.mediaItemDao().updateManualCategory(id, "cat_food", "Food", "Food/same.jpg")
            assertTrue(database.mediaItemDao().getMediaItemById(id)!!.needsManualReview)
        } finally { database.close() }
    }

    @Test fun reportsKeepDecimalColumnsValidUnderTurkishLocale() {
        val before = java.util.Locale.getDefault()
        var report: File? = null
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
            report = ReportExporter(context).exportCsvReport(
                listOf(item("content://qa/photo", "source", "session").copy(calibratedConfidence = .1234f)),
                emptyList())
            val csv = report.readText()
            assertTrue(csv.contains(",0.1234,"))
            assertFalse(csv.contains(",0,1234,"))
        } finally {
            java.util.Locale.setDefault(before)
            report?.parentFile?.deleteRecursively()
        }
    }

    @Test fun originalMediaStoreStreamMatchesWrittenBytes() {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "qa-" + UUID.randomUUID() + ".png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AIMediaSorter-QA")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            val bytes = java.io.ByteArrayOutputStream().use { out ->
                bitmap().apply { compress(Bitmap.CompressFormat.PNG, 100, out); recycle() }
                out.toByteArray()
            }
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            assertNotEquals(uri, OriginalMediaAccess(context).originalUri(uri))
            assertArrayEquals(bytes, OriginalMediaAccess(context).input(uri).use { it.readBytes() })
        } finally { resolver.delete(uri, null, null) }
    }
}
