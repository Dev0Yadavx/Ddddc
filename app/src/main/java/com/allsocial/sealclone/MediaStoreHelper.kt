package com.allsocial.sealclone

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaStoreHelper {
    private const val TAG = "MediaStoreHelper"

    /**
     * Trigger a scan on the app's download directory to make sure files are indexed by MediaStore.
     */
    fun scanDownloadDirectory(context: Context) {
        try {
            val dir = DownloaderBridge.getDownloadDir(context)
            val files = dir.listFiles { f ->
                f.isFile && (f.name.endsWith(".mp4", ignoreCase = true) ||
                        f.name.endsWith(".mkv", ignoreCase = true) ||
                        f.name.endsWith(".webm", ignoreCase = true) ||
                        f.name.endsWith(".mp3", ignoreCase = true) ||
                        f.name.endsWith(".m4a", ignoreCase = true) ||
                        f.name.endsWith(".opus", ignoreCase = true) ||
                        f.name.endsWith(".ogg", ignoreCase = true))
            } ?: emptyArray()

            if (files.isNotEmpty()) {
                val paths = files.map { it.absolutePath }.toTypedArray()
                MediaScannerConnection.scanFile(context, paths, null) { path, uri ->
                    Log.d(TAG, "Scanned $path -> $uri")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning download dir", e)
        }
    }

    /**
     * Query all finished media files from Android MediaStore (Videos, Audios, and Downloads),
     * and merge with local app directory media so nothing is missed.
     */
    suspend fun queryAllMedia(context: Context): List<DownloadedMedia> = withContext(Dispatchers.IO) {
        // First trigger a scan to catch any freshly finished files
        scanDownloadDirectory(context)

        val items = mutableListOf<DownloadedMedia>()
        val seenPaths = mutableSetOf<String>()
        val seenUris = mutableSetOf<Uri>()

        // 1. Query Video MediaStore
        try {
            val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.DATA
            )
            context.contentResolver.query(
                videoUri,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(videoUri, id)
                    val displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: "Video_$id" else "Video_$id"
                    val title = if (titleCol >= 0) cursor.getString(titleCol) ?: displayName else displayName
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val duration = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                    val dateModified = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "video/mp4" else "video/mp4"
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""

                    if (dataPath.isNotEmpty()) seenPaths.add(dataPath)
                    seenUris.add(uri)

                    items.add(
                        DownloadedMedia(
                            id = id,
                            uri = uri,
                            title = title.ifBlank { displayName },
                            displayName = displayName,
                            sizeBytes = size,
                            durationMs = duration,
                            dateModified = dateModified,
                            mimeType = mimeType,
                            isVideo = true,
                            filePath = dataPath
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying Video MediaStore", e)
        }

        // 2. Query Audio MediaStore
        try {
            val audioUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DATA
            )
            context.contentResolver.query(
                audioUri,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val dateCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(audioUri, id)
                    val displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: "Audio_$id" else "Audio_$id"
                    val title = if (titleCol >= 0) cursor.getString(titleCol) ?: displayName else displayName
                    val artist = if (artistCol >= 0) cursor.getString(artistCol) ?: "" else ""
                    val album = if (albumCol >= 0) cursor.getString(albumCol) ?: "" else ""
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val duration = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                    val dateModified = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "audio/mpeg" else "audio/mpeg"
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""

                    if (dataPath.isNotEmpty()) seenPaths.add(dataPath)
                    seenUris.add(uri)

                    items.add(
                        DownloadedMedia(
                            id = id,
                            uri = uri,
                            title = title.ifBlank { displayName },
                            displayName = displayName,
                            artist = if (artist.contains("<unknown>", ignoreCase = true)) "" else artist,
                            album = if (album.contains("<unknown>", ignoreCase = true)) "" else album,
                            sizeBytes = size,
                            durationMs = duration,
                            dateModified = dateModified,
                            mimeType = mimeType,
                            isVideo = false,
                            filePath = dataPath
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying Audio MediaStore", e)
        }

        // 3. Query Downloads MediaStore (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val dlUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.DATE_MODIFIED,
                    MediaStore.Downloads.MIME_TYPE,
                    MediaStore.Downloads.DATA
                )
                context.contentResolver.query(
                    dlUri,
                    projection,
                    null,
                    null,
                    "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Downloads.SIZE)
                    val dateCol = cursor.getColumnIndex(MediaStore.Downloads.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndex(MediaStore.Downloads.MIME_TYPE)
                    val dataCol = cursor.getColumnIndex(MediaStore.Downloads.DATA)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(dlUri, id)
                        val displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                        val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                        val dataPath = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""

                        val isVideo = mimeType.startsWith("video", ignoreCase = true) ||
                                displayName.endsWith(".mp4", ignoreCase = true) ||
                                displayName.endsWith(".mkv", ignoreCase = true) ||
                                displayName.endsWith(".webm", ignoreCase = true)
                        val isAudio = mimeType.startsWith("audio", ignoreCase = true) ||
                                displayName.endsWith(".mp3", ignoreCase = true) ||
                                displayName.endsWith(".m4a", ignoreCase = true) ||
                                displayName.endsWith(".opus", ignoreCase = true)

                        if ((isVideo || isAudio) && (dataPath.isEmpty() || !seenPaths.contains(dataPath)) && !seenUris.contains(uri)) {
                            val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                            val dateModified = if (dateCol >= 0) cursor.getLong(dateCol) else 0L

                            if (dataPath.isNotEmpty()) seenPaths.add(dataPath)
                            seenUris.add(uri)

                            items.add(
                                DownloadedMedia(
                                    id = id,
                                    uri = uri,
                                    title = displayName.substringBeforeLast('.'),
                                    displayName = displayName,
                                    sizeBytes = size,
                                    durationMs = 0L,
                                    dateModified = dateModified,
                                    mimeType = if (mimeType.isNotBlank()) mimeType else (if (isVideo) "video/mp4" else "audio/mpeg"),
                                    isVideo = isVideo,
                                    filePath = dataPath
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying Downloads MediaStore", e)
            }
        }

        // 4. Merge any finished media from the app's scoped downloads directory that MediaScanner hasn't finished indexing
        try {
            val downloadDir = DownloaderBridge.getDownloadDir(context)
            val localFiles = downloadDir.listFiles { file ->
                file.isFile && (
                        file.name.endsWith(".mp4", ignoreCase = true) ||
                        file.name.endsWith(".mkv", ignoreCase = true) ||
                        file.name.endsWith(".webm", ignoreCase = true) ||
                        file.name.endsWith(".mp3", ignoreCase = true) ||
                        file.name.endsWith(".m4a", ignoreCase = true) ||
                        file.name.endsWith(".opus", ignoreCase = true) ||
                        file.name.endsWith(".ogg", ignoreCase = true)
                )
            } ?: emptyArray()

            for (file in localFiles) {
                if (!seenPaths.contains(file.absolutePath)) {
                    val isVideo = file.name.endsWith(".mp4", ignoreCase = true) ||
                            file.name.endsWith(".mkv", ignoreCase = true) ||
                            file.name.endsWith(".webm", ignoreCase = true)
                    val contentUri = try {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            file
                        )
                    } catch (_: Exception) {
                        Uri.fromFile(file)
                    }

                    items.add(
                        DownloadedMedia(
                            id = file.hashCode().toLong(),
                            uri = contentUri,
                            title = file.nameWithoutExtension,
                            displayName = file.name,
                            sizeBytes = file.length(),
                            durationMs = 0L,
                            dateModified = file.lastModified() / 1000L,
                            mimeType = if (isVideo) "video/mp4" else "audio/mpeg",
                            isVideo = isVideo,
                            filePath = file.absolutePath
                        )
                    )
                    seenPaths.add(file.absolutePath)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning local downloads fallback", e)
        }

        // Sort primarily by date modified descending (newest finished downloads first)
        items.sortedByDescending { it.dateModified }
    }

    /**
     * Delete a media item from MediaStore and filesystem.
     */
    suspend fun deleteMedia(context: Context, item: DownloadedMedia): Boolean = withContext(Dispatchers.IO) {
        var deleted = false
        try {
            // Delete via content resolver
            val rows = context.contentResolver.delete(item.uri, null, null)
            if (rows > 0) deleted = true
        } catch (e: Exception) {
            Log.e(TAG, "ContentResolver delete failed", e)
        }

        // Also attempt direct file deletion if filePath is set
        if (item.filePath.isNotEmpty()) {
            try {
                val f = File(item.filePath)
                if (f.exists() && f.delete()) {
                    deleted = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "File delete failed", e)
            }
        }

        deleted
    }
}
