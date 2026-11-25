package io.easypi.mobile.photos

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import io.easypi.mobile.BackNavigation
import io.easypi.mobile.EasyPiEndpointResolver
import io.easypi.mobile.EasyPiLinkStore
import io.easypi.mobile.LinkedEasyPi
import io.easypi.mobile.MobileApiClient
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Background = Color(0xFF06101D)
private val Surface = Color(0xFF122235)
private val Accent = Color(0xFF52BFFF)
private val Muted = Color(0xFFA4B7CC)
private val Success = Color(0xFF37DC8F)
private val Warning = Color(0xFFFFB34A)

internal data class GalleryItem(
    val key: String,
    val capturedAtMillis: Long,
    val local: LocalMediaBackup? = null,
    val remote: RemotePhoto? = null,
) {
    /** The copy on EasyPi, whether or not the file is also on this phone. */
    val remoteCopy: RemotePhoto? get() = local?.remote ?: remote
    val name: String get() = local?.media?.name ?: remote?.filename.orEmpty()
    val isVideo: Boolean get() = (local?.media?.mimeType ?: remote?.mimeType.orEmpty()).startsWith("video/")
}

internal data class GallerySection(val title: String, val items: List<GalleryItem>)

internal data class BackupStatus(
    val title: String,
    val completed: Int = 0,
    val total: Int = 0,
    /** Work is under way but its size is not known yet. */
    val indeterminate: Boolean = false,
    val done: Boolean = false,
    val paused: Boolean = false,
)

/**
 * The single backup line of the gallery. A running upload reports its own
 * numbers; otherwise the phone's files are compared with EasyPi's list, and
 * until that list arrives nothing is claimed as waiting or backed up.
 */
internal fun backupStatus(
    syncing: Boolean,
    reportedCompleted: Int,
    reportedTotal: Int,
    remoteKnown: Boolean,
    unreachable: Boolean,
    backedUp: Int,
    total: Int,
): BackupStatus = when {
    syncing && reportedTotal > 0 && reportedCompleted < reportedTotal ->
        BackupStatus("Backing up · $reportedCompleted of $reportedTotal files", reportedCompleted, reportedTotal)
    syncing && reportedTotal > 0 ->
        BackupStatus("All $reportedTotal files are backed up", reportedTotal, reportedTotal, done = true)
    syncing -> BackupStatus("Checking backup…", indeterminate = true)
    unreachable -> BackupStatus("Backup paused · EasyPi is unreachable", paused = true)
    !remoteKnown -> BackupStatus("Checking backup…", indeterminate = true)
    total == 0 -> BackupStatus("No photos to back up yet", done = true)
    backedUp >= total -> BackupStatus("All $total files are backed up", total, total, done = true)
    else -> BackupStatus(
        "$backedUp of $total files backed up · ${total - backedUp} waiting",
        backedUp,
        total,
    )
}

internal fun remoteTimestamp(value: String?): Long {
    if (value.isNullOrBlank()) return 0L
    return runCatching { Instant.parse(value).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .recoverCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli() }
        .getOrDefault(0L)
}

internal fun galleryItems(local: List<LocalMediaBackup>, remote: List<RemotePhoto>): List<GalleryItem> {
    val matchedRemoteIds = local.mapNotNull { it.remote?.id }.toSet()
    return (local.map { backup ->
        GalleryItem(
            key = "local:${backup.media.key}",
            capturedAtMillis = backup.media.capturedAtMillis ?: 0L,
            local = backup,
        )
    } + remote.filterNot { it.id in matchedRemoteIds }.map { photo ->
        GalleryItem(
            key = "remote:${photo.id}",
            capturedAtMillis = remoteTimestamp(photo.capturedAt),
            remote = photo,
        )
    }).sortedByDescending { it.capturedAtMillis }
}

/** Group newest-first items by the day they were taken, like a phone gallery. */
internal fun gallerySections(
    items: List<GalleryItem>,
    today: LocalDate,
    zone: ZoneId,
    locale: Locale,
): List<GallerySection> {
    val dayInYear = DateTimeFormatter.ofPattern("d MMMM", locale)
    val fullDay = DateTimeFormatter.ofPattern("d MMMM yyyy", locale)
    return items.groupBy { item ->
        item.capturedAtMillis.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    }.map { (day, dayItems) ->
        val title = when {
            day == null -> "Date unknown"
            day == today -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            day.year == today.year -> dayInYear.format(day)
            else -> fullDay.format(day)
        }
        GallerySection(title, dayItems)
    }
}

