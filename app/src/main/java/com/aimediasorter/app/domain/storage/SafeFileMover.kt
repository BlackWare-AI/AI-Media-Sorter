package com.aimediasorter.app.domain.storage

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import com.aimediasorter.app.data.local.MediaItemEntity
import com.aimediasorter.app.data.local.TransferLogEntity
import com.aimediasorter.app.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class SingleTransferOutcome(
    val itemId: Long,
    val status: TransferItemStatus,
    val destinationUriString: String?,
    val resolvedFileName: String,
    val sha256Checksum: String?,
    val timestampPreserved: Boolean,
    val destinationLastModifiedMs: Long?,
    val collisionResolutionNote: String?,
    val detailsMessage: String,
    val logEntry: TransferLogEntity
)


class SafeFileMover(
    private val context: Context,
    private val timestampManager: ExifAndTimestampManager,
    private val scanner: StorageScanner
) {
    private val engine = VerifiedTransferEngine()

    suspend fun transferMediaItem(
        sessionId: String, item: MediaItemEntity, sourceRootUri: String,
        destinationRootUri: String, categorySubfolder: String, operationMode: OperationMode,
        transferMethod: TransferMethod, collisionPolicy: CollisionPolicy, timestampPolicy: TimestampPolicy,
        checkpoint: suspend (TransferCheckpoint) -> Unit = {}
    ): SingleTransferOutcome = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        var latestCheckpoint: TransferCheckpoint? = null
        try {
            require(operationMode == OperationMode.MOVE_FILES) { "Transfer blocked: Analysis Mode is active." }
            require(!scanner.isDestinationInsideSource(sourceRootUri, destinationRootUri)) {
                "Destination must be outside the source."
            }
            val segments = SafeRelativePath.segments(categorySubfolder)
            val localRoot = scanner.resolveLocalFile(destinationRootUri)
            val source: TransferDocument
            val directory: TransferDirectory
            if (localRoot != null && !item.filePath.isNullOrBlank()) {
                val localSource = File(item.filePath)
                val category = segments.fold(localRoot) { parent, child -> File(parent, child) }
                require(!category.canonicalFile.toPath().startsWith(localSource.parentFile!!.canonicalFile.toPath())
                    || !scanner.isDestinationInsideSource(sourceRootUri, category.canonicalPath)) {
                    "Actual category destination is inside source."
                }
                source = LocalTransferDocument(localSource)
                directory = LocalTransferDirectory(category, localRoot)
            } else {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(destinationRootUri))
                    ?: error("Invalid destination tree.")
                require(root.canWrite()) { "Destination write permission revoked." }
                var category = root
                for (segment in segments) {
                    category = category.findFile(segment)?.also {
                        require(it.isDirectory) { "Category path conflicts with a file." }
                    } ?: category.createDirectory(segment) ?: error("Cannot create category directory.")
                }
                val localSource = item.filePath?.let { File(it) }?.takeIf { it.exists() }
                source = if (localSource != null) SafDocument(DocumentFile.fromFile(localSource))
                    else SafDocument(DocumentFile.fromSingleUri(context, Uri.parse(item.uriString))
                        ?: error("Invalid source document."))
                directory = SafDirectory(category)
            }
            val receipt = engine.transfer(source, directory, item.fileName, item.mimeType, transferMethod,
                collisionPolicy, timestampPolicy, item.sha256Checksum, item.fileSizeBytes,
                item.originalLastModifiedMs) { state ->
                latestCheckpoint = state
                checkpoint(state)
            }
            if (receipt.skipped) {
                return@withContext buildSkippedCollisionOutcome(sessionId, item,
                    receipt.destination?.id.orEmpty(), transferMethod, receipt.details)
            }
            val destination = checkNotNull(receipt.destination)
            val timestamp = checkNotNull(receipt.timestamp)
            val status = when {
                transferMethod == TransferMethod.MOVE && receipt.sourceRetained -> TransferItemStatus.COMPLETED_SOURCE_RETAINED
                timestamp.preserved -> TransferItemStatus.COMPLETED_VERIFIED
                else -> TransferItemStatus.COMPLETED_TIMESTAMP_WARNING
            }
            buildSuccessOutcome(sessionId, item, destination.id, destination.name, receipt.actualMethod,
                checkNotNull(receipt.checksum), timestamp, receipt.collisionNote, status, receipt.details)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val state = latestCheckpoint
            val status = if (state?.destinationId != null) TransferItemStatus.RECOVERY_REQUIRED else TransferItemStatus.FAILED
            val failed = createFailureOutcome(sessionId, item, transferMethod,
                "Transfer interrupted; inspect recorded destination and source before retrying. " + error.message)
            failed.copy(status = status, destinationUriString = state?.destinationId,
                sha256Checksum = state?.checksum, timestampPreserved = state?.timestampPreserved ?: false,
                destinationLastModifiedMs = state?.destinationModifiedMs,
                logEntry = failed.logEntry.copy(status = status.name, destinationUri = state?.destinationId,
                    sha256Checksum = state?.checksum, timestampPreserved = state?.timestampPreserved ?: false,
                    destinationLastModifiedMs = state?.destinationModifiedMs))
        }
    }

    private inner class SafDocument(private val document: DocumentFile) : TransferDocument {
        override val id: String get() = document.uri.toString()
        override val name: String get() = document.name ?: error("Document has no filename.")
        override fun identityKey(): String? = runCatching {
            OriginalMediaAccess(context).asset(document.uri).use {
                val stat = Os.fstat(it.parcelFileDescriptor.fileDescriptor)
                if (OsConstants.S_ISREG(stat.st_mode)) stat.st_dev.toString() + ":" + stat.st_ino else null
            }
        }.getOrNull()
        override fun exists(): Boolean = document.exists()
        override fun size(): Long = document.length()
        override fun modifiedMs(): Long = timestampManager.resolveFilesystemLastModified(document.uri,
            document.uri.path?.takeIf { document.uri.scheme == "file" }, 0)
        override fun input(): InputStream = OriginalMediaAccess(context).input(document.uri)
        override fun output(): OutputStream = context.contentResolver.openOutputStream(document.uri, "wt")
            ?: error("Destination write permission revoked.")
        override fun sync(output: OutputStream) {
            // Some providers expose pipes, which cannot fsync. Readback is still mandatory.
            if (output is FileOutputStream) runCatching { output.fd.sync() }
        }
        override fun setTimestamp(originalMs: Long): TimestampVerificationResult =
            timestampManager.restoreAndVerifyTimestamp(null, document.uri, originalMs)
        override fun delete(): Boolean = document.delete()
    }

    private inner class SafDirectory(private val document: DocumentFile) : TransferDirectory {
        override fun confirmPublicationDurable(): Boolean = runCatching {
            context.contentResolver.openFileDescriptor(document.uri, "r")?.use {
                if (!OsConstants.S_ISDIR(Os.fstat(it.fileDescriptor).st_mode)) return@use false
                Os.fsync(it.fileDescriptor)
                true
            } ?: false
        }.getOrDefault(false)
        override fun find(name: String): TransferDocument? = document.findFile(name)?.let { SafDocument(it) }
        override fun createExclusive(name: String, mimeType: String): TransferDocument? {
            SafeRelativePath.fileName(name)
            if (document.findFile(name) != null) return null
            val created = document.createFile(mimeType, name)
                ?: error("Storage provider failed to create destination.")
            // DocumentsProvider createDocument creates a new document and may resolve a collision by renaming.
            return SafDocument(created)
        }
    }

    fun computeFileSha256(file: File): String = file.inputStream().use { computeStreamSha256(it) }
    fun computeStreamSha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun buildSuccessOutcome(
        sessionId: String,
        item: MediaItemEntity,
        destUriStr: String,
        resolvedName: String,
        method: TransferMethod,
        sha256: String,
        tsResult: TimestampVerificationResult,
        collisionNote: String?,
        status: TransferItemStatus,
        details: String
    ): SingleTransferOutcome {
        val log = TransferLogEntity(
            sessionId = sessionId,
            sourceUri = item.uriString,
            sourceFileName = item.fileName,
            destinationUri = destUriStr,
            categoryName = item.effectiveCategoryName,
            operationType = method.name,
            bytesTransferred = item.fileSizeBytes,
            sha256Checksum = sha256,
            originalLastModifiedMs = item.originalLastModifiedMs,
            destinationLastModifiedMs = tsResult.actualDestinationLastModifiedMs,
            timestampPreserved = tsResult.preserved,
            collisionResolution = collisionNote,
            status = status.name,
            detailsMessage = details,
            executedAtMs = System.currentTimeMillis()
        )
        return SingleTransferOutcome(
            itemId = item.id,
            status = status,
            destinationUriString = destUriStr,
            resolvedFileName = resolvedName,
            sha256Checksum = sha256,
            timestampPreserved = tsResult.preserved,
            destinationLastModifiedMs = tsResult.actualDestinationLastModifiedMs,
            collisionResolutionNote = collisionNote,
            detailsMessage = details,
            logEntry = log
        )
    }

    private fun buildSkippedCollisionOutcome(
        sessionId: String,
        item: MediaItemEntity,
        existingDestUri: String,
        method: TransferMethod,
        reason: String
    ): SingleTransferOutcome {
        val log = TransferLogEntity(
            sessionId = sessionId,
            sourceUri = item.uriString,
            sourceFileName = item.fileName,
            destinationUri = existingDestUri,
            categoryName = item.effectiveCategoryName,
            operationType = method.name,
            bytesTransferred = 0L,
            sha256Checksum = null,
            originalLastModifiedMs = item.originalLastModifiedMs,
            destinationLastModifiedMs = null,
            timestampPreserved = false,
            collisionResolution = "SKIPPED",
            status = TransferItemStatus.SKIPPED_COLLISION.name,
            detailsMessage = reason,
            executedAtMs = System.currentTimeMillis()
        )
        return SingleTransferOutcome(
            itemId = item.id,
            status = TransferItemStatus.SKIPPED_COLLISION,
            destinationUriString = existingDestUri,
            resolvedFileName = item.fileName,
            sha256Checksum = null,
            timestampPreserved = false,
            destinationLastModifiedMs = null,
            collisionResolutionNote = reason,
            detailsMessage = reason,
            logEntry = log
        )
    }

    private fun createFailureOutcome(
        sessionId: String,
        item: MediaItemEntity,
        method: TransferMethod,
        message: String
    ): SingleTransferOutcome {
        val log = TransferLogEntity(
            sessionId = sessionId,
            sourceUri = item.uriString,
            sourceFileName = item.fileName,
            destinationUri = null,
            categoryName = item.effectiveCategoryName,
            operationType = method.name,
            bytesTransferred = 0L,
            sha256Checksum = null,
            originalLastModifiedMs = item.originalLastModifiedMs,
            destinationLastModifiedMs = null,
            timestampPreserved = false,
            collisionResolution = null,
            status = TransferItemStatus.FAILED.name,
            detailsMessage = message,
            executedAtMs = System.currentTimeMillis()
        )
        return SingleTransferOutcome(
            itemId = item.id,
            status = TransferItemStatus.FAILED,
            destinationUriString = null,
            resolvedFileName = item.fileName,
            sha256Checksum = null,
            timestampPreserved = false,
            destinationLastModifiedMs = null,
            collisionResolutionNote = null,
            detailsMessage = message,
            logEntry = log
        )
    }
}
