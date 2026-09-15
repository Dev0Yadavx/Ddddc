package com.allsocial.sealclone

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ================= DATA MODELS =================
data class SearchItem(
    val id: String,
    val title: String,
    val uploader: String,
    val duration: String,
    val thumbnail: String,
    val url: String
)

data class ActiveDownloadTask(
    val id: String,
    val title: String,
    val progress: Float,
    val speed: String = ""
)

data class DownloadedRecord(
    val title: String,
    val filePath: String,
    val thumbnail: String,
    val quality: String,
    val ext: String,
    val fileSize: String
)

enum class AppTab(val label: String, val activeIcon: ImageVector, val inactiveIcon: ImageVector) {
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home),
    TASKS("Tasks", Icons.Filled.Downloading, Icons.Outlined.Downloading),
    DOWNLOADS("Downloads", Icons.Filled.DownloadDone, Icons.Outlined.DownloadDone),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

// ================= VIEWMODEL =================
class SealViewModel @JvmOverloads constructor(application: Application? = null) : ViewModel() {
    private val prefs = application?.getSharedPreferences("seal_app_prefs", Context.MODE_PRIVATE)

    private val _isDarkMode = MutableStateFlow(prefs?.getBoolean("pref_dark_mode", true) ?: true)
    val isDarkMode = _isDarkMode.asStateFlow()

    fun setDarkMode(enabled: Boolean) {
        _isDarkMode.value = enabled
        prefs?.edit()?.putBoolean("pref_dark_mode", enabled)?.apply()
    }

    fun toggleDarkMode() {
        setDarkMode(!_isDarkMode.value)
    }

    private val _activeTasks = MutableStateFlow<Map<String, ActiveDownloadTask>>(emptyMap())
    val activeTasks = _activeTasks.asStateFlow()

    private val _downloadedHistory = MutableStateFlow<List<DownloadedRecord>>(emptyList())
    val downloadedHistory = _downloadedHistory.asStateFlow()

    private val _showBottomSheet = MutableStateFlow(false)
    val showBottomSheet = _showBottomSheet.asStateFlow()

    private val _selectedItemForSheet = MutableStateFlow<SearchItem?>(null)
    val selectedItemForSheet = _selectedItemForSheet.asStateFlow()

    init {
        viewModelScope.launch {
            DownloadForegroundService.activeTasksFlow.collect { tasks ->
                _activeTasks.value = tasks
            }
        }
        viewModelScope.launch {
            DownloadForegroundService.downloadEvents.collect { event ->
                if (event.isSuccess && event.record != null) {
                    addCompletedRecord(event.record)
                }
            }
        }
    }

    fun cancelDownload(context: Context, taskId: String) {
        DownloadForegroundService.cancelDownload(context, taskId)
    }

    fun toggleBottomSheet() {
        _showBottomSheet.value = !_showBottomSheet.value
    }

    fun setShowBottomSheet(show: Boolean) {
        _showBottomSheet.value = show
    }

    fun openBottomSheet(item: SearchItem) {
        _selectedItemForSheet.value = item
        _showBottomSheet.value = true
    }

    fun closeBottomSheet() {
        _showBottomSheet.value = false
    }

    fun updateProgress(id: String, title: String, progress: Float, speed: String) {
        val current = _activeTasks.value.toMutableMap()
        if (progress >= 100f) {
            current.remove(id)
        } else {
            current[id] = ActiveDownloadTask(id, title, progress, speed)
        }
        _activeTasks.value = current
    }

    fun addCompletedRecord(record: DownloadedRecord) {
        _downloadedHistory.value = listOf(record) + _downloadedHistory.value
    }

    fun removeRecord(record: DownloadedRecord) {
        val file = File(record.filePath)
        if (file.exists()) file.delete()
        _downloadedHistory.value = _downloadedHistory.value.filter { it != record }
    }
}

// VideoInfo extension properties for DownloaderEngine compatibility
private val videoInfoEntriesMap = java.util.WeakHashMap<VideoInfo, List<VideoInfo>>()

var VideoInfo.entries: List<VideoInfo>
    get() = videoInfoEntriesMap[this] ?: emptyList()
    set(value) {
        if (value.isNotEmpty()) videoInfoEntriesMap[this] = value else videoInfoEntriesMap.remove(this)
    }

