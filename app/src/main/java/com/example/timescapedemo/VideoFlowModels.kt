package com.example.timescapedemo

import android.net.Uri

/**
 * Persisted source configuration for the dedicated Video Flow page.
 */
data class VideoFlowSource(
    val treeUri: Uri,
    val displayName: String,
    val includeSubfolders: Boolean,
    val refreshOnLaunch: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class VideoBrowseMode {
    RECENT,
    RANDOM,
    FAVORITES,
    CONTINUE_WATCHING,
    HIDDEN,
    FOLDER,
    DATE_GROUPED
}

data class VideoFileMetadata(
    val durationMs: Long?,
    val modifiedAt: Long,
    val width: Int?,
    val height: Int?,
    val aspectRatio: Float?,
    val sizeBytes: Long,
    val mimeType: String?,
    val extension: String?
)

data class VideoSourceVideo(
    val stableId: String,
    val documentUri: Uri,
    val relativePath: String,
    val title: String,
    val folderLabel: String,
    val metadata: VideoFileMetadata
)

data class VideoCoverState(
    val timestampMs: Long,
    val generatedAt: Long = System.currentTimeMillis()
)

data class VideoInteractionState(
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val isPinned: Boolean = false,
    val watchProgress: Float? = null,
    val watchPositionMs: Long? = null,
    val lastPlayedAt: Long? = null
)

data class VideoCardState(
    val stableId: String,
    val cover: VideoCoverState?,
    val interaction: VideoInteractionState = VideoInteractionState(),
    val metadataFingerprint: String,
    val lastSeenAt: Long = System.currentTimeMillis()
)

data class VideoReconcileResult(
    val added: List<VideoSourceVideo>,
    val removed: List<String>,
    val updated: List<VideoSourceVideo>,
    val unchanged: List<VideoSourceVideo>
)
