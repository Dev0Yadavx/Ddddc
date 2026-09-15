package com.example

import com.allsocial.sealclone.ActiveDownloadTask
import com.allsocial.sealclone.AppTab
import com.allsocial.sealclone.DownloadForegroundService
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testAppTabIncludesDownloads() {
        val tabs = AppTab.values()
        assertTrue(tabs.any { it == AppTab.DOWNLOADS })
        assertEquals("Downloads", AppTab.DOWNLOADS.label)
    }

    @Test
    fun testDownloadForegroundServiceConstants() {
        assertEquals("seal_download_channel", DownloadForegroundService.CHANNEL_ID)
        assertEquals("Ongoing Downloads", DownloadForegroundService.CHANNEL_NAME)
        assertEquals(1001, DownloadForegroundService.NOTIFICATION_ID)
        assertEquals("com.allsocial.sealclone.action.START_DOWNLOAD", DownloadForegroundService.ACTION_START_DOWNLOAD)
        assertEquals("com.allsocial.sealclone.action.CANCEL_DOWNLOAD", DownloadForegroundService.ACTION_CANCEL_DOWNLOAD)
    }

    @Test
    fun testActiveDownloadTask() {
        val task = ActiveDownloadTask(
            id = "test_123",
            title = "Awesome Song",
            progress = 65.5f,
            speed = "2.4 MiB/s • ETA 00:15"
        )
        assertEquals("test_123", task.id)
        assertEquals("Awesome Song", task.title)
        assertEquals(65.5f, task.progress, 0.01f)
        assertEquals("2.4 MiB/s • ETA 00:15", task.speed)
    }

    @Test
    fun testFixUrl() {
        // Direct link with tracking query params
        val urlWithTracking = "https://youtu.be/dQw4w9WgXcQ?si=abcdef123456"
        assertEquals("https://youtu.be/dQw4w9WgXcQ", com.allsocial.sealclone.DownloaderEngine.fixUrl(urlWithTracking))

        // Raw 11-char ID
        val rawId = "dQw4w9WgXcQ"
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", com.allsocial.sealclone.DownloaderEngine.fixUrl(rawId))

        // Keyword query
        val keyword = "lofi chill beats"
        assertEquals("ytsearch10:lofi chill beats", com.allsocial.sealclone.DownloaderEngine.fixUrl(keyword))
    }
}


