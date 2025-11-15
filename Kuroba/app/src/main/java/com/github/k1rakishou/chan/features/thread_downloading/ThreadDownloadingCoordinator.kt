package com.github.k1rakishou.chan.features.thread_downloading

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.chan.core.manager.ThreadDownloadManager
import com.github.k1rakishou.common.AndroidUtils
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.core_logger.Logger
import androidx.work.WorkInfo
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class ThreadDownloadingCoordinator(
  private val appContext: Context,
  private val appScope: CoroutineScope,
  private val appConstants: AppConstants,
  private val _threadDownloadManager: Lazy<ThreadDownloadManager>,
  private val _rateLimitManager: Lazy<com.github.k1rakishou.chan.core.manager.RateLimitManager>
) {

  private val threadDownloadManager: ThreadDownloadManager
    get() = _threadDownloadManager.get()
  
  private val rateLimitManager: com.github.k1rakishou.chan.core.manager.RateLimitManager
    get() = _rateLimitManager.get()

  private val startupRestartDone = AtomicBoolean(false)

  fun initialize() {
    appScope.launch {
      threadDownloadManager.threadDownloadUpdateFlow
        .debounce(1.seconds)
        .collect { event -> onThreadDownloadUpdateEvent(event) }
    }

    appScope.launch {
      ChanSettings.threadDownloaderUpdateInterval.listenForChanges()
        .asFlow()
        .collect { startOrRestartThreadDownloading(appContext, appConstants, eager = true) }
    }
    
    // Auto-retry downloads when cooldown expires
    appScope.launch {
      rateLimitManager.cooldownExpiredFlow.collect { siteDescriptor ->
        Logger.d(TAG, "Cooldown expired for site=${siteDescriptor.siteName}, " +
          "checking if downloads should resume")
        
        if (threadDownloadManager.hasActiveThreads()) {
          Logger.d(TAG, "Active threads found, restarting downloads")
          startOrRestartThreadDownloading(appContext, appConstants, eager = true)
        }
      }
    }
    
    // NEW: Explicit startup check to handle active downloads on app startup
    appScope.launch {
      try {
        delay(1000)  // Give flow collectors time to set up
        threadDownloadManager.awaitInitialization()
        
        if (!startupRestartDone.compareAndSet(false, true)) {
          Logger.d(TAG, "Startup restart already done, skipping")
          return@launch
        }
        
        val activeThreadsCount = threadDownloadManager.activeThreadsCount()
        if (activeThreadsCount > 0) {
          Logger.d(TAG, "Found $activeThreadsCount active threads on startup, restarting downloads")
          startOrRestartThreadDownloading(
            appContext = appContext, 
            appConstants = appConstants, 
            eager = true, 
            immediate = true
          )
        } else {
          Logger.d(TAG, "No active threads found on startup")
        }
      } catch (e: CancellationException) {
        Logger.e(TAG, "Startup restart check cancelled", e)
        throw e
      } catch (e: Exception) {
        Logger.e(TAG, "Startup restart check failed", e)
      }
    }
  }

  private suspend fun onThreadDownloadUpdateEvent(event: ThreadDownloadManager.Event) {
    when (event) {
      ThreadDownloadManager.Event.Initialized -> {
        // NEW: Handle initialization event if startup check hasn't run yet
        if (!startupRestartDone.get()) {
          delay(100)  // Brief delay to avoid race with explicit check
          if (startupRestartDone.compareAndSet(false, true)) {
            val activeThreadsCount = threadDownloadManager.activeThreadsCount()
            if (activeThreadsCount > 0) {
              Logger.d(TAG, "Event.Initialized triggered restart (found $activeThreadsCount active threads)")
              startOrRestartThreadDownloading(
                appContext = appContext, 
                appConstants = appConstants, 
                eager = true, 
                immediate = true
              )
            }
          }
        }
      }
      is ThreadDownloadManager.Event.StartDownload -> {
        startOrRestartThreadDownloading(appContext, appConstants, eager = true, forceRestart = true)
      }
      is ThreadDownloadManager.Event.CancelDownload,
      is ThreadDownloadManager.Event.CompleteDownload,
      is ThreadDownloadManager.Event.StopDownload -> {
        if (!threadDownloadManager.hasActiveThreads()) {
          cancelThreadDownloading(appContext, appConstants)
        }
      }
      is ThreadDownloadManager.Event.InitializationFailed -> {
        Logger.e(TAG, "ThreadDownloadManager initialization failed", event.error)
      }
    }
  }

  companion object {
    private const val TAG = "ThreadDownloadingCoordinator"

    suspend fun startOrRestartThreadDownloading(
      appContext: Context,
      appConstants: AppConstants,
      eager: Boolean,
      immediate: Boolean = false,
      forceRestart: Boolean = false
    ) {
      if (AndroidUtils.isNotMainProcess()) {
        return
      }

      val tag = appConstants.threadDownloadWorkUniqueTag
      Logger.d(TAG, "startOrRestartThreadDownloading() called tag=$tag, eager=$eager, immediate=$immediate, forceRestart=$forceRestart")

      // Check if work is already running
      val existingWork = WorkManager
        .getInstance(appContext)
        .getWorkInfosForUniqueWork(tag)
        .await()
        .firstOrNull()

      val policy = when {
        forceRestart -> {
          Logger.d(TAG, "Force restart requested")
          ExistingWorkPolicy.REPLACE
        }
        existingWork?.state == WorkInfo.State.RUNNING -> {
          Logger.d(TAG, "Work already running, keeping it")
          ExistingWorkPolicy.KEEP
        }
        else -> {
          Logger.d(TAG, "No running work, replacing any enqueued work")
          ExistingWorkPolicy.REPLACE
        }
      }

      val threadDownloadInterval = when {
        immediate -> 0L  // Start immediately on startup
        eager -> TimeUnit.SECONDS.toMillis(5)
        else -> ChanSettings.threadDownloaderUpdateInterval.get().toLong()
      }

      val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

      val workRequest = OneTimeWorkRequestBuilder<ThreadDownloadingWorker>()
        .addTag(tag)
        .setInitialDelay(threadDownloadInterval, TimeUnit.MILLISECONDS)
        .setConstraints(constraints)
        .build()

      WorkManager
        .getInstance(appContext)
        .enqueueUniqueWork(tag, policy, workRequest)
        .result
        .await()

      Logger.d(TAG, "startOrRestartThreadDownloading() enqueued work with tag=$tag, policy=$policy, " +
          "eager=$eager, immediate=$immediate, threadDownloadInterval=$threadDownloadInterval")
    }

    suspend fun cancelThreadDownloading(
      appContext: Context,
      appConstants: AppConstants,
    ) {
      if (AndroidUtils.isNotMainProcess()) {
        return
      }

      val tag = appConstants.threadDownloadWorkUniqueTag
      Logger.d(TAG, "cancelThreadDownloading() called tag=$tag")

      WorkManager
        .getInstance(appContext)
        .cancelUniqueWork(tag)
        .result
        .await()

      Logger.d(TAG, "cancelThreadDownloading() work with tag $tag canceled")
    }

  }
}