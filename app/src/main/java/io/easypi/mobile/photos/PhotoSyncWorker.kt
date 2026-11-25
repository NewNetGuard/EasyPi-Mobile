package io.easypi.mobile.photos

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.easypi.mobile.EasyPiEndpointResolver
import io.easypi.mobile.EasyPiLinkStore
import io.easypi.mobile.LinkedEasyPi
import io.easypi.mobile.R
import io.easypi.mobile.sharing.EasyPiUploader
import io.easypi.mobile.sharing.UploadHttpException
import io.easypi.mobile.sharing.UploadQueue
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow

data class LocalMedia(
    val uri: Uri,
    val key: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val capturedAtMillis: Long?,
    val durationMillis: Long? = null,
)

enum class BackupState {
    /** A copy exists in this phone's EasyPi folder. */
    BACKED_UP,

    /** Not uploaded yet; the next sync will send it. */
    WAITING,

    /** The user removed its copy; it is not uploaded again unless they ask. */
    EXCLUDED,

    /** EasyPi refused it recently; it is retried later or on request. */
    FAILED,
}

data class LocalMediaBackup(
    val media: LocalMedia,
    val remote: RemotePhoto?,
    val state: BackupState = if (remote != null) BackupState.BACKED_UP else BackupState.WAITING,
    val failure: String? = null,
) {
    val isBackedUp: Boolean get() = remote != null
}

enum class MediaAccess {
    /** Every photo (and video, when enabled) can be read: continuous backup works. */
    FULL,

    /** Only files the user selected, or photos without videos, are readable. */
    PARTIAL,
    NONE,
}

internal enum class UploadFailure {
    /** This file cannot be stored as it is; skip it and keep backing up the rest. */
    SKIP_FILE,

    /** The connection or EasyPi is temporarily unavailable; try the whole run later. */
    RETRY_LATER,

    /** This phone is no longer allowed to upload; stop until it is linked again. */
    STOP,
}

internal fun classifyUploadFailure(error: Throwable): UploadFailure = when (error) {
    is UploadHttpException -> when (error.code) {
        401, 403 -> UploadFailure.STOP
        408, 429 -> UploadFailure.RETRY_LATER
        in 500..599 -> UploadFailure.RETRY_LATER
        else -> UploadFailure.SKIP_FILE
    }
    // The file vanished, became unreadable, or the provider returned nothing.
    is FileNotFoundException, is SecurityException, is IllegalArgumentException -> UploadFailure.SKIP_FILE
    else -> UploadFailure.RETRY_LATER
}

internal fun shouldBackUpMedia(mimeType: String, backupVideos: Boolean): Boolean =
    mimeType.startsWith("image/") || (backupVideos && mimeType.startsWith("video/"))

internal fun isExpectedBackupLocation(remoteId: String, folder: String, mimeType: String): Boolean {
    val normalized = remoteId.replace('\\', '/')
    val parent = normalized.substringBeforeLast('/', missingDelimiterValue = "")
    return if (mimeType.startsWith("video/")) {
        parent == "$folder/Videos"
    } else {
        parent == folder
    }
}

private fun mediaSignature(name: String, size: Long): String = "$name\u0000$size"

internal fun matchLocalMediaToBackups(
    local: List<LocalMedia>,
    remote: List<RemotePhoto>,
    folder: String,
    savedCloudIds: Map<String, String>,
    excluded: Set<String> = emptySet(),
    failures: Map<String, String> = emptyMap(),
): List<LocalMediaBackup> {
    val remoteById = remote.associateBy { it.id }
    val availableBySignature = remote
        .groupBy { mediaSignature(it.filename, it.sizeBytes) }
        .mapValues { (_, matches) -> matches.toMutableList() }
        .toMutableMap()
    val usedRemoteIds = mutableSetOf<String>()

    return local.map { media ->
        // A saved id is what EasyPi returned for this file's upload. EasyPi
        // stores identical content once (SHA-256), so the same file kept in two
        // phone folders gets the same id for both: that copy backs up each of
        // them. Only a guess by name and size must claim a server file once.
        val saved = savedCloudIds[media.key]
            ?.let(remoteById::get)
            ?.takeIf { isExpectedBackupLocation(it.id, folder, media.mimeType) }
        val matched = saved ?: availableBySignature[mediaSignature(media.name, media.size)]
            ?.firstOrNull {
                it.id !in usedRemoteIds && isExpectedBackupLocation(it.id, folder, media.mimeType)
            }
        if (matched != null) usedRemoteIds += matched.id
        when {
            matched != null -> LocalMediaBackup(media, matched)
            media.key in excluded -> LocalMediaBackup(media, null, BackupState.EXCLUDED)
            media.key in failures -> LocalMediaBackup(media, null, BackupState.FAILED, failures[media.key])
            else -> LocalMediaBackup(media, null)
        }
    }
}

