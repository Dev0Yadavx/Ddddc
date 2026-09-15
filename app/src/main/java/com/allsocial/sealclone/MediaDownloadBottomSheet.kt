package com.allsocial.sealclone

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

/**
 * Format type: Video or Audio extraction.
 */
enum class DownloadMediaType(val label: String) {
    VIDEO("Video (MP4)"),
    AUDIO("Audio (MP3)")
}

/**
 * Data representation of an audio quality choice.
 */
data class AudioQualityOption(
    val label: String,
    val bitrate: String,
    val description: String
)

/**
 * Data representation of a video resolution option.
 */
data class VideoQualityOption(
    val label: String,
    val subLabel: String,
    val formatSelector: String,
    val isRecommended: Boolean = false
)

/**
 * DownloadOptionsSheet composable that displays UI elements for:
 * - Audio / Video format toggles
 * - Resolution and bitrate selection
 * - Start Download button
 * Hosted directly inside the ModalBottomSheet.
 */
@Composable
fun DownloadOptionsSheet(
    item: SearchItem,
    onDismissRequest: () -> Unit,
    onPlayStream: (url: String) -> Unit,
    onDownloadAudio: (url: String, bitrate: String, title: String, thumbnail: String) -> Unit,
    onDownloadVideo: (url: String, resolution: String, formatSelector: String, title: String, thumbnail: String) -> Unit,
    modifier: Modifier = Modifier,
    vm: SealViewModel = viewModel()
) {
    val context = LocalContext.current

    // Observe real-time download state from ViewModel
    val activeTasks by vm.activeTasks.collectAsState()
    val activeTask = activeTasks.values.firstOrNull {
        it.title == item.title || it.id == item.id || (item.title.isNotBlank() && it.title.contains(item.title))
    } ?: activeTasks.values.lastOrNull()

    val isDownloading = activeTask != null
    val progressFloat = ((activeTask?.progress ?: 0f) / 100f).coerceIn(0f, 1f)

    val audioOptions = remember {
        listOf(
            AudioQualityOption(label = "320 kbps", bitrate = "320K", description = "High Res (320k)"),
            AudioQualityOption(label = "192 kbps", bitrate = "192K", description = "Standard (192k)"),
            AudioQualityOption(label = "128 kbps", bitrate = "128K", description = "Normal (128k)"),
            AudioQualityOption(label = "64 kbps", bitrate = "64K", description = "Voice (64k)")
        )
    }

    val videoOptions = remember {
        listOf(
            VideoQualityOption("4K UHD", "2160p • MP4", "bestvideo[height<=2160]"),
            VideoQualityOption("2K QHD", "1440p • MP4", "bestvideo[height<=1440]"),
            VideoQualityOption("1080p FHD", "1080p • MP4", "bestvideo[height<=1080]", isRecommended = true),
            VideoQualityOption("720p HD", "720p • MP4", "bestvideo[height<=720]"),
            VideoQualityOption("480p SD", "480p • MP4", "bestvideo[height<=480]"),
            VideoQualityOption("360p Low", "360p • MP4", "bestvideo[height<=360]")
        )
    }

    var selectedMediaType by remember { mutableStateOf(DownloadMediaType.VIDEO) }
    var selectedVideoOption by remember { mutableStateOf(videoOptions[2]) } // 1080p FHD recommended
    var selectedAudioOption by remember { mutableStateOf(audioOptions[0]) } // 320 kbps

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Media Header Info Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.thumbnail.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .size(width = 110.dp, height = 70.dp)
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    AsyncImage(
                        model = item.thumbnail,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (item.duration.isNotBlank()) {
                        Text(
                            text = item.duration,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.uploader,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Stream Preview Action Button
        OutlinedButton(
            onClick = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    onPlayStream(item.url)
                }
                onDismissRequest()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .testTag("btn_watch_stream"),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Watch stream preview",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Watch Stream Preview",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Format Toggle: Video vs Audio
        Text(
            text = "Download Format",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(4.dp)
        ) {
            // Video Toggle
            val isVideo = selectedMediaType == DownloadMediaType.VIDEO
            Button(
                onClick = { selectedMediaType = DownloadMediaType.VIDEO },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .testTag("toggle_format_video"),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isVideo) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (isVideo) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Video (MP4)",
                    fontSize = 13.sp,
                    fontWeight = if (isVideo) FontWeight.Bold else FontWeight.Medium
                )
            }

            // Audio Toggle
            val isAudio = selectedMediaType == DownloadMediaType.AUDIO
            Button(
                onClick = { selectedMediaType = DownloadMediaType.AUDIO },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .testTag("toggle_format_audio"),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAudio) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (isAudio) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Audio (MP3)",
                    fontSize = 13.sp,
                    fontWeight = if (isAudio) FontWeight.Bold else FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Resolution / Bitrate Selection
        if (selectedMediaType == DownloadMediaType.VIDEO) {
            Text(
                text = "Select Resolution",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                videoOptions.forEach { vOpt ->
                    val isSelected = selectedVideoOption == vOpt
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { selectedVideoOption = vOpt }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .testTag("resolution_option_${vOpt.label.replace(" ", "_")}"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedVideoOption = vOpt },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = vOpt.label,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (vOpt.isRecommended) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = "Recommended",
                                                color = MaterialTheme.colorScheme.primary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = vOpt.subLabel,
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        } else {
            Text(
                text = "Select Audio Bitrate",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                audioOptions.forEach { aOpt ->
                    val isSelected = selectedAudioOption == aOpt
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { selectedAudioOption = aOpt }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .testTag("audio_option_${aOpt.bitrate}"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedAudioOption = aOpt },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = aOpt.label,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = aOpt.description,
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Real-Time Download Progress (observing ViewModel activeTasks state)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isDownloading) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(14.dp)
                .testTag("download_progress_card")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isDownloading) Icons.Default.Download else Icons.Default.Check,
                        contentDescription = null,
                        tint = if (isDownloading) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isDownloading) "Downloading in Progress..." else "Download Status: Ready",
                        color = if (isDownloading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = if (isDownloading) "${(progressFloat * 100).toInt()}%" else "0%",
                    color = if (isDownloading) MaterialTheme.colorScheme.primary else Color.Gray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("download_progress_text")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progressFloat },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .testTag("download_progress_indicator"),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isDownloading) (activeTask?.speed?.ifBlank { "Processing stream..." } ?: "Downloading...") else "Tap Start Download to begin",
                    color = if (isDownloading) MaterialTheme.colorScheme.primary else Color.Gray,
                    fontSize = 11.sp,
                    modifier = Modifier.testTag("download_speed_text")
                )
                if (isDownloading) {
                    Text(
                        text = "Real-time sync",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Start Download Button
        Button(
            onClick = {
                if (selectedMediaType == DownloadMediaType.VIDEO) {
                    onDownloadVideo(
                        item.url,
                        selectedVideoOption.label,
                        selectedVideoOption.formatSelector,
                        item.title,
                        item.thumbnail
                    )
                } else {
                    onDownloadAudio(
                        item.url,
                        selectedAudioOption.bitrate,
                        item.title,
                        item.thumbnail
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("btn_start_download"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.Black
            )
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isDownloading) {
                    "Downloading (${(progressFloat * 100).toInt()}%)..."
                } else if (selectedMediaType == DownloadMediaType.VIDEO) {
                    "Start Download (${selectedVideoOption.label})"
                } else {
                    "Start Download (${selectedAudioOption.label} MP3)"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Material 3 ModalBottomSheet hosting the DownloadOptionsSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDownloadBottomSheet(
    item: SearchItem,
    onDismissRequest: () -> Unit,
    onPlayStream: (url: String) -> Unit,
    onDownloadAudio: (url: String, bitrate: String, title: String, thumbnail: String) -> Unit,
    onDownloadVideo: (url: String, resolution: String, formatSelector: String, title: String, thumbnail: String) -> Unit,
    modifier: Modifier = Modifier,
    vm: SealViewModel = viewModel()
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        },
        modifier = modifier.testTag("media_download_bottom_sheet")
    ) {
        DownloadOptionsSheet(
            item = item,
            onDismissRequest = onDismissRequest,
            onPlayStream = onPlayStream,
            onDownloadAudio = onDownloadAudio,
            onDownloadVideo = onDownloadVideo,
            modifier = Modifier,
            vm = vm
        )
    }
}
