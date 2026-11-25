package io.easypi.mobile.photos

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.easypi.mobile.EasyPiLinkStore
import java.time.Duration

/**
 * Wakes when Android's media library changes (a photo was taken, a video was
 * recorded) and starts a backup right away instead of waiting for the next
 * periodic sync. It only schedules work; [PhotoSyncWorker] does the upload.
 */
class NewMediaWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val link = EasyPiLinkStore(applicationContext).linkedEasyPi()
        if (link == null || link.photoFolder.isNullOrBlank()) return Result.success()
        if (PhotoSyncWorker.mediaAccess(applicationContext) != MediaAccess.NONE) {
            PhotoSyncWorker.runForNewMedia(applicationContext)
        }
        // A content trigger fires once; queue the next watcher behind this run.
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(link.backupVideos),
        )
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "cloud-media-new-media-watch"

        /** Start watching; [restart] applies a changed photo/video choice. */
        fun watch(context: Context, restart: Boolean) {
            val link = EasyPiLinkStore(context).linkedEasyPi() ?: return
            if (link.photoFolder.isNullOrBlank()) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request(link.backupVideos),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun request(includeVideos: Boolean): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .apply {
                    if (includeVideos) addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                }
                // Let a burst of shots settle, but never wait more than half a minute.
                .setTriggerContentUpdateDelay(Duration.ofSeconds(5))
                .setTriggerContentMaxDelay(Duration.ofSeconds(30))
                .build()
            return OneTimeWorkRequestBuilder<NewMediaWorker>().setConstraints(constraints).build()
        }
    }
}
