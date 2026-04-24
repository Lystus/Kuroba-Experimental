package com.github.k1rakishou.chan.features.thread_downloading

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.github.k1rakishou.chan.Chan
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.manager.ThreadDownloadManager
import com.github.k1rakishou.chan.utils.NotificationConstants
import com.github.k1rakishou.common.AndroidUtils
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.core_logger.Logger
import javax.inject.Inject

class ThreadDownloadingWorker(
  context: Context,
  params: WorkerParameters
) : CoroutineWorker(context, params) {

  @Inject
  lateinit var appConstants: AppConstants
  @Inject
  lateinit var threadDownloadingDelegate: ThreadDownloadingDelegate
  @Inject
  lateinit var threadDownloadManager: ThreadDownloadManager

  private val notificationManagerCompat by lazy { NotificationManagerCompat.from(applicationContext) }

  override suspend fun doWork(): Result {
    Chan.getComponent()
      .inject(this)

    setForeground(createForegroundInfo())

    threadDownloadingDelegate.doWork()
      .peekError { error -> Logger.e(TAG, "threadDownloadingDelegate.doWork() unhandled error", error) }
      .ignore()

    val hasActiveThreads = threadDownloadManager.hasActiveThreads()
    val activeThreadsCount = threadDownloadManager.activeThreadsCount()
    Logger.d(TAG, "threadDownloadingDelegate.doWork() done, activeThreadsCount=$activeThreadsCount")

    if (hasActiveThreads) {
      ThreadDownloadingCoordinator.startOrRestartThreadDownloading(
        appContext = applicationContext,
        appConstants = appConstants,
        eager = false
      )
    }

    return Result.success()
  }

  private fun createForegroundInfo(): ForegroundInfo {
    setupNotificationChannel()

    val notification = NotificationCompat.Builder(
      applicationContext,
      NotificationConstants.ThreadDownloaderNotifications.THREAD_DOWNLOADER_NOTIFICATION_CHANNEL_ID
    )
      .setContentTitle(applicationContext.getString(R.string.thread_downloader_downloading_threads))
      .setSmallIcon(R.drawable.ic_stat_notify)
      .setOngoing(true)
      .build()

    return ForegroundInfo(NotificationConstants.THREAD_DOWNLOADER_NOTIFICATION_ID, notification)
  }

  private fun setupNotificationChannel() {
    if (!AndroidUtils.isAndroidO()) {
      return
    }

    val channelId = NotificationConstants.ThreadDownloaderNotifications.THREAD_DOWNLOADER_NOTIFICATION_CHANNEL_ID
    if (notificationManagerCompat.getNotificationChannel(channelId) == null) {
      val channel = NotificationChannel(
        channelId,
        NotificationConstants.ThreadDownloaderNotifications.THREAD_DOWNLOADER_NOTIFICATION_NAME,
        NotificationManager.IMPORTANCE_LOW
      )

      channel.setSound(null, null)
      channel.enableLights(false)
      channel.enableVibration(false)

      notificationManagerCompat.createNotificationChannel(channel)
    }
  }

  companion object {
    private const val TAG = "ThreadDownloadingWorker"
  }

}