package com.example.timescapedemo

data class VideoCardData(
    val sourceUri: String,
    val fileName: String,
    val durationMs: Long,
    val modifiedAt: Long,
    val width: Int,
    val height: Int,
    val aspectRatio: Float,
    val fileSizeBytes: Long,
    val folderLabel: String?,
    val coverImagePath: String?,
    val coverTimestampMs: Long?,
    val sourceFingerprint: String,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val isPinned: Boolean = false,
    val watchProgressMs: Long = 0L
)