val VideoInfo.channel: String?
    get() = uploader

val VideoInfo.durationString: String?
    get() {
        val d = duration
        return if (d > 0) {
            val m = d / 60
            val s = d % 60
            "%02d:%02d".format(m, s)
        } else {
            "00:00"
        }
    }

// ================= 1. DOWNLOADER ENGINE =================
object DownloaderEngine {
    // 1. Bulletproof URL Repair
    fun fixUrl(input: String): String {
        val trimmed = input.trim()

        // Full Link handling
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed.substringBefore("?si=").substringBefore("&si=")
        }

        // Agar cut-off URL ya random string me 11-character video ID maujood ho
        val idRegex = Regex("""([a-zA-Z0-9_-]{11})""")
        val match = idRegex.find(trimmed)
        if (match != null) {
            return "https://www.youtube.com/watch?v=${match.value}"
        }

        // Agar user ne normal song/keyword search kiya ho
        return "ytsearch10:$trimmed"
    }

    // 2. Direct Link + Search Query Handler
    suspend fun search(query: String): List<SearchItem> = withContext(Dispatchers.IO) {
        val resolvedTarget = fixUrl(query)

        val request = YoutubeDLRequest(resolvedTarget).apply {
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--ignore-no-formats-error")
            // Android, iOS, Web clients to bypass PO-Token bot blocks
            addOption("--extractor-args", "youtube:player_client=android,ios,web")
            addOption("--user-agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            
            // Sirf tab flat-playlist lagao jab keyword search ho, direct URL me nahi
            if (resolvedTarget.startsWith("ytsearch")) {
                addOption("--flat-playlist")
            }
        }

        try {
            val res: VideoInfo = if (resolvedTarget.startsWith("ytsearch")) {
                val response = YoutubeDL.getInstance().execute(request)
                val out = response.out ?: ""
                val parentInfo = VideoInfo()
                if (out.isNotBlank()) {
                    val json = org.json.JSONObject(out)
                    val entriesJson = json.optJSONArray("entries")
                    if (entriesJson != null && entriesJson.length() > 0) {
                        val parsedEntries = mutableListOf<VideoInfo>()
                        for (i in 0 until minOf(10, entriesJson.length())) {
                            val itemJson = entriesJson.getJSONObject(i)
                            val itemInfo = VideoInfo()
                            fun setField(name: String, value: Any?) {
                                try {
                                    val f = VideoInfo::class.java.getDeclaredField(name)
                                    f.isAccessible = true
                                    f.set(itemInfo, value)
                                } catch (_: Exception) {}
                            }
                            val id = itemJson.optString("id", "")
                            val title = itemJson.optString("title", "Unknown Title")
                            val uploader = if (itemJson.has("uploader")) itemJson.optString("uploader") else itemJson.optString("channel", "Artist")
                            val duration = itemJson.optInt("duration", 0)
                            val thumb = if (itemJson.has("thumbnail")) itemJson.optString("thumbnail") else if (id.isNotEmpty()) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else ""
                            val url = if (id.isNotEmpty()) "https://www.youtube.com/watch?v=$id" else itemJson.optString("url", "")
                            setField("id", id)
                            setField("title", title)
                            setField("uploader", uploader)
                            setField("duration", duration)
                            setField("thumbnail", thumb)
                            setField("url", url)
                            parsedEntries.add(itemInfo)
                        }
                        parentInfo.entries = parsedEntries
                    }
                }
                parentInfo
            } else {
                YoutubeDL.getInstance().getInfo(request)
            }
            val list = mutableListOf<SearchItem>()

            // Case A: Agar search query se multiple videos aayi hon
            if (!res.entries.isNullOrEmpty()) {
                res.entries.take(10).forEach { e ->
                    val vId = e.id ?: ""
                    list.add(
                        SearchItem(
                            id = vId,
                            title = e.title ?: "Unknown Title",
                            uploader = e.uploader ?: e.channel ?: "Artist",
                            duration = e.durationString ?: "00:00",
                            thumbnail = e.thumbnail ?: "https://i.ytimg.com/vi/$vId/hqdefault.jpg",
                            url = if (vId.isNotEmpty()) "https://www.youtube.com/watch?v=$vId" else (e.url ?: "")
                        )
                    )
                }
            } 
            // Case B: Agar direct URL paste ki ho (single video result)
            else if (!res.title.isNullOrEmpty()) {
                val vId = res.id ?: ""
                list.add(
                    SearchItem(
                        id = vId,
                        title = res.title ?: "Unknown Title",
                        uploader = res.uploader ?: res.channel ?: "Artist",
                        duration = res.durationString ?: "HD",
                        thumbnail = res.thumbnail ?: "https://i.ytimg.com/vi/$vId/hqdefault.jpg",
                        url = if (vId.isNotEmpty()) "https://www.youtube.com/watch?v=$vId" else resolvedTarget
                    )
                )
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun runDownload(
        context: Context,
        rawUrl: String,
        formatSpec: String,
        isAudio: Boolean,
        audioBitrate: String,
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        // Double safety check for initialization
        try {
            YoutubeDL.getInstance().version(context)
        } catch (e: Exception) {
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
            } catch (_: Exception) {}
        }

        val cleanUrl = fixUrl(rawUrl)
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .takeIf { it.exists() || it.mkdirs() }
            ?: (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads"))
        val outPattern = "${downloadDir.absolutePath}/%(title)s.%(ext)s"

        val request = YoutubeDLRequest(cleanUrl).apply {
            addOption("--no-warnings")
            addOption("--extractor-args", "youtube:player_client=web,android,ios")
            if (isAudio) {
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", audioBitrate)
            } else {
                addOption("-f", "$formatSpec+ba/bestvideo+bestaudio/best")
                addOption("--merge-output-format", "mp4")
            }
            addOption("-o", outPattern)
            addOption("--no-mtime")
        }

        YoutubeDL.getInstance().execute(request) { progress, _, line ->
            onProgress(progress, line ?: "")
        }

        downloadDir.listFiles()?.maxByOrNull { it.lastModified() } ?: File(downloadDir, "media.mp4")
    }
}

// ================= DOWNLOAD ENGINE =================
object DownloaderBridge {
    private var isInitDone = false

    fun ensureInit(context: Context) {
        if (!isInitDone) {
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                com.yausername.ffmpeg.FFmpeg.getInstance().init(context.applicationContext)
                com.yausername.aria2c.Aria2c.getInstance().init(context.applicationContext)
                isInitDone = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getDownloadDir(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun fixUrl(input: String): String = DownloaderEngine.fixUrl(input)

    suspend fun searchTop10(context: Context, query: String): List<SearchItem> = withContext(Dispatchers.IO) {
        ensureInit(context)
        DownloaderEngine.search(query)
    }

    suspend fun executeDownload(
        context: Context,
        targetUrl: String,
        formatSpec: String,
        isAudioOnly: Boolean,
        audioBitrate: String? = null,
        processId: String? = null,
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        ensureInit(context)
        val validUrl = fixUrl(targetUrl)
        val downloadDir = getDownloadDir(context)
        val existingFiles = downloadDir.listFiles()?.toSet() ?: emptySet()
        val outPattern = "${downloadDir.absolutePath}/%(title).100B.%(ext)s"

        val request = YoutubeDLRequest(validUrl).apply {
            addOption("--no-warnings")
            addOption("--no-check-certificate")
            addOption("--prefer-free-formats")
            addOption("--extractor-args", "youtube:player_client=web,android,ios")
            if (isAudioOnly) {
                addOption("-x")
                addOption("--audio-format", "mp3")
                if (!audioBitrate.isNullOrBlank()) {
                    addOption("--audio-quality", audioBitrate)
                }
                addOption("-f", "bestaudio/ba/b")
            } else {
                addOption("-f", "$formatSpec+bestaudio/bestvideo+bestaudio/best[ext=mp4]/best")
                addOption("--merge-output-format", "mp4")
            }
            addOption("-o", outPattern)
            addOption("--no-mtime")
        }

        YoutubeDL.getInstance().execute(request, processId) { p, _, line ->
            onProgress(p, line ?: "")
        }

        val allFiles = downloadDir.listFiles()?.toSet() ?: emptySet()
        val newlyCreated = allFiles - existingFiles
        val targetFile = newlyCreated.maxByOrNull { it.lastModified() }
            ?: downloadDir.listFiles()?.maxByOrNull { it.lastModified() }
            ?: File(downloadDir, "media.mp4")

        try {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(if (isAudioOnly) "audio/mpeg" else "video/mp4"),
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        targetFile
    }
}

// ================= ACTIVITY =================
class MainActivity : ComponentActivity() {
    private var sharedUrl by mutableStateOf("")
    private var requestedTab by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        captureIntentUrl(intent)

        setContent {
            val vm: SealViewModel = viewModel()
            val isDarkMode by vm.isDarkMode.collectAsState()

            val darkColors = darkColorScheme(
                primary = Color(0xFF00E5FF),
                onPrimary = Color(0xFF00363D),
                primaryContainer = Color(0xFF004F58),
                onPrimaryContainer = Color(0xFF8CF8FF),
                background = Color(0xFF0A0E17),
                onBackground = Color(0xFFE1E7F5),
                surface = Color(0xFF141A26),
                onSurface = Color(0xFFE1E7F5),
                surfaceVariant = Color(0xFF1F2637),
                onSurfaceVariant = Color(0xFFC4CAD4),
                outline = Color(0xFF434D5E)
            )

            val lightColors = lightColorScheme(
                primary = Color(0xFF006876),
                onPrimary = Color.White,
                primaryContainer = Color(0xFF8CF8FF),
                onPrimaryContainer = Color(0xFF002025),
                background = Color(0xFFF7F9FC),
                onBackground = Color(0xFF13171F),
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF13171F),
                surfaceVariant = Color(0xFFE2E7F0),
                onSurfaceVariant = Color(0xFF434D5E),
                outline = Color(0xFFB8C2D1)
            )

            MaterialTheme(
                colorScheme = if (isDarkMode) darkColors else lightColors
            ) {
                MainAppScaffold(
                    initialSharedUrl = sharedUrl,
                    onUrlConsumed = { sharedUrl = "" },
                    requestedTab = requestedTab,
                    onTabConsumed = { requestedTab = null },
                    vm = vm
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureIntentUrl(intent)
    }

    private fun captureIntentUrl(intent: Intent?) {
        val targetTab = intent?.getStringExtra("OPEN_TAB")
        if (!targetTab.isNullOrEmpty()) {
            requestedTab = targetTab
        }
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val incoming = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val found = Regex("""(https?://[^\s]+)""").find(incoming)?.value
            if (!found.isNullOrEmpty()) {
                sharedUrl = found.trim()
            }
        }
    }
}

// ================= MAIN SCAFFOLD & TABS =================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold(
    initialSharedUrl: String,
    onUrlConsumed: () -> Unit,
    requestedTab: String? = null,
    onTabConsumed: () -> Unit = {},
    vm: SealViewModel = viewModel()
) {
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }
    val activeTasks by vm.activeTasks.collectAsState()
    val downloadedHistory by vm.downloadedHistory.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(requestedTab) {
        when (requestedTab) {
            "TASKS" -> selectedTab = AppTab.TASKS
            "DOWNLOADS" -> selectedTab = AppTab.DOWNLOADS
            "HOME" -> selectedTab = AppTab.HOME
        }
        if (requestedTab != null) {
            onTabConsumed()
        }
    }

    // Request Storage, Media, and Notification Permissions
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            Toast.makeText(
                context,
                "Permissions recommended for saving downloaded media & notifications",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        permissionsLauncher.launch(perms.toTypedArray())
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
                AppTab.values().forEach { tab ->
                    val isSelected = selectedTab == tab
                    val badgeNum = if (tab == AppTab.TASKS) activeTasks.size else 0

                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTab = tab },
                        label = { Text(tab.label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        icon = {
                            BadgedBox(badge = {
                                if (badgeNum > 0) Badge { Text(badgeNum.toString()) }
                            }) {
                                Icon(if (isSelected) tab.activeIcon else tab.inactiveIcon, contentDescription = tab.label)
                            }
                        },
                        modifier = Modifier.testTag("tab_${tab.name.lowercase()}"),
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            indicatorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (selectedTab) {
                AppTab.HOME -> HomeSearchScreen(
                    incomingUrl = initialSharedUrl,
                    onUrlHandled = onUrlConsumed,
                    onStartDownload = { url, format, isAudio, qualityLabel, title, thumb ->
                        // Check WRITE_EXTERNAL_STORAGE on Android <= 9 (API 28)
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                            val writePerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                            if (writePerm != PackageManager.PERMISSION_GRANTED) {
                                permissionsLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                            }
                        }

                        val taskId = System.currentTimeMillis().toString()
                        DownloadForegroundService.enqueueDownload(
                            context = context,
                            taskId = taskId,
                            url = url,
                            format = format,
                            isAudio = isAudio,
                            qualityLabel = qualityLabel,
                            title = title,
                            thumb = thumb
                        )
                        Toast.makeText(context, "Download started in foreground service", Toast.LENGTH_SHORT).show()
                    },
                    vm = vm
                )
                AppTab.TASKS -> TasksListScreen(
                    tasks = activeTasks.values.toList(),
                    onCancelTask = { taskId -> vm.cancelDownload(context, taskId) }
                )
                AppTab.DOWNLOADS -> DownloadsScreen(
                    vm = vm,
                    onNavigateHome = { selectedTab = AppTab.HOME }
                )
                AppTab.SETTINGS -> SettingsScreen(vm = vm)
            }
        }
    }
}

// ================= 1. HOME & 10-SEARCH SCREEN =================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSearchScreen(
    incomingUrl: String,
    onUrlHandled: () -> Unit,
    onStartDownload: (url: String, format: String, isAudio: Boolean, qualityLabel: String, title: String, thumb: String) -> Unit,
    vm: SealViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val showBottomSheet by vm.showBottomSheet.collectAsState()
    val selectedItem by vm.selectedItemForSheet.collectAsState()

    var searchInput by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var playingUrl by remember { mutableStateOf<String?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isUpdatingEngine by remember { mutableStateOf(false) }
    var currentYtDlpVersion by remember { mutableStateOf("Checking...") }

    // Fetch initial version
    LaunchedEffect(Unit) {
        try {
            currentYtDlpVersion = YoutubeDL.getInstance().version(context) ?: "Unknown"
        } catch (e: Exception) {
            currentYtDlpVersion = "Not Initialized"
        }
    }

    // Update Dialog with "Update Now" button
    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { if (!isUpdatingEngine) showUpdateDialog = false },
            title = {
                Text(
                    text = "yt-dlp Engine Update",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = "Current Version: $currentYtDlpVersion",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Update yt-dlp config engine to latest version to fix broken extractors and bot detection limits.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    if (isUpdatingEngine) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Updating engine binaries...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isUpdatingEngine = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val status = YoutubeDL.getInstance().updateYoutubeDL(context)
                                val newVer = YoutubeDL.getInstance().version(context) ?: "Updated"
                                withContext(Dispatchers.Main) {
                                    currentYtDlpVersion = newVer
                                    isUpdatingEngine = false
                                    showUpdateDialog = false
                                    Toast.makeText(context, "Engine Updated: $status ($newVer)", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    isUpdatingEngine = false
                                    Toast.makeText(context, "Update Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(50),
                    enabled = !isUpdatingEngine,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Update Now",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUpdateDialog = false },
                    enabled = !isUpdatingEngine
                ) {
                    Text("Close", color = Color.Gray)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    LaunchedEffect(incomingUrl) {
        if (incomingUrl.isNotBlank()) {
            searchInput = incomingUrl
            onUrlHandled()
            isLoading = true
            scope.launch {
                searchResults = DownloaderEngine.search(incomingUrl)
                isLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        // App Bar Title & Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Seal Downloader",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Update Config Button
                FilledTonalButton(
                    onClick = { showUpdateDialog = true },
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.testTag("btn_update_config")
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Update Config",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "Update Config",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                val isDarkMode by vm.isDarkMode.collectAsState()
                IconButton(
                    onClick = { vm.toggleDarkMode() },
                    modifier = Modifier.testTag("btn_quick_theme_toggle")
                ) {
                    Icon(
                        imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = "Toggle theme mode",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = {
                        if (!showBottomSheet && selectedItem == null) {
                            val inputUrl = searchInput.trim()
                            if (inputUrl.isNotBlank()) {
                                vm.openBottomSheet(
                                    SearchItem(
                                        id = System.currentTimeMillis().toString(),
                                        title = inputUrl,
                                        uploader = "Direct Media",
                                        duration = "--:--",
                                        thumbnail = "",
                                        url = inputUrl
                                    )
                                )
                            } else if (searchResults.isNotEmpty()) {
                                vm.openBottomSheet(searchResults.first())
                            } else {
                                vm.toggleBottomSheet()
                            }
                        } else {
                            vm.toggleBottomSheet()
                        }
                    },
                    modifier = Modifier.testTag("btn_toggle_bottom_sheet")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Toggle download options bottom sheet",
                        tint = if (showBottomSheet) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
            }
        }

        // Search Box
        OutlinedTextField(
            value = searchInput,
            onValueChange = { searchInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search song, artist, or paste URL...", color = Color.Gray) },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            trailingIcon = {
                Row {
                    IconButton(onClick = {
                        clipboard.getText()?.let { clipData ->
                            val raw = clipData.text.toString().trim()
                            val urlRegex = Regex("""(https?://[^\s]+)""")
                            val fullUrl = urlRegex.find(raw)?.value ?: raw
                            searchInput = fullUrl

                            // Auto trigger search on paste
                            if (fullUrl.isNotBlank()) {
                                isLoading = true
                                scope.launch {
                                    searchResults = DownloaderEngine.search(fullUrl)
                                    isLoading = false
                                }
                            }
                        }
                    }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste", tint = Color.Gray)
                    }
                    IconButton(onClick = {
                        if (searchInput.isNotBlank()) {
                            isLoading = true
                            scope.launch {
                                searchResults = DownloaderEngine.search(searchInput)
                                isLoading = false
                                if (searchResults.isEmpty()) {
                                    Toast.makeText(context, "No results found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // In-App Player Card
        AnimatedVisibility(visible = playingUrl != null) {
            playingUrl?.let { playStream ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        StreamPlayer(url = playStream)
                        IconButton(
                            onClick = { playingUrl = null },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        // 10-Result List
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(searchResults) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { vm.openBottomSheet(item) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(110.dp, 68.dp).clip(RoundedCornerShape(10.dp))) {
                        AsyncImage(
                            model = item.thumbnail,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            text = item.duration,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .background(Color.Black.copy(0.75f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            color = Color.White,
                            fontSize = 10.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.uploader,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = { vm.openBottomSheet(item) },
                        modifier = Modifier.testTag("btn_item_download_${item.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Open download options",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    // Material 3 Bottom Sheet for Stream Preview & Multi-Format Downloading
    if (showBottomSheet) {
        val targetItem = selectedItem ?: SearchItem(
            id = "direct_url",
            title = if (searchInput.isNotBlank()) searchInput.trim() else "Direct Download",
            uploader = "Media Downloader",
            duration = "--:--",
            thumbnail = "",
            url = if (searchInput.isNotBlank()) searchInput.trim() else ""
        )

        MediaDownloadBottomSheet(
            item = targetItem,
            onDismissRequest = { vm.closeBottomSheet() },
            onPlayStream = { url ->
                playingUrl = url
            },
            onDownloadAudio = { url, bitrate, title, thumb ->
                onStartDownload(url, "ba/b", true, bitrate, title, thumb)
            },
            onDownloadVideo = { url, resolution, formatSelector, title, thumb ->
                onStartDownload(url, formatSelector, false, resolution, title, thumb)
            },
            vm = vm
        )
    }
}

// ================= 2. ACTIVE TASKS SCREEN =================
@Composable
fun TasksListScreen(
    tasks: List<ActiveDownloadTask>,
    onCancelTask: (String) -> Unit = {}
) {
    if (tasks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Downloading,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "No downloads running",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Active downloads are managed by the Foreground Service and will show real-time progress here and in your notification shade even when backgrounded.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(tasks, key = { it.id }) { task ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .testTag("task_card_${task.id}"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            task.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onCancelTask(task.id) },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("cancel_task_${task.id}")
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel download",
                                tint = Color.Red.copy(alpha = 0.8f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (task.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${task.progress.toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(task.speed.ifBlank { "Downloading..." }, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ================= 3. LIBRARY & HISTORY SCREEN =================
@Composable
fun LibraryHistoryScreen(
    records: List<DownloadedRecord>,
    onDelete: (DownloadedRecord) -> Unit,
    onOpenFile: (DownloadedRecord) -> Unit
) {
    if (records.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No downloads in library", color = Color.Gray, fontSize = 14.sp)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        items(records) { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onOpenFile(item) },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = item.thumbnail,
                        contentDescription = null,
                        modifier = Modifier.size(65.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "${item.quality} • ${item.ext.uppercase()} • ${item.fileSize}",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = { onDelete(item) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(0.8f))
                    }
                }
            }
        }
    }
}

// ================= PLAYER COMPOSABLE =================
@Composable
fun StreamPlayer(url: String) {
    val context = LocalContext.current
    var hasError by remember(url) { mutableStateOf(false) }
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    hasError = true
                }
            })
            try {
                setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                prepare()
                playWhenReady = true
            } catch (e: Exception) {
                hasError = true
            }
        }
    }
    DisposableEffect(url) {
        onDispose { player.release() }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Unable to stream video preview directly",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            "Open In External Player / Browser",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

// ================= 4. SETTINGS SCREEN =================
@Composable
fun SettingsScreen(
    vm: SealViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDarkMode by vm.isDarkMode.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .testTag("settings_screen")
    ) {
        Text(
            text = "Settings",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Appearance / Theme Section Card
        Text(
            text = "Appearance",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .testTag("theme_settings_card"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Quick Toggle Row with Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.toggleDarkMode() }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                                contentDescription = "Theme Icon",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Dark Mode",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isDarkMode) "Dark theme enabled" else "Light theme enabled",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = isDarkMode,
                        onCheckedChange = { vm.setDarkMode(it) },
                        modifier = Modifier.testTag("theme_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))

                // Theme Mode Selector Option Cards
                Text(
                    text = "Theme Preference",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Light Theme Option
                    OutlinedCard(
                        onClick = { vm.setDarkMode(false) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("theme_option_light"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (!isDarkMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(
                            width = if (!isDarkMode) 2.dp else 1.dp,
                            color = if (!isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.LightMode,
                                contentDescription = "Light Mode",
                                tint = if (!isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Light",
                                fontSize = 13.sp,
                                fontWeight = if (!isDarkMode) FontWeight.Bold else FontWeight.Medium,
                                color = if (!isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Dark Theme Option
                    OutlinedCard(
                        onClick = { vm.setDarkMode(true) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("theme_option_dark"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (isDarkMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(
                            width = if (isDarkMode) 2.dp else 1.dp,
                            color = if (isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.DarkMode,
                                contentDescription = "Dark Mode",
                                tint = if (isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Dark",
                                fontSize = 13.sp,
                                fontWeight = if (isDarkMode) FontWeight.Bold else FontWeight.Medium,
                                color = if (isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Downloader Preferences Section
        Text(
            text = "Downloader Preferences",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsInfoRow(title = "Download Location", subtitle = "App Scoped Storage / Downloads")
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                SettingsInfoRow(title = "Aria2c Multi-connection", subtitle = "Enabled (Fast segmented downloading)")
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                SettingsInfoRow(title = "Audio Extraction Quality", subtitle = "MP3 (320kbps High fidelity)")
            }
        }

        // About Section
        Text(
            text = "About & Core Engine",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        var ytdlpVersion by remember { mutableStateOf("Checking...") }
        var isUpdatingEngineInSettings by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            try {
                ytdlpVersion = YoutubeDL.getInstance().version(context) ?: "Unknown"
            } catch (e: Exception) {
                ytdlpVersion = "Not Initialized"
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsInfoRow(title = "App Version", subtitle = "1.0.0 (Release Build)")
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                SettingsInfoRow(title = "Core Binaries", subtitle = "yt-dlp ($ytdlpVersion) • FFmpeg • Aria2c")
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "yt-dlp Engine Update",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Download latest extractors to bypass bot verification",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = {
                            isUpdatingEngineInSettings = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val status = YoutubeDL.getInstance().updateYoutubeDL(context)
                                    val newVer = YoutubeDL.getInstance().version(context) ?: "Updated"
                                    withContext(Dispatchers.Main) {
                                        ytdlpVersion = newVer
                                        isUpdatingEngineInSettings = false
                                        Toast.makeText(context, "Engine Updated: $status ($newVer)", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isUpdatingEngineInSettings = false
                                        Toast.makeText(context, "Update Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(50),
                        enabled = !isUpdatingEngineInSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("btn_settings_update_ytdlp")
                    ) {
                        if (isUpdatingEngineInSettings) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Update", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsInfoRow(title: String, subtitle: String) {
    Column {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = subtitle,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
