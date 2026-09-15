package com.allsocial.sealclone

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

enum class MediaCategoryFilter {
    ALL, VIDEOS, AUDIO
}

enum class MediaSortOrder(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    LARGEST("Largest"),
    NAME("Name (A-Z)")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    vm: SealViewModel,
    onNavigateHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mediaList by remember { mutableStateOf<List<DownloadedMedia>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(MediaCategoryFilter.ALL) }
    var selectedSort by remember { mutableStateOf(MediaSortOrder.NEWEST) }
    var itemToDelete by remember { mutableStateOf<DownloadedMedia?>(null) }

    // Active player state
    var activeMedia by remember { mutableStateOf<DownloadedMedia?>(null) }
    var isPlayerMinimized by remember { mutableStateOf(false) }

    // Load media from MediaStore
    fun loadMedia() {
        isLoading = true
        scope.launch {
            mediaList = MediaStoreHelper.queryAllMedia(context)
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadMedia()
    }

    // Filter and sort items
    val filteredList = remember(mediaList, searchQuery, selectedCategory, selectedSort) {
        var result = mediaList

        // Filter by category
        result = when (selectedCategory) {
            MediaCategoryFilter.ALL -> result
            MediaCategoryFilter.VIDEOS -> result.filter { it.isVideo }
            MediaCategoryFilter.AUDIO -> result.filter { !it.isVideo }
        }

        // Filter by search query
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            result = result.filter {
                it.title.lowercase().contains(q) ||
                        it.displayName.lowercase().contains(q) ||
                        it.artist.lowercase().contains(q) ||
                        it.extension.lowercase().contains(q)
            }
        }

        // Sort items
        when (selectedSort) {
            MediaSortOrder.NEWEST -> result.sortedByDescending { it.dateModified }
            MediaSortOrder.OLDEST -> result.sortedBy { it.dateModified }
            MediaSortOrder.LARGEST -> result.sortedByDescending { it.sizeBytes }
            MediaSortOrder.NAME -> result.sortedBy { it.title.lowercase() }
        }
    }

    // Storage summary calculation
    val totalSizeBytes = remember(mediaList) {
        mediaList.sumOf { it.sizeBytes }
    }
    val totalFormattedSize = remember(totalSizeBytes) {
        val mb = totalSizeBytes / (1024.0 * 1024.0)
        if (mb >= 1024.0) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("downloads_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Header Row: Title & Total Info & Refresh
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Downloads",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${mediaList.size} items • $totalFormattedSize in storage",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledTonalIconButton(
                    onClick = { loadMedia() },
                    modifier = Modifier.testTag("refresh_downloads_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh MediaStore",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .testTag("search_downloads_input"),
                placeholder = { Text("Search downloaded files...", fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            // Category Filter & Sort Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category Chips
                FilterChip(
                    selected = selectedCategory == MediaCategoryFilter.ALL,
                    onClick = { selectedCategory = MediaCategoryFilter.ALL },
                    label = { Text("All (${mediaList.size})", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.Black
                    ),
                    modifier = Modifier.testTag("chip_category_all")
                )

                FilterChip(
                    selected = selectedCategory == MediaCategoryFilter.VIDEOS,
                    onClick = { selectedCategory = MediaCategoryFilter.VIDEOS },
                    label = { Text("Videos (${mediaList.count { it.isVideo }})", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.Black
                    ),
                    modifier = Modifier.testTag("chip_category_videos")
                )

                FilterChip(
                    selected = selectedCategory == MediaCategoryFilter.AUDIO,
                    onClick = { selectedCategory = MediaCategoryFilter.AUDIO },
                    label = { Text("Audio (${mediaList.count { !it.isVideo }})", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.Black
                    ),
                    modifier = Modifier.testTag("chip_category_audio")
                )
            }

            // Content Area: Loading / Empty State / List
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
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
                                    imageVector = Icons.Outlined.DownloadDone,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = if (searchQuery.isNotEmpty()) "No matching downloads" else "No finished downloads found",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = if (searchQuery.isNotEmpty()) "Try adjusting your search terms or filters"
                            else "Files downloaded from social links will appear here automatically via MediaStore.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onNavigateHome,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Find Media", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }

                            OutlinedButton(onClick = { loadMedia() }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scan MediaStore", fontSize = 13.sp)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("downloads_list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = if (activeMedia != null) 90.dp else 16.dp)
                ) {
                    items(filteredList, key = { "${it.id}_${it.filePath}" }) { item ->
                        DownloadedMediaCard(
                            item = item,
                            isPlaying = activeMedia?.id == item.id,
                            onPlay = {
                                activeMedia = item
                                isPlayerMinimized = false
                            },
                            onShare = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = item.mimeType.ifBlank { if (item.isVideo) "video/*" else "audio/*" }
                                    putExtra(Intent.EXTRA_STREAM, item.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Media"))
                            },
                            onDelete = {
                                itemToDelete = item
                            }
                        )
                    }
                }
            }
        }

        // Active ExoPlayer Overlay / Mini Player
        activeMedia?.let { currentMedia ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                IntegratedMediaPlayerSheet(
                    media = currentMedia,
                    isMinimized = isPlayerMinimized,
                    onToggleMinimize = { isPlayerMinimized = !isPlayerMinimized },
                    onClose = { activeMedia = null }
                )
            }
        }
    }

    // Delete Confirmation Dialog
    itemToDelete?.let { targetItem ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete Downloaded File?") },
            text = {
                Text(
                    "Are you sure you want to delete '${targetItem.title}'?\nThis will remove the file permanently from storage and MediaStore.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val deleted = MediaStoreHelper.deleteMedia(context, targetItem)
                            if (deleted) {
                                if (activeMedia?.id == targetItem.id) {
                                    activeMedia = null
                                }
                                loadMedia()
                                Toast.makeText(context, "Deleted: ${targetItem.title}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Unable to delete file", Toast.LENGTH_SHORT).show()
                            }
                            itemToDelete = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DownloadedMediaCard(
    item: DownloadedMedia,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .testTag("media_card_${item.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surface
        ),
        border = if (isPlaying) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail / Icon Box with format & duration badges
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (item.isVideo) Color(0xFF1E293B)
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (item.isVideo) Icons.Default.Videocam else Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = if (item.isVideo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp)
                )

                // Duration badge
                if (item.durationMs > 0) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.75f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                    ) {
                        Text(
                            text = item.formattedDuration,
                            fontSize = 9.sp,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Details Column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Extension badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = item.extension,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }

                    Text(
                        text = "•",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = item.formattedSize,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (item.formattedDate.isNotEmpty()) {
                        Text(
                            text = "•",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = item.formattedDate,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (item.artist.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.artist,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Action Buttons: Play (primary), Share, Delete
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = onPlay,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("play_button_${item.id}"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Equalizer else Icons.Default.PlayArrow,
                        contentDescription = "Play media directly with ExoPlayer",
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("share_button_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share media",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("delete_button_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete media",
                        tint = Color.Red.copy(alpha = 0.75f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
