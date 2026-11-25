package io.easypi.mobile.photos

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.easypi.mobile.LinkedEasyPi
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

private val ViewerMuted = Color(0xFFA4B7CC)
private val ViewerAccent = Color(0xFF52BFFF)

/** Everything the viewer needs to reach EasyPi; [link] is null while EasyPi is unreachable. */
internal data class ViewerSource(
    val link: LinkedEasyPi?,
    val linkKey: String,
    val remoteKnown: Boolean,
)

@Composable
internal fun GalleryViewer(
    items: List<GalleryItem>,
    startIndex: Int,
    source: ViewerSource,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<Float?>(null) }
    var confirmRemove by remember { mutableStateOf<GalleryItem?>(null) }
    DisposableEffect(Unit) {
        onDispose { GalleryCache.clearViewer(context) }
    }
    LaunchedEffect(pagerState.currentPage) { zoomed = false }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !zoomed,
                key = { items[it].key },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = items[page]
                if (item.isVideo) {
                    VideoPage(item, source, current = pagerState.currentPage == page)
                } else {
                    PhotoPage(item, source) { if (pagerState.currentPage == page) zoomed = it }
                }
            }
            val item = items.getOrNull(pagerState.currentPage)
            if (item != null) {
                ViewerHeader(item, onDismiss, Modifier.align(Alignment.TopStart))
                ViewerActions(
                    item = item,
                    source = source,
                    saving = saving,
                    onSave = { photo ->
                        saving = 0f
                        scope.launch {
                            runCatching { saveToPhone(context, source, photo) { saving = it } }
                                .onSuccess {
                                    Toast.makeText(context, "Saved to EasyPi Photos on this phone", Toast.LENGTH_SHORT).show()
                                    onChanged()
                                }
                                .onFailure { failure ->
                                    if (failure !is CancellationException) {
                                        Toast.makeText(context, failure.message ?: "Could not save the file", Toast.LENGTH_LONG).show()
                                    }
                                }
                            saving = null
                        }
                    },
                    onRemove = { confirmRemove = item },
                    onBackUpAgain = { media ->
                        scope.launch {
                            withContext(Dispatchers.IO) { PhotoSyncWorker.backUpAgain(context, media.key) }
                            Toast.makeText(context, "It will be backed up shortly", Toast.LENGTH_SHORT).show()
                            onChanged()
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        confirmRemove?.let { target ->
            RemoveBackupDialog(
                item = target,
                onCancel = { confirmRemove = null },
                onConfirm = {
                    confirmRemove = null
                    val photo = target.remoteCopy ?: return@RemoveBackupDialog
                    val link = source.link ?: return@RemoveBackupDialog
                    val localKey = target.local?.media?.key
                    scope.launch {
                        // Exclude first, so a sync running right now cannot upload it again.
                        localKey?.let { withContext(Dispatchers.IO) { PhotoSyncWorker.excludeFromBackup(context, it) } }
                        runCatching { withContext(Dispatchers.IO) { CloudMediaClient.delete(link, photo.id) } }
                            .onSuccess {
                                onDismiss()
                                onChanged()
                            }
                            .onFailure { failure ->
                                if (failure is CancellationException) throw failure
                                localKey?.let { withContext(Dispatchers.IO) { PhotoSyncWorker.backUpAgain(context, it) } }
                                Toast.makeText(context, failure.message ?: "Could not remove the copy", Toast.LENGTH_LONG).show()
                            }
                    }
                },
            )
        }
    }
}

@Composable
private fun ViewerHeader(item: GalleryItem, onDismiss: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().background(Color(0x99000000)).safeDrawingPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDismiss) { Text("✕", color = Color.White, style = MaterialTheme.typography.titleLarge) }
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.capturedAtMillis > 0) {
                Text(
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                        .format(Instant.ofEpochMilli(item.capturedAtMillis).atZone(ZoneId.systemDefault())),
                    color = ViewerMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun ViewerActions(
    item: GalleryItem,
    source: ViewerSource,
    saving: Float?,
    onSave: (RemotePhoto) -> Unit,
    onRemove: () -> Unit,
    onBackUpAgain: (LocalMedia) -> Unit,
    modifier: Modifier,
) {
    val backup = item.local
    val status = when {
        backup == null -> "Only on EasyPi · not on this phone"
        !source.remoteKnown -> "Backup status unknown · EasyPi is unreachable"
        backup.state == BackupState.BACKED_UP -> "Backed up to EasyPi"
        backup.state == BackupState.WAITING -> "Waiting to upload"
        backup.state == BackupState.EXCLUDED -> "Not backed up · you removed its copy from EasyPi"
        else -> "Could not upload: ${backup.failure ?: "EasyPi refused this file"}"
    }
    Column(
        modifier.fillMaxWidth().background(Color(0x99000000)).safeDrawingPadding().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(status, color = ViewerMuted, style = MaterialTheme.typography.bodySmall)
        if (saving != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { saving }, modifier = Modifier.fillMaxWidth(), color = ViewerAccent)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val remote = item.remoteCopy
            if (backup == null && remote != null && source.link != null) {
                Button(onClick = { onSave(remote) }, enabled = saving == null) {
                    Text(if (saving == null) "Save to phone" else "Saving ${(saving * 100).toInt()}%")
                }
            }
            if (backup != null && source.remoteKnown &&
                (backup.state == BackupState.EXCLUDED || backup.state == BackupState.FAILED)
            ) {
                Button(onClick = { onBackUpAgain(backup.media) }) {
                    Text(if (backup.state == BackupState.FAILED) "Try again" else "Back up again")
                }
            }
            if (remote != null && source.link != null) {
                TextButton(onClick = onRemove) {
                    Text(if (backup == null) "Remove from EasyPi" else "Remove backup", color = Color(0xFFFF7979))
                }
            }
        }
    }
}

@Composable
private fun RemoveBackupDialog(item: GalleryItem, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val onPhone = item.local != null
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (onPhone) "Remove backup copy?" else "Remove from EasyPi?") },
        text = {
            Text(
                if (onPhone) {
                    "The copy on EasyPi moves to the EasyPi Cloud trash, where it can be restored. " +
                        "The original stays on this phone and is not uploaded again unless you choose Back up again."
                } else {
                    "This file is not on this phone, so this is its only copy. " +
                        "It moves to the EasyPi Cloud trash, where it can be restored."
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove", color = Color(0xFFFF7979)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun PhotoPage(item: GalleryItem, source: ViewerSource, onZoomChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(item.key) { mutableStateOf<Bitmap?>(null) }
    var progress by remember(item.key) { mutableStateOf<Float?>(null) }
    var error by remember(item.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.key) {
        try {
            val local = item.local?.media
            bitmap = if (local != null) {
                runInterruptible(Dispatchers.IO) {
                    GalleryCache.decodeScaled(ImageDecoder.createSource(context.contentResolver, local.uri), GalleryCache.SCREEN_PIXELS)
                }
            } else {
                val file = downloadForViewer(context, source, item.remoteCopy!!) { progress = it }
                runInterruptible(Dispatchers.IO) {
                    GalleryCache.decodeScaled(ImageDecoder.createSource(file), GalleryCache.SCREEN_PIXELS)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "This photo cannot be shown"
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val shown = bitmap
        when {
            shown != null -> ZoomableImage(shown, item.name, onZoomChanged)
            error != null -> Text(error!!, color = Color(0xFFFFB34A), modifier = Modifier.padding(24.dp))
            else -> LoadingIndicator(progress)
        }
    }
}

// transformable(canPan) lets one-finger swipes reach the pager until the photo is zoomed in.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImage(bitmap: Bitmap, description: String, onZoomChanged: (Boolean) -> Unit) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(value: Offset, zoom: Float): Offset {
        val maxX = size.width * (zoom - 1f) / 2f
        val maxY = size.height * (zoom - 1f) / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }
    fun apply(zoom: Float, pan: Offset) {
        scale = zoom.coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else clamp(pan, scale)
        onZoomChanged(scale > 1f)
    }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        apply(scale * zoomChange, offset + panChange)
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = { if (scale > 1f) apply(1f, Offset.Zero) else apply(2.5f, Offset.Zero) })
            }
            .transformable(state, canPan = { scale > 1f })
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

@Composable
private fun VideoPage(item: GalleryItem, source: ViewerSource, current: Boolean) {
    val context = LocalContext.current
    val local = item.local?.media
    var playable by remember(item.key) { mutableStateOf(local?.uri) }
    var preview by remember(item.key) { mutableStateOf<Bitmap?>(null) }
    var progress by remember(item.key) { mutableStateOf<Float?>(null) }
    var error by remember(item.key) { mutableStateOf<String?>(null) }
    var requested by remember(item.key) { mutableStateOf(false) }
    val remote = item.remoteCopy
    LaunchedEffect(item.key) {
        if (local == null && remote != null && source.link != null) {
            preview = runCatching { GalleryCache.remoteThumbnail(context, source.link, source.linkKey, remote) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        }
    }
    LaunchedEffect(item.key, requested) {
        if (!requested || playable != null || remote == null) return@LaunchedEffect
        try {
            playable = Uri.fromFile(downloadForViewer(context, source, remote) { progress = it })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "This video cannot be played"
            requested = false
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val uri = playable
        when {
            uri != null -> VideoPlayer(uri, current)
            requested -> LoadingIndicator(progress)
            else -> {
                preview?.let {
                    Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = { error = null; requested = true },
                        enabled = source.link != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC122235)),
                    ) {
                        Text("▶  Play · ${formatBytes(remote?.sizeBytes ?: 0)}", color = Color.White)
                    }
                    error?.let { Text(it, color = Color(0xFFFFB34A), modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun VideoPlayer(uri: Uri, current: Boolean) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                val controller = MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoURI(uri)
                setOnPreparedListener { if (current) start() }
                view = this
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = Modifier.fillMaxSize(),
    )
    LaunchedEffect(current) {
        val player = view ?: return@LaunchedEffect
        if (current) player.start() else player.pause()
    }
}

@Composable
private fun LoadingIndicator(progress: Float?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (progress == null) {
            CircularProgressIndicator(color = ViewerAccent)
        } else {
            CircularProgressIndicator(progress = { progress }, color = ViewerAccent)
            Spacer(Modifier.height(8.dp))
            Text("Downloading ${(progress * 100).toInt()}%", color = ViewerMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private suspend fun downloadForViewer(
    context: Context,
    source: ViewerSource,
    photo: RemotePhoto,
    onProgress: (Float) -> Unit,
): File {
    val link = checkNotNull(source.link) { "EasyPi is unreachable" }
    val file = GalleryCache.viewerFile(context, source.linkKey, photo)
    if (file.isFile) return file
    runInterruptible(Dispatchers.IO) {
        var shown = -1
        CloudMediaClient.downloadOriginal(link, photo, file) { copied, total ->
            val percent = if (total > 0) (copied * 100 / total).toInt().coerceIn(0, 100) else 0
            if (percent != shown) {
                shown = percent
                onProgress(percent / 100f)
            }
        }
    }
    return file
}

/** Copy an EasyPi-only file into the phone's gallery without blocking the screen. */
private suspend fun saveToPhone(context: Context, source: ViewerSource, photo: RemotePhoto, onProgress: (Float) -> Unit) {
    val link = checkNotNull(source.link) { "EasyPi is unreachable" }
    val image = photo.mimeType.startsWith("image/")
    val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val resolver = context.contentResolver
    val downloaded = GalleryCache.viewerFile(context, source.linkKey, photo).takeIf { it.isFile }
    runInterruptible(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, photo.filename)
            put(MediaStore.MediaColumns.MIME_TYPE, photo.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (image) "Pictures" else "Movies") + "/EasyPi Photos")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collection, values)) { "Could not create the file on this phone" }
        var complete = false
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")) { "Could not write the file on this phone" }.use { output ->
                if (downloaded != null) {
                    downloaded.inputStream().use { it.copyTo(output, 1024 * 1024) }
                } else {
                    var shown = -1
                    CloudMediaClient.copyOriginal(link, photo, output) { copied, total ->
                        val percent = if (total > 0) (copied * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (percent != shown) {
                            shown = percent
                            onProgress(percent / 100f)
                        }
                    }
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            complete = true
        } finally {
            if (!complete) resolver.delete(uri, null, null)
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