class PhotosActivity : ComponentActivity() {
    private var refreshGeneration by mutableIntStateOf(0)
    private var access by mutableStateOf(MediaAccess.NONE)
    private var lastLoadedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GalleryCache.clearViewer(this)
        access = PhotoSyncWorker.mediaAccess(this)
        PhotoSyncWorker.schedule(this)
        if (access != MediaAccess.NONE) PhotoSyncWorker.runNow(this)
        setContent { GalleryScreen() }
    }

    override fun onResume() {
        super.onResume()
        val current = PhotoSyncWorker.mediaAccess(this)
        val changed = current != access
        access = current
        if (changed && current != MediaAccess.NONE) PhotoSyncWorker.runNow(this)
        // Returning to the gallery only rescans when something can have changed.
        if (lastLoadedAt == 0L || changed || SystemClock.elapsedRealtime() - lastLoadedAt > RELOAD_AFTER_MILLIS) {
            refreshGeneration++
        }
    }

    override fun onDestroy() {
        if (isFinishing) GalleryCache.clearViewer(this)
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun GalleryScreen() {
        val scope = rememberCoroutineScope()
        var linkChecked by remember { mutableStateOf(false) }
        var linked by remember { mutableStateOf<LinkedEasyPi?>(null) }
        var linkKey by remember { mutableStateOf("") }
        var activeLink by remember { mutableStateOf<LinkedEasyPi?>(null) }
        var remote by remember { mutableStateOf<List<RemotePhoto>?>(null) }
        var localBackups by remember { mutableStateOf<List<LocalMediaBackup>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var viewerIndex by remember { mutableStateOf<Int?>(null) }
        var savingVideos by remember { mutableStateOf(false) }
        val manualWork = remember { PhotoSyncWorker.manualWork(this) }
        val workInfos by manualWork.collectAsState(initial = emptyList())
        val activeWork = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?: workInfos.firstOrNull { !it.state.isFinished }
        val syncing = activeWork != null
        val syncSettled = workInfos.isNotEmpty() && workInfos.all { it.state.isFinished }
        val reportedTotal = activeWork?.progress?.getInt(PhotoSyncWorker.PROGRESS_TOTAL, 0) ?: 0
        val reportedCompleted = activeWork?.progress?.getInt(PhotoSyncWorker.PROGRESS_COMPLETED, 0) ?: 0
        val remoteKnown = remote != null
        val counted = localBackups.filter { it.state != BackupState.EXCLUDED }
        val failedFiles = if (remoteKnown) localBackups.count { it.state == BackupState.FAILED } else 0
        val excludedFiles = localBackups.size - counted.size
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            access = PhotoSyncWorker.mediaAccess(this)
            if (access != MediaAccess.NONE) PhotoSyncWorker.runNow(this)
            refreshGeneration++
        }
        val requestAccess = { permissionLauncher.launch(PhotoSyncWorker.requiredPermissions(this)) }
        val setVideoBackup: (Boolean) -> Unit = setVideoBackup@{ enable ->
            val current = linked ?: return@setVideoBackup
            val folder = current.photoFolder ?: return@setVideoBackup
            savingVideos = true
            scope.launch {
                try {
                    val updated = withContext(Dispatchers.IO) {
                        val settings = MobileApiClient.setPhotoFolder(activeLink ?: current, folder, enable)
                        // Save onto the stored link: the active one may point at the home address.
                        current.copy(photoFolder = settings.photoFolder, backupVideos = settings.backupVideos).also {
                            EasyPiLinkStore(this@PhotosActivity).save(it)
                            PhotoSyncWorker.onBackupSettingsChanged(this@PhotosActivity)
                        }
                    }
                    linked = updated
                    access = PhotoSyncWorker.mediaAccess(this@PhotosActivity)
                    Toast.makeText(
                        this@PhotosActivity,
                        if (updated.backupVideos) "Videos will be backed up to $folder/Videos" else "Video backup is off. Videos already on EasyPi stay there.",
                        Toast.LENGTH_LONG,
                    ).show()
                    if (updated.backupVideos && access != MediaAccess.FULL) requestAccess()
                    refreshGeneration++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Toast.makeText(this@PhotosActivity, failure.message ?: "Could not change video backup", Toast.LENGTH_LONG).show()
                }
                savingVideos = false
            }
        }
        val items = remember(localBackups, remote) { galleryItems(localBackups, remote.orEmpty()) }
        val positions = remember(items) { items.withIndex().associate { (index, item) -> item.key to index } }
        val sections = remember(items) {
            gallerySections(items, LocalDate.now(), ZoneId.systemDefault(), Locale.getDefault())
        }

        LaunchedEffect(syncSettled) {
            if (syncSettled) refreshGeneration++
        }
        LaunchedEffect(refreshGeneration) {
            if (refreshGeneration == 0) return@LaunchedEffect
            loading = true
            val current = withContext(Dispatchers.IO) { EasyPiLinkStore(this@PhotosActivity).linkedEasyPi() }
            linked = current
            linkChecked = true
            if (current == null) {
                loading = false
                return@LaunchedEffect
            }
            linkKey = withContext(Dispatchers.IO) { BackupLedger.linkKey(this@PhotosActivity, current) }
            // Media on this phone appears at once; EasyPi's list follows when it answers.
            val known = remote
            localBackups = withContext(Dispatchers.IO) { PhotoSyncWorker.mediaBackups(this@PhotosActivity, known.orEmpty()) }
            try {
                val (resolved, list) = withContext(Dispatchers.IO) {
                    val resolved = EasyPiEndpointResolver.resolve(this@PhotosActivity, current)
                    resolved to CloudMediaClient.listAll(resolved)
                }
                activeLink = resolved
                remote = list
                error = null
                localBackups = withContext(Dispatchers.IO) { PhotoSyncWorker.mediaBackups(this@PhotosActivity, list) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                activeLink = null
                error = failure.message ?: "Could not reach EasyPi"
            }
            lastLoadedAt = SystemClock.elapsedRealtime()
            loading = false
        }

        MaterialTheme {
            Scaffold(
                containerColor = Background,
                topBar = {
                    TopAppBar(
                        navigationIcon = { BackNavigation(this@PhotosActivity) },
                        title = { Text("EasyPi Photos", fontWeight = FontWeight.Bold, color = Color.White) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
                    )
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    // Backup runs on its own (on opening, for new media and
                    // periodically); one bar, always in view, shows where it is.
                    if (linked?.photoFolder.isNullOrBlank().not() && access != MediaAccess.NONE) {
                        BackupProgress(
                            backupStatus(
                                syncing = syncing,
                                reportedCompleted = reportedCompleted,
                                reportedTotal = reportedTotal,
                                remoteKnown = remoteKnown,
                                unreachable = error != null,
                                backedUp = counted.count(LocalMediaBackup::isBackedUp),
                                total = counted.size,
                            ),
                            failedFiles,
                            excludedFiles,
                        )
                    }
                    when {
                        !linkChecked -> Centered { CircularProgressIndicator(color = Accent) }
                        linked == null -> LinkRequired()
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(110.dp),
                            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            if (access != MediaAccess.FULL) {
                                item(key = "access", span = { GridItemSpan(maxLineSpan) }) {
                                    AccessBanner(
                                        partial = access == MediaAccess.PARTIAL,
                                        backupVideos = linked?.backupVideos == true,
                                        request = requestAccess,
                                        openSettings = {
                                            startActivity(Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:$packageName"),
                                            ))
                                        },
                                    )
                                }
                            }
                            error?.let { message ->
                                item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                                    Notice(
                                        "Cannot reach EasyPi: $message. Showing what is available on this phone.",
                                        Warning,
                                        action = "Retry",
                                    ) { refreshGeneration++ }
                                }
                            }
                            linked?.takeIf { !it.photoFolder.isNullOrBlank() }?.let { current ->
                                item(key = "videos", span = { GridItemSpan(maxLineSpan) }) {
                                    VideoBackupSetting(
                                        enabled = current.backupVideos,
                                        folder = current.photoFolder.orEmpty(),
                                        saving = savingVideos,
                                        onChange = setVideoBackup,
                                    )
                                }
                            }
                            if (items.isEmpty()) {
                                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                                        if (loading) CircularProgressIndicator(color = Accent) else Text("No photos yet", color = Muted)
                                    }
                                }
                            }
                            sections.forEach { section ->
                                item(key = "day:${section.title}:${section.items.first().key}", span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        section.title,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp),
                                    )
                                }
                                items(section.items, key = { it.key }) { item ->
                                    GalleryTile(item, activeLink, linkKey, remoteKnown) {
                                        viewerIndex = positions[item.key]
                                    }
                                }
                            }
                        }
                    }
                }
            }

            viewerIndex?.let { index ->
                GalleryViewer(
                    items = items,
                    startIndex = index,
                    source = ViewerSource(activeLink, linkKey, remoteKnown),
                    scope = scope,
                    onDismiss = { viewerIndex = null },
                    onChanged = { refreshGeneration++ },
                )
            }
        }
    }

    @Composable
    private fun GalleryTile(
        item: GalleryItem,
        link: LinkedEasyPi?,
        linkKey: String,
        remoteKnown: Boolean,
        open: () -> Unit,
    ) {
        val local = item.local?.media
        val remote = item.remote
        var bitmap by remember(item.key) {
            mutableStateOf<Bitmap?>(
                when {
                    local != null -> GalleryCache.cachedLocal(local)
                    remote != null -> GalleryCache.cachedRemote(linkKey, remote)
                    else -> null
                },
            )
        }
        var failed by remember(item.key) { mutableStateOf(false) }
        LaunchedEffect(item.key, link?.serverUrl) {
            if (bitmap != null) return@LaunchedEffect
            try {
                bitmap = when {
                    local != null -> GalleryCache.localThumbnail(this@PhotosActivity, local)
                    remote != null && link != null -> GalleryCache.remoteThumbnail(this@PhotosActivity, link, linkKey, remote)
                    else -> null
                }
                failed = bitmap == null && link != null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            }
        }
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF162538)).clickable(onClick = open),
            contentAlignment = Alignment.Center,
        ) {
            val shown = bitmap
            when {
                shown != null -> Image(shown.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                failed -> Text(
                    if (item.isVideo) "Video" else "No preview",
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall,
                )
                link == null && local == null -> Unit
                else -> CircularProgressIndicator(Modifier.size(22.dp), color = Accent, strokeWidth = 2.dp)
            }
            if (item.isVideo) {
                val duration = local?.durationMillis?.let(::formatDuration)
                Text(
                    if (duration == null) "▶" else "▶ $duration",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .background(Color(0xAA06101D), RoundedCornerShape(topEnd = 8.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            StatusBadge(item, remoteKnown, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
    }

    /** Marks only what differs from a normal photo; backup progress is the bar's job. */
    @Composable
    private fun StatusBadge(item: GalleryItem, remoteKnown: Boolean, modifier: Modifier) {
        val backup = item.local
        val (symbol, color) = when {
            backup == null -> "☁" to Accent
            remoteKnown && backup.state == BackupState.FAILED -> "!" to Color(0xFFFF7979)
            else -> return
        }
        Text(
            symbol,
            color = Background,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            modifier = modifier.background(color, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }

    @Composable
    private fun BackupProgress(status: BackupStatus, failed: Int, excluded: Int) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
                .background(Surface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                status.title,
                color = if (status.paused) Warning else Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            when {
                status.indeterminate -> LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent,
                    trackColor = Color(0xFF26394D),
                )
                status.total > 0 -> LinearProgressIndicator(
                    progress = { status.completed.coerceIn(0, status.total).toFloat() / status.total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (status.done) Success else Accent,
                    trackColor = Color(0xFF26394D),
                )
            }
            if (failed > 0) {
                Spacer(Modifier.height(5.dp))
                Text(
                    "$failed could not be uploaded · open them to see why",
                    color = Warning,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (excluded > 0) {
                Spacer(Modifier.height(5.dp))
                Text("$excluded removed from backup by you", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(5.dp))
            Text("Copies only · originals stay on this phone", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }

    @Composable
    private fun VideoBackupSetting(enabled: Boolean, folder: String, saving: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .background(Surface, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Video backup", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    if (enabled) "On · new videos go to $folder/Videos" else "Off · only photos are backed up",
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (saving) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), color = Accent, strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { onChange(!enabled) }) {
                    Text(if (enabled) "Turn off" else "Turn on", color = Accent)
                }
            }
        }
    }

    @Composable
    private fun AccessBanner(
        partial: Boolean,
        backupVideos: Boolean,
        request: () -> Unit,
        openSettings: () -> Unit,
    ) {
        val media = if (backupVideos) "photos and videos" else "photos"
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .background(Surface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                if (partial) {
                    "Only some $media can be read, so only those are backed up. Allow access to all $media for continuous backup."
                } else {
                    "Allow access to $media to back them up. Copies already on EasyPi are shown below."
                },
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = request) { Text(if (partial) "Allow all" else "Allow $media") }
                TextButton(onClick = openSettings) { Text("Open settings", color = Accent) }
            }
        }
    }

    @Composable
    private fun Notice(text: String, color: Color, action: String, onAction: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .background(Surface, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(action, color = Accent) }
        }
    }

    @Composable
    private fun LinkRequired() {
        Centered {
            Column(
                Modifier.padding(24.dp).background(Surface, RoundedCornerShape(22.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Connect EasyPi", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Open Mobile Assistant on EasyPi and scan the pairing QR code.", color = Muted)
                Spacer(Modifier.height(18.dp))
                Button(onClick = { finish() }) { Text("Return home") }
            }
        }
    }

    @Composable
    private fun Centered(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }

    private companion object {
        const val RELOAD_AFTER_MILLIS = 2 * 60 * 1000L
    }
}

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
