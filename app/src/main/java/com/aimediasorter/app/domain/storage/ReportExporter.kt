package com.aimediasorter.app.domain.storage

import android.content.Context
import com.aimediasorter.app.data.local.MediaItemEntity
import com.aimediasorter.app.data.local.TransferLogEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID

@Serializable
data class DiagnosticExportPayload(
    val exportedAtMs: Long,
    val totalMediaItems: Int,
    val totalTransferLogs: Int,
    val mediaItems: List<MediaItemEntity>,
    val transferLogs: List<TransferLogEntity>
)

class ReportExporter(private val context: Context) {

    private val jsonFormat = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun exportCsvReport(
        mediaItems: List<MediaItemEntity>,
        logs: List<TransferLogEntity>
    ): File {
        val exportDir = File(context.cacheDir, "exports/" + UUID.randomUUID()).apply { check(mkdirs()) }
        val csvFile = File(exportDir, "ai_media_sorter_report_${System.currentTimeMillis()}.csv")

        csvFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(
                "ID,FileName,Type,Extension,SizeBytes,OriginalLastModifiedMs,ExifCaptureDateMs," +
                "DetectedCategory,EffectiveCategory,ModelScore,DiagnosticRawSimilarity,TopMargin," +
                "NeedsManualReview,IsManuallyCorrected,Excluded,TransferStatus,SHA256,TimestampVerified,InferenceEngine\n"
            )
            for (item in mediaItems) {
                writer.write(
                    listOf(
                        item.id.toString(),
                        escapeCsv(item.fileName),
                        if (item.isVideo) "VIDEO" else "PHOTO",
                        item.extension,
                        item.fileSizeBytes.toString(),
                        item.originalLastModifiedMs.toString(),
                        (item.exifCaptureDateMs ?: "").toString(),
                        escapeCsv(item.detectedCategoryName),
                        escapeCsv(item.effectiveCategoryName),
                        if (item.inferenceEngine in setOf("GEMINI", "GEMINI_HYBRID_REVIEW")) "" else "%.4f".format(Locale.US, item.calibratedConfidence),
                        "%.4f".format(Locale.US, item.rawSimilarityScore),
                        "%.4f".format(Locale.US, item.topMargin),
                        item.needsManualReview.toString(),
                        item.isManuallyCorrected.toString(),
                        item.isExcludedFromTransfer.toString(),
                        item.transferStatus,
                        item.sha256Checksum ?: "",
                        (item.timestampVerified ?: "").toString(),
                        escapeCsv(item.inferenceEngine)
                    ).joinToString(",") + "\n"
                )
            }
        }
        return csvFile
    }

    fun exportJsonDiagnostic(
        mediaItems: List<MediaItemEntity>,
        logs: List<TransferLogEntity>
    ): File {
        val exportDir = File(context.cacheDir, "exports/" + UUID.randomUUID()).apply { check(mkdirs()) }
        val jsonFile = File(exportDir, "ai_media_sorter_diagnostics_${System.currentTimeMillis()}.json")
        val payload = DiagnosticExportPayload(
            exportedAtMs = System.currentTimeMillis(),
            totalMediaItems = mediaItems.size,
            totalTransferLogs = logs.size,
            mediaItems = mediaItems,
            transferLogs = logs
        )
        jsonFile.writeText(jsonFormat.encodeToString(payload), Charsets.UTF_8)
        return jsonFile
    }

    private fun escapeCsv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
