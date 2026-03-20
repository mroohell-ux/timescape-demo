package com.example.timescapedemo

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest
import java.util.Locale

/**
 * Indexes videos from a single user-selected SAF tree URI.
 *
 * The scanner does not copy source files. It only indexes current metadata and URIs.
 */
class VideoFlowScanner(private val context: Context) {

    fun scan(source: VideoFlowSource): List<VideoSourceVideo> {
        val root = DocumentFile.fromTreeUri(context, source.treeUri) ?: return emptyList()
        if (!root.exists() || !root.isDirectory) return emptyList()

        val accumulator = mutableListOf<VideoSourceVideo>()
        val rootDocId = DocumentsContract.getTreeDocumentId(source.treeUri)
        walkDirectory(
            root = root,
            includeSubfolders = source.includeSubfolders,
            rootName = root.name ?: source.displayName,
            rootDocId = rootDocId,
            out = accumulator
        )
        return accumulator
    }

    fun reconcile(
        scanned: List<VideoSourceVideo>,
        previous: Map<String, VideoCardState>
    ): VideoReconcileResult = reconcileScannedVideos(scanned, previous)

    private fun walkDirectory(
        root: DocumentFile,
        includeSubfolders: Boolean,
        rootName: String,
        rootDocId: String,
        out: MutableList<VideoSourceVideo>
    ) {
        root.listFiles().forEach { file ->
            when {
                file.isDirectory && includeSubfolders -> {
                    walkDirectory(file, includeSubfolders, rootName, rootDocId, out)
                }
                file.isFile && isLikelyVideo(file) -> {
                    out += toVideo(file, rootName, rootDocId)
                }
            }
        }
    }

    private fun toVideo(file: DocumentFile, rootName: String, rootDocId: String): VideoSourceVideo {
        val uri = file.uri
        val name = file.name ?: "video"
        val mimeType = file.type
        val extension = extensionOf(name)
        val durationMs = VideoMetadataReader.readDurationMs(context, uri)
        val dimensions = VideoMetadataReader.readDimensions(context, uri)
        val modifiedAt = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        val sizeBytes = file.length().coerceAtLeast(0L)

        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val relativePath = documentId
            ?.removePrefix("$rootDocId/")
            ?.takeIf { it.isNotBlank() }
            ?: name

        val parentFolder = relativePath.substringBeforeLast('/', missingDelimiterValue = rootName)
            .ifBlank { rootName }

        val aspectRatio = dimensions?.let { (w, h) ->
            if (h == 0) null else w.toFloat() / h.toFloat()
        }

        return VideoSourceVideo(
            stableId = sha1("${uri}#${documentId ?: relativePath}"),
            documentUri = uri,
            relativePath = relativePath,
            title = name.substringBeforeLast('.'),
            folderLabel = parentFolder,
            metadata = VideoFileMetadata(
                durationMs = durationMs,
                modifiedAt = modifiedAt,
                width = dimensions?.first,
                height = dimensions?.second,
                aspectRatio = aspectRatio,
                sizeBytes = sizeBytes,
                mimeType = mimeType,
                extension = extension
            )
        )
    }

    private fun isLikelyVideo(file: DocumentFile): Boolean {
        val type = file.type?.lowercase(Locale.US)
        if (type != null && type.startsWith("video/")) return true
        return SUPPORTED_VIDEO_EXTENSIONS.contains(extensionOf(file.name))
    }

    companion object {
        fun reconcileScannedVideos(
            scanned: List<VideoSourceVideo>,
            previous: Map<String, VideoCardState>
        ): VideoReconcileResult {
            val byId = scanned.associateBy { it.stableId }
            val added = mutableListOf<VideoSourceVideo>()
            val updated = mutableListOf<VideoSourceVideo>()
            val unchanged = mutableListOf<VideoSourceVideo>()

            scanned.forEach { video ->
                val old = previous[video.stableId]
                if (old == null) {
                    added += video
                } else {
                    val currentFingerprint = buildMetadataFingerprint(video)
                    if (currentFingerprint == old.metadataFingerprint) {
                        unchanged += video
                    } else {
                        updated += video
                    }
                }
            }

            val removed = previous.keys.filterNot(byId::containsKey)
            return VideoReconcileResult(
                added = added,
                removed = removed,
                updated = updated,
                unchanged = unchanged
            )
        }

        fun buildMetadataFingerprint(video: VideoSourceVideo): String {
            val m = video.metadata
            val pieces = listOf(
                video.relativePath,
                m.durationMs?.toString().orEmpty(),
                m.modifiedAt.toString(),
                m.width?.toString().orEmpty(),
                m.height?.toString().orEmpty(),
                m.aspectRatio?.toString().orEmpty(),
                m.sizeBytes.toString(),
                m.mimeType.orEmpty(),
                m.extension.orEmpty()
            )
            return sha1(pieces.joinToString(separator = "|"))
        }

        val SUPPORTED_VIDEO_EXTENSIONS: Set<String> = setOf(
            "mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "m2ts", "ts", "wmv", "flv"
        )

        fun extensionOf(name: String?): String? {
            if (name.isNullOrBlank()) return null
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.lastIndex) return null
            return name.substring(dot + 1).lowercase(Locale.US)
        }

        private fun sha1(value: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            return digest.joinToString(separator = "") { "%02x".format(it) }
        }
    }
}