/**
 * Per-pairing backup bookkeeping. A new pairing starts with a clean ledger, so
 * decisions made for one EasyPi never leak to another.
 */
internal class BackupLedger(context: Context, link: LinkedEasyPi) {
    private val name = STATE + "_" + linkKey(context, link)
    private val uploaded = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val excluded = context.getSharedPreferences(name + "_excluded", Context.MODE_PRIVATE)
    private val failed = context.getSharedPreferences(name + "_failed", Context.MODE_PRIVATE)

    fun savedCloudIds(): Map<String, String> = uploaded.strings()

    fun excluded(): Set<String> = excluded.all.keys.toSet()

    fun activeFailures(now: Long): Map<String, String> = failed.strings().mapNotNull { (key, value) ->
        val at = value.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
        if (now - at in 0 until FAILURE_COOLDOWN_MILLIS) key to value.substringAfter('|') else null
    }.toMap()

    fun recordUpload(key: String, remoteId: String) {
        uploaded.edit().putString(key, remoteId).apply()
        failed.edit().remove(key).apply()
    }

    fun recordFailure(key: String, reason: String, now: Long) {
        failed.edit().putString(key, "$now|${reason.take(200)}").apply()
    }

    fun rememberMatches(backups: List<LocalMediaBackup>) {
        val saved = savedCloudIds()
        val editor = uploaded.edit()
        var changed = false
        backups.forEach { backup ->
            val remoteId = backup.remote?.id ?: return@forEach
            if (saved[backup.media.key] != remoteId) {
                editor.putString(backup.media.key, remoteId)
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    /** Keep the file on the phone, but never upload it again on its own. */
    fun exclude(key: String) {
        excluded.edit().putBoolean(key, true).commit()
        uploaded.edit().remove(key).commit()
        failed.edit().remove(key).apply()
    }

    fun allow(key: String) {
        excluded.edit().remove(key).commit()
        failed.edit().remove(key).commit()
    }

    /** Forget decisions about files that no longer exist on the phone. */
    fun prune(presentKeys: Set<String>) {
        listOf(excluded, failed).forEach { preferences ->
            val stale = preferences.all.keys - presentKeys
            if (stale.isNotEmpty()) preferences.edit().apply { stale.forEach(::remove) }.apply()
        }
    }

    private fun SharedPreferences.strings(): Map<String, String> =
        all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    companion object {
        private const val STATE = "cloud_media_uploaded_v1"
        private const val FAILURE_COOLDOWN_MILLIS = 24L * 60 * 60 * 1000

        fun linkKey(context: Context, link: LinkedEasyPi): String =
            UploadQueue.directory(context.filesDir, link).name
    }
}

class PhotoSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val storedLink = EasyPiLinkStore(applicationContext).linkedEasyPi() ?: return Result.success()
        if (storedLink.photoFolder.isNullOrBlank()) return Result.success()
        val access = mediaAccess(applicationContext)
        if (access == MediaAccess.NONE) return Result.success()
        return runCatching {
            val link = EasyPiEndpointResolver.resolve(applicationContext, storedLink)
            val ledger = BackupLedger(applicationContext, storedLink)
            val remote = CloudMediaClient.listAll(link)
            val backups = reconcile(applicationContext, storedLink, ledger, remote, access)
            val pending = backups.filter { it.state == BackupState.WAITING }
            val total = backups.count { it.state != BackupState.EXCLUDED }
            var completed = backups.count(LocalMediaBackup::isBackedUp)
            publishProgress(completed, total)
            val batch = pending.take(MAX_PER_RUN)
            for ((index, backup) in batch.withIndex()) {
                val media = backup.media
                if (isStopped || EasyPiLinkStore(applicationContext).linkedEasyPi()?.mobileToken != storedLink.mobileToken) {
                    return Result.success()
                }
                showUploadNotification(index, batch.size, media.name)
                try {
                    val cloudFile = EasyPiUploader.upload(
                        link,
                        applicationContext.contentResolver,
                        media.uri,
                        media.name,
                        media.mimeType,
                        photoBackup = media.mimeType.startsWith("image/"),
                        videoBackup = media.mimeType.startsWith("video/"),
                        capturedAtMillis = media.capturedAtMillis,
                    )
                    ledger.recordUpload(media.key, cloudFile.id)
                    completed++
                    publishProgress(completed, total)
                } catch (error: Exception) {
                    Log.w(TAG, "Could not upload media to EasyPi Cloud", error)
                    when (classifyUploadFailure(error)) {
                        UploadFailure.SKIP_FILE -> ledger.recordFailure(
                            media.key,
                            error.message ?: "EasyPi refused this file",
                            System.currentTimeMillis(),
                        )
                        UploadFailure.RETRY_LATER -> return Result.retry()
                        UploadFailure.STOP -> return Result.success()
                    }
                }
            }
            if (pending.size > MAX_PER_RUN) enqueueContinuation(applicationContext)
            Result.success()
        }.getOrElse { error ->
            Log.e(TAG, "EasyPi Cloud sync failed", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EasyPiCloudSync"
        private const val PERIODIC_WORK = "cloud-media-periodic-sync"
        private const val MANUAL_WORK = "cloud-media-manual-sync"
        private const val MAX_PER_RUN = 25
        private const val ACCESS_PROMPTS = "cloud_media_access_prompts_v1"
        const val MEDIA_ACCESS_REQUEST = 4310

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(MANUAL_WORK)
            NewMediaWorker.cancel(context)
            GalleryCache.clear(context)
        }

        /** The photo folder or the video choice changed: watch the right media and sync now. */
        fun onBackupSettingsChanged(context: Context) {
            schedule(context)
            NewMediaWorker.watch(context, restart = true)
            runNow(context)
        }

        /** A new photo or video appeared: back it up now without piling up runs. */
        fun runForNewMedia(context: Context) {
            val manager = WorkManager.getInstance(context)
            val states = runCatching { manager.getWorkInfosForUniqueWork(MANUAL_WORK).get() }
                .getOrDefault(emptyList())
                .map { it.state }
            when {
                // A run that has not started yet will see the new file anyway.
                states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> Unit
                // A running sync listed the library before this file existed.
                states.any { it == WorkInfo.State.RUNNING } ->
                    manager.enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, oneTimeRequest())
                else -> manager.enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, oneTimeRequest())
            }
        }

        /**
         * Ask once per pairing and photo/video choice for the access backup
         * needs, so new media can be uploaded without opening the gallery first.
         */
        fun requestMediaAccessIfNeeded(activity: Activity) {
            val link = EasyPiLinkStore(activity).linkedEasyPi() ?: return
            if (link.photoFolder.isNullOrBlank() || mediaAccess(activity) == MediaAccess.FULL) return
            val prompts = activity.getSharedPreferences(ACCESS_PROMPTS, Context.MODE_PRIVATE)
            val key = BackupLedger.linkKey(activity, link) + ":" + link.backupVideos
            if (prompts.getBoolean(key, false)) return
            prompts.edit().putBoolean(key, true).apply()
            activity.requestPermissions(requiredPermissions(activity), MEDIA_ACCESS_REQUEST)
        }

        fun onMediaAccessResult(context: Context, requestCode: Int) {
            if (requestCode == MEDIA_ACCESS_REQUEST && mediaAccess(context) != MediaAccess.NONE) runNow(context)
        }

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<PhotoSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            NewMediaWorker.watch(context, restart = false)
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK,
                ExistingWorkPolicy.KEEP,
                oneTimeRequest(),
            )
        }

        fun manualWork(context: Context): Flow<List<WorkInfo>> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(MANUAL_WORK)

        fun hasMediaPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 33) {
            granted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
                (!backupVideosEnabled(context) || granted(context, Manifest.permission.READ_MEDIA_VIDEO))
        } else {
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        fun mediaAccess(context: Context): MediaAccess = when {
            hasMediaPermission(context) -> MediaAccess.FULL
            Build.VERSION.SDK_INT >= 34 &&
                granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
            Build.VERSION.SDK_INT >= 33 && granted(context, Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccess.PARTIAL
            else -> MediaAccess.NONE
        }

        fun requiredPermissions(context: Context): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> buildList {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                if (backupVideosEnabled(context)) add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }.toTypedArray()
            Build.VERSION.SDK_INT >= 33 -> buildList {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                if (backupVideosEnabled(context)) add(Manifest.permission.READ_MEDIA_VIDEO)
            }.toTypedArray()
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        /** Local media matched against [remote]; with [remote] unknown, nothing is claimed as backed up. */
        fun mediaBackups(context: Context, remote: List<RemotePhoto>): List<LocalMediaBackup> {
            val link = EasyPiLinkStore(context).linkedEasyPi() ?: return emptyList()
            if (link.photoFolder.isNullOrBlank()) return emptyList()
            val access = mediaAccess(context)
            if (access == MediaAccess.NONE) return emptyList()
            return reconcile(context, link, BackupLedger(context, link), remote, access)
        }

        /** The user removed this file's copy from EasyPi: stop backing it up. */
        fun excludeFromBackup(context: Context, key: String) {
            val link = EasyPiLinkStore(context).linkedEasyPi() ?: return
            BackupLedger(context, link).exclude(key)
        }

        /** Undo an exclusion or a refused upload and try again right away. */
        fun backUpAgain(context: Context, key: String) {
            val link = EasyPiLinkStore(context).linkedEasyPi() ?: return
            BackupLedger(context, link).allow(key)
            runNow(context)
        }

        fun discover(context: Context, includeVideos: Boolean): List<LocalMedia> {
            val collection = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Images.ImageColumns.DATE_TAKEN,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.DURATION,
            )
            val selection = if (includeVideos) {
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
            } else {
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
            }
            val args = buildList {
                add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
                if (includeVideos) add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            }.toTypedArray()
            val result = mutableListOf<LocalMedia>()
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC, ${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)
                val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val size = cursor.getLong(sizeColumn)
                    val mime = cursor.getString(mimeColumn) ?: continue
                    val video = mime.startsWith("video/")
                    if (size <= 0 || (!mime.startsWith("image/") && !video)) continue
                    val modified = cursor.getLong(modifiedColumn)
                    // Type-specific URIs let the system thumbnail and play the item directly.
                    val typed = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    result += LocalMedia(
                        uri = ContentUris.withAppendedId(typed, id),
                        key = "$id:$size:$modified",
                        name = cursor.getString(nameColumn) ?: "photo-$id",
                        mimeType = mime,
                        size = size,
                        capturedAtMillis = cursor.getLong(takenColumn).takeIf { it > 0 }
                            ?: modified.takeIf { it > 0 }?.times(1000),
                        durationMillis = if (video) cursor.getLong(durationColumn).takeIf { it > 0 } else null,
                    )
                }
            }
            return result
        }

        private fun reconcile(
            context: Context,
            link: LinkedEasyPi,
            ledger: BackupLedger,
            remote: List<RemotePhoto>,
            access: MediaAccess,
        ): List<LocalMediaBackup> {
            val local = discover(context, includeVideos = link.backupVideos)
                .filter { shouldBackUpMedia(it.mimeType, link.backupVideos) }
            // With partial access unseen files may still exist; keep their decisions.
            if (access == MediaAccess.FULL) ledger.prune(local.mapTo(mutableSetOf()) { it.key })
            val backups = matchLocalMediaToBackups(
                local,
                remote,
                link.photoFolder!!,
                ledger.savedCloudIds(),
                ledger.excluded(),
                ledger.activeFailures(System.currentTimeMillis()),
            )
            ledger.rememberMatches(backups)
            return backups
        }

        private fun granted(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        private fun backupVideosEnabled(context: Context): Boolean =
            EasyPiLinkStore(context).linkedEasyPi()?.backupVideos == true

        private fun oneTimeRequest() = OneTimeWorkRequestBuilder<PhotoSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        private fun enqueueContinuation(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                oneTimeRequest(),
            )
        }

        const val PROGRESS_COMPLETED = "completed"
        const val PROGRESS_TOTAL = "total"
    }

    private var foregroundAllowed: Boolean? = null

    private fun publishProgress(completed: Int, total: Int) {
        setProgressAsync(workDataOf(PROGRESS_COMPLETED to completed, PROGRESS_TOTAL to total))
    }

    /**
     * Uploads run as a visible foreground task, so Android does not stop a long
     * video after its ten-minute background limit. Android 12+ refuses this
     * while the app is fully in the background; the upload then continues as
     * ordinary background work and is resumed by the next run if stopped.
     */
    private fun showUploadNotification(index: Int, count: Int, name: String) {
        if (foregroundAllowed == false) return
        val info = uploadNotification(applicationContext, index, count, name)
        if (foregroundAllowed == true) {
            setForegroundAsync(info)
            return
        }
        foregroundAllowed = try {
            setForegroundAsync(info).get()
            true
        } catch (error: Exception) {
            Log.i(TAG, "Uploading without a foreground notification", error)
            false
        }
    }
}

private const val UPLOAD_CHANNEL = "media-backup"
private const val UPLOAD_NOTIFICATION = 4310

private fun uploadNotification(context: Context, index: Int, count: Int, name: String): ForegroundInfo {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(UPLOAD_CHANNEL, "Media backup", NotificationManager.IMPORTANCE_LOW),
    )
    val open = PendingIntent.getActivity(
        context,
        0,
        Intent(context, PhotosActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(context, UPLOAD_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Backing up to EasyPi · ${index + 1} of $count")
        .setContentText(name)
        .setProgress(count, index, false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(open)
        .build()
    return ForegroundInfo(UPLOAD_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
}
