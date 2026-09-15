package com.allsocial.sealclone

import android.net.Uri

/**
 * Data model for a finished media file indexed via Android MediaStore.
 */
data class DownloadedMedia(
    val id: Long,
    val uri: Uri,
    val title: String,
    val displayName: String,
    val artist: String = "",
    val album: String = "",
    val sizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val dateModified: Long = 0L,
    val mimeType: String = "",
    val isVideo: Boolean = true,
    val filePath: String = "",
    val thumbnailUri: Uri? = null
) {
    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return "0 B"
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1.0) "%.1f MB".format(mb) else "%.0f KB".format(sizeBytes / 1024.0)
        }

    val formattedDuration: String
        get() {
            if (durationMs <= 0) return "--:--"
            val totalSec = durationMs / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return "%02d:%02d".format(min, sec)
        }

    val extension: String
        get() = displayName.substringAfterLast('.', if (isVideo) "mp4" else "mp3").uppercase()

    val formattedDate: String
        get() {
            if (dateModified <= 0) return "Recently"
            return try {
                val sdf = java.text.SimpleDateFormat("MMM dd, yyyy • HH:mm", java.util.Locale.getDefault())
                sdf.format(java.util.Date(dateModified * 1000L))
            } catch (_: Exception) {
                "Recently"
            }
        }
}
