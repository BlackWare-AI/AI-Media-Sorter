package com.aimediasorter.app.domain.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.local.MediaItemEntity
import com.aimediasorter.app.data.model.TimestampPolicy
import com.aimediasorter.app.data.model.TransferMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

@Serializable
data class FileOperationExportRecord(
    val id: Long,
    val originalPathOrUri: String,
    val resolvedPosixSourcePath: String?,
    val requiresSafAccessOnly: Boolean,
    val destinationDirectory: String,
    val destinationFileName: String,
    val detectedCategory: String,
    val effectiveCategory: String,
    val isManuallyCorrected: Boolean,
    val classificationScoreUncalibrated: Float,
    val rawCosineSimilarity: Float,
    val scoreInterpretationNote: String = "Model/ranking score; not measured accuracy.",
    val mediaType: String,
    val originalModificationTimestampMs: Long,
    val originalModificationEpochSec: Long,
    val originalModificationIsoUtc: String,
    val requestedOperation: String,
    val sourceRootDirectory: String?,
    val destinationRootDirectory: String?,
    val sourceSha256: String?,
    val needsManualReview: Boolean,
    val timestampPolicy: String
)

@Serializable
data class FileOperationsManifestPayload(
    val schemaVersion: String = "2.0",
    val generatedAtIsoUtc: String,
    val modelNotice: String = "Classification uses on-device inference. Scores are not measured accuracy. Diagnostic ONNX results require review.",
    val defaultExecutionMode: String = "DRY_RUN",
    val totalRecords: Int,
    val safRestrictedCount: Int,
    val operations: List<FileOperationExportRecord>
)

data class ExportBundleResult(
    val exportDirectoryPath: String,
    val includedFilesCount: Int,
    val safRestrictedCount: Int,
    val generatedFiles: List<File>
)

class TermuxOperationExporter(private val context: Context) {
    companion object {
        const val FILE_CLASSIFICATION_CSV = "classification_report.csv"
        const val FILE_OPERATIONS_JSON = "file_operations.json"
        const val FILE_MOVE_SCRIPT_SH = "move_files.sh"
        const val FILE_COPY_SCRIPT_SH = "copy_files.sh"
    }
    private val jsonFormat = Json { prettyPrint = true; encodeDefaults = true }
    fun getExportDirectory(): File =
        File(context.cacheDir, "exports/termux_" + UUID.randomUUID()).apply {
            check(mkdirs()) { "Could not create private export directory." }
        }

    /** Only the standard external-storage provider has this documented mapping. */
    fun resolvePosixPathOrNull(filePath: String?, uriString: String): String? {
        if (!filePath.isNullOrBlank() && filePath.startsWith("/")) return filePath
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") return uri.path?.takeIf { it.startsWith("/") }
        if (uri.scheme != "content" || uri.authority != "com.android.externalstorage.documents") return null
        val documentId = runCatching {
            if (uriString.contains("/document/")) DocumentsContract.getDocumentId(uri)
            else DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return null
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "")
        if (relative.split('/').any { it == ".." || it == "." }) return null
        return when {
            volume == "primary" -> "/storage/emulated/0/" + relative
            volume.matches(Regex("[0-9a-fA-F]{4}-[0-9a-fA-F]{4}")) -> "/storage/" + volume + "/" + relative
            else -> null
        }
    }
    fun resolveDestinationPosixRoot(destinationUriOrPath: String): String? =
        resolvePosixPathOrNull(destinationUriOrPath.takeIf { it.startsWith("/") }, destinationUriOrPath)?.trimEnd('/')

