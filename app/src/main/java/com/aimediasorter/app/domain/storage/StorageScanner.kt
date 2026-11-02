package com.aimediasorter.app.domain.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.aimediasorter.app.data.model.DefaultCategories
import com.aimediasorter.app.data.model.MediaTypeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

data class ScannedMediaCandidate(
    val uri: Uri, val filePath: String?, val fileName: String, val extension: String,
    val isVideo: Boolean, val mimeType: String, val fileSizeBytes: Long,
    val lastModifiedMs: Long, val quickFingerprint: String
)

class StorageScanner(private val context: Context) {
    fun resolveLocalFile(raw: String): File? {
        if (raw.startsWith("/")) return File(raw)
        val uri = Uri.parse(raw)
        return if (uri.scheme == "file") uri.path?.let { File(it) } else null
    }
    fun isDestinationInsideSource(sourceUriStr: String, destinationUriStr: String): Boolean {
        if (sourceUriStr.isBlank() || destinationUriStr.isBlank()) return false
        val sourceFile = resolveLocalFile(sourceUriStr)
        val destinationFile = resolveLocalFile(destinationUriStr)
        if (sourceFile != null && destinationFile != null) {
            return destinationFile.canonicalFile.toPath().startsWith(sourceFile.canonicalFile.toPath())
        }
        val source = Uri.parse(sourceUriStr)
        val destination = Uri.parse(destinationUriStr)
        if (source.scheme != "content" || destination.scheme != "content") return false
        if (source.authority != destination.authority) return false
        val sourceId = runCatching { DocumentsContract.getTreeDocumentId(source) }.getOrNull() ?: return true
        val destinationId = runCatching { DocumentsContract.getTreeDocumentId(destination) }.getOrNull() ?: return true
        if (sourceId == destinationId) return true
        if (source.authority == "com.android.externalstorage.documents") {
            val sourceParts = sourceId.split(':', limit = 2)
            val destinationParts = destinationId.split(':', limit = 2)
            if (sourceParts.size != 2 || destinationParts.size != 2) return true
            if (sourceParts[0] != destinationParts[0]) return false
            val sourcePath = sourceParts[1].trim('/')
            val destinationPath = destinationParts[1].trim('/')
            return sourcePath.isEmpty() || destinationPath == sourcePath ||
                destinationPath.startsWith(sourcePath + "/")
        }
        return runCatching {
            DocumentsContract.isChildDocument(context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(source, sourceId),
                DocumentsContract.buildDocumentUriUsingTree(destination, destinationId))
        }.getOrDefault(true)
    }
    fun normalizeUriOrPath(raw: String): String =
        resolveLocalFile(raw)?.canonicalPath ?: Uri.parse(raw).normalizeScheme().toString().trimEnd('/')

    suspend fun scanDirectory(
        sourceUriStr: String, destinationUriStr: String, includeSubfolders: Boolean,
        mediaTypeFilter: MediaTypeFilter, enabledExtensions: Set<String>,
        onProgress: suspend (Int) -> Unit = {}
    ): List<ScannedMediaCandidate> =
        scanCandidates(sourceUriStr, destinationUriStr, includeSubfolders, mediaTypeFilter, enabledExtensions, onProgress).toList()

    fun scanCandidates(
        sourceUriStr: String, destinationUriStr: String, includeSubfolders: Boolean,
        mediaTypeFilter: MediaTypeFilter, enabledExtensions: Set<String>,
        onProgress: suspend (Int) -> Unit = {}
    ): Flow<ScannedMediaCandidate> = flow {
        require(sourceUriStr.isNotBlank()) { "Select a source directory." }
        require(!isDestinationInsideSource(sourceUriStr, destinationUriStr)) { "Destination must be outside source." }
        val extensions = enabledExtensions.map { it.lowercase(Locale.ROOT) }.toSet()
        var discovered = 0
        val localRoot = resolveLocalFile(sourceUriStr)
        if (localRoot != null) {
            require(localRoot.isDirectory && localRoot.canRead()) { "Source directory missing or unreadable." }
            val pending = ArrayDeque<File>()
            val visited = hashSetOf<String>()
            pending.add(localRoot.canonicalFile)
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val directory = pending.removeLast()
                if (!visited.add(directory.canonicalPath)) continue
                val children = directory.listFiles() ?: throw IOException("Directory access failed: " + directory.path)
                for (child in children) {
                    currentCoroutineContext().ensureActive()
                    if (Files.isSymbolicLink(child.toPath()) || child.name.startsWith(".aimediasorter-")) continue
                    if (child.isDirectory) {
                        if (includeSubfolders) pending.add(child)
                    } else if (child.isFile) {
                        val extension = child.extension.lowercase(Locale.ROOT)
                        if (!included(extension, mediaTypeFilter, extensions)) continue
                        val video = extension in DefaultCategories.SUPPORTED_VIDEO_EXTENSIONS
                        val uri = Uri.fromFile(child)
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                            ?: if (video) "video/mp4" else "image/jpeg"
                        onProgress(++discovered)
                        emit(ScannedMediaCandidate(uri, child.canonicalPath, child.name, extension, video,
                            mime, child.length(), child.lastModified(),
                            uri.toString() + ":" + child.length() + ":" + child.lastModified()))
                    }
                }
            }
        } else {
            val tree = Uri.parse(sourceUriStr)
            val root = DocumentFile.fromTreeUri(context, tree)
                ?.takeIf { it.isDirectory && it.canRead() } ?: error("Source SAF access was revoked.")
            val pending = ArrayDeque<String>()
            pending.add(DocumentsContract.getDocumentId(root.uri))
            val visited = hashSetOf<String>()
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val directoryId = pending.removeLast()
                if (!visited.add(directoryId)) continue
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directoryId)
                val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
                    ?: throw IOException("Provider did not return a directory listing.")
                cursor.use {
                    val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                    val timeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    while (it.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val id = it.getString(idColumn)
                        val name = it.getString(nameColumn) ?: continue
                        if (name.startsWith(".aimediasorter-")) continue
                        val mime = it.getString(mimeColumn).orEmpty()
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (includeSubfolders) pending.add(id)
                            continue
                        }
                        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        if (!included(extension, mediaTypeFilter, extensions)) continue
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        val size = if (it.isNull(sizeColumn)) 0L else it.getLong(sizeColumn)
                        val modified = if (it.isNull(timeColumn)) 0L else it.getLong(timeColumn)
                        onProgress(++discovered)
                        emit(ScannedMediaCandidate(uri, null, name, extension,
                            extension in DefaultCategories.SUPPORTED_VIDEO_EXTENSIONS,
                            mime.ifBlank { if (extension in DefaultCategories.SUPPORTED_VIDEO_EXTENSIONS) "video/mp4" else "image/jpeg" },
                            size, modified, uri.toString() + ":" + size + ":" + modified))
                    }
                }
            }
        }
    }.buffer(32).flowOn(Dispatchers.IO)

    private fun included(extension: String, filter: MediaTypeFilter, enabled: Set<String>): Boolean {
        if (extension !in enabled) return false
        return when (filter) {
            MediaTypeFilter.ALL -> extension in DefaultCategories.ALL_SUPPORTED_EXTENSIONS
            MediaTypeFilter.PHOTOS_ONLY -> extension in DefaultCategories.SUPPORTED_PHOTO_EXTENSIONS
            MediaTypeFilter.VIDEOS_ONLY -> extension in DefaultCategories.SUPPORTED_VIDEO_EXTENSIONS
        }
    }
}