    fun buildExportRecords(
        items: List<MediaItemEntity>, categoriesMap: Map<String, CategoryEntity>,
        destinationRootUriOrPath: String, requestedMethod: TransferMethod,
        sourceRootUri: String = "",
        timestampPolicy: TimestampPolicy = TimestampPolicy.FALLBACK_COPY_KEEP_ORIGINAL
    ): List<FileOperationExportRecord> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return items.map { item ->
            val category = categoriesMap[item.effectiveCategoryId]
            val subfolder = SafeRelativePath.segments(category?.destinationSubfolder ?: item.effectiveCategoryName).joinToString("/")
            val rootUri = category?.customDestinationUri?.takeIf { it.isNotBlank() } ?: destinationRootUriOrPath
            val destinationRoot = resolveDestinationPosixRoot(rootUri)
            val source = resolvePosixPathOrNull(item.filePath, item.uriString)
            val sourceRoot = resolvePosixPathOrNull(sourceRootUri.takeIf { it.startsWith("/") }, sourceRootUri)
                ?: resolvePosixPathOrNull(null, item.uriString.substringBefore("/document/"))
                ?: source?.substringBeforeLast('/')
            FileOperationExportRecord(
                id = item.id, originalPathOrUri = item.uriString,
                resolvedPosixSourcePath = source,
                requiresSafAccessOnly = source == null || destinationRoot == null,
                destinationDirectory = destinationRoot?.let { it + "/" + subfolder } ?: rootUri,
                destinationFileName = SafeRelativePath.fileName(item.fileName),
                scoreInterpretationNote = if (item.inferenceEngine.startsWith("GEMINI") && item.inferenceEngine != "GEMINI_HYBRID_AGREEMENT") "Gemini numeric confidence unavailable; legacy zero is not a probability." else "Model score; not measured accuracy.",
                detectedCategory = item.detectedCategoryName, effectiveCategory = item.effectiveCategoryName,
                isManuallyCorrected = item.isManuallyCorrected,
                classificationScoreUncalibrated = item.calibratedConfidence,
                rawCosineSimilarity = item.rawSimilarityScore,
                mediaType = if (item.isVideo) "VIDEO" else "PHOTO",
                originalModificationTimestampMs = item.originalLastModifiedMs,
                originalModificationEpochSec = item.originalLastModifiedMs / 1000,
                originalModificationIsoUtc = if (item.originalLastModifiedMs > 0) dateFormat.format(Date(item.originalLastModifiedMs)) else "",
                requestedOperation = requestedMethod.name, sourceRootDirectory = sourceRoot,
                destinationRootDirectory = destinationRoot, sourceSha256 = item.sha256Checksum,
                needsManualReview = item.needsManualReview || item.sha256Checksum == null || category?.isEnabled != true,
                timestampPolicy = if (timestampPolicy == TimestampPolicy.FALLBACK_COPY_KEEP_ORIGINAL) "keep-original" else "best-effort"
            )
        }
    }
    private fun payload(records: List<FileOperationExportRecord>): String =
        jsonFormat.encodeToString(FileOperationsManifestPayload(
            generatedAtIsoUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()),
            totalRecords = records.size, safRestrictedCount = records.count { it.requiresSafAccessOnly },
            operations = records
        ))

    fun exportClassificationCsv(records: List<FileOperationExportRecord>, outputDir: File = getExportDirectory()): File {
        val file = File(outputDir, FILE_CLASSIFICATION_CSV)
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("original_path_or_uri,resolved_posix_path,requires_saf_only,destination_directory,destination_filename,detected_category,effective_category,manually_corrected,classification_score_uncalibrated,raw_cosine_similarity,media_type,original_modified_ms,requested_operation,needs_review,source_sha256")
            records.forEach { record ->
                writer.appendLine(listOf(
                    record.originalPathOrUri, record.resolvedPosixSourcePath.orEmpty(),
                    record.requiresSafAccessOnly.toString(), record.destinationDirectory,
                    record.destinationFileName, record.detectedCategory, record.effectiveCategory,
                    record.isManuallyCorrected.toString(),
                    "%.4f".format(Locale.US, record.classificationScoreUncalibrated),
                    "%.4f".format(Locale.US, record.rawCosineSimilarity), record.mediaType,
                    record.originalModificationTimestampMs.toString(), record.requestedOperation,
                    record.needsManualReview.toString(), record.sourceSha256.orEmpty()
                ).joinToString(",") { escapeCsv(it) })
            }
        }
        return file
    }
    fun exportFileOperationsJson(records: List<FileOperationExportRecord>, outputDir: File = getExportDirectory()): File =
        File(outputDir, FILE_OPERATIONS_JSON).apply { writeText(payload(records), Charsets.UTF_8) }

    fun exportTermuxScript(records: List<FileOperationExportRecord>, operationMode: TransferMethod, outputDir: File = getExportDirectory()): File {
        val executor = context.assets.open("termux/manifest_executor.py").bufferedReader().use { it.readText() }
        return File(outputDir, if (operationMode == TransferMethod.MOVE) FILE_MOVE_SCRIPT_SH else FILE_COPY_SCRIPT_SH).apply {
            writeText(TermuxScriptBuilder.build(executor, payload(records), operationMode.name), Charsets.UTF_8)
        }
    }
    fun exportAll(
        items: List<MediaItemEntity>, categoriesMap: Map<String, CategoryEntity>,
        destinationRootUriOrPath: String, defaultMethod: TransferMethod,
        outputDir: File = getExportDirectory(), sourceRootUri: String = "",
        timestampPolicy: TimestampPolicy = TimestampPolicy.FALLBACK_COPY_KEEP_ORIGINAL
    ): ExportBundleResult {
        val records = buildExportRecords(items, categoriesMap, destinationRootUriOrPath, defaultMethod, sourceRootUri, timestampPolicy)
        val files = listOf(
            exportClassificationCsv(records, outputDir), exportFileOperationsJson(records, outputDir),
            exportTermuxScript(records, TransferMethod.MOVE, outputDir),
            exportTermuxScript(records, TransferMethod.COPY, outputDir)
        )
        return ExportBundleResult(outputDir.absolutePath, records.size, records.count { it.requiresSafAccessOnly }, files)
    }
    fun escapePosixSingleQuoted(raw: String): String = TermuxScriptBuilder.escapePosixSingleQuoted(raw)
    private fun escapeCsv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
