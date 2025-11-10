package com.github.k1rakishou.chan.core.manager

import android.content.Context
import com.github.k1rakishou.chan.Chan
import com.github.k1rakishou.common.suspendCall
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.descriptor.SiteDescriptor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.joda.time.DateTime
import org.joda.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages rate limiting state for thread downloads. Tracks cooldown periods per site
 * when API/CDN returns 429 Too Many Requests and coordinates pause/resume of downloads.
 * 
 * Thread-safe with proper synchronization to prevent race conditions.
 * Provides reactive state via StateFlow for UI to display countdown timers and
 * emits events when cooldowns expire for automatic retry.
 * Persists active cooldowns to survive app restarts.
 */
@Singleton
class RateLimitManager @Inject constructor(
  private val applicationScope: CoroutineScope,
  private val gson: Gson,
  private val appContext: Context
) {
  private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val _cooldownState = MutableStateFlow<Map<SiteDescriptor, CooldownState>>(emptyMap())
  val cooldownState: StateFlow<Map<SiteDescriptor, CooldownState>> = _cooldownState.asStateFlow()
  
  private val _cooldownExpiredFlow = MutableSharedFlow<SiteDescriptor>(extraBufferCapacity = 16)
  val cooldownExpiredFlow: SharedFlow<SiteDescriptor> = _cooldownExpiredFlow.asSharedFlow()
  
  private val cooldownMap = ConcurrentHashMap<SiteDescriptor, CooldownInfo>()
  private val cooldownJobs = ConcurrentHashMap<SiteDescriptor, Job>()
  private val setCooldownMutex = Mutex()
  
  init {
    // Load persisted cooldowns on initialization
    applicationScope.launch {
      loadPersistedCooldowns()
    }
  }
  
  data class CooldownInfo(
    val endTime: DateTime,
    val retryCount: Int,
    val originalDurationSeconds: Int
  )
  
  sealed class CooldownState {
    object None : CooldownState()
    
    data class Active(
      val siteDescriptor: SiteDescriptor,
      val endTime: DateTime,
      val retryCount: Int,
      val originalDurationSeconds: Int
    ) : CooldownState() {
      fun getRemainingSeconds(): Int {
        val remaining = endTime.millis - DateTime.now().millis
        return (remaining / 1000).toInt().coerceAtLeast(0)
      }
    }
  }
  
  sealed class ValidationResult {
    object NotInCooldown : ValidationResult()
    object CooldownExpired : ValidationResult()
    data class StillRateLimited(val remainingSeconds: Int) : ValidationResult()
    data class OtherError(val code: Int) : ValidationResult()
    data class NetworkError(val error: Exception) : ValidationResult()
  }
  
  /**
   * Set a cooldown period for the specified site.
   * Thread-safe with mutex to prevent race conditions from concurrent 429 responses.
   * Cancels any existing expiration job before scheduling new one to prevent orphaned coroutines.
   * Only updates if new cooldown is longer than existing one.
   * Caps cooldown at MAX_COOLDOWN_SECONDS to prevent indefinite blocking from server errors.
   * 
   * @param siteDescriptor The site that is rate limited
   * @param durationSeconds How long to wait before allowing requests again (in SECONDS)
   * @param retryCount Number of times we've retried (for UI display)
   */
  fun setCooldown(siteDescriptor: SiteDescriptor, durationSeconds: Int, retryCount: Int = 0) {
    applicationScope.launch {
      setCooldownMutex.withLock {
        // If duration is 0 or negative, don't set cooldown - resume immediately
        if (durationSeconds <= 0) {
          Logger.d(TAG, "setCooldown() skipping cooldown for site=${siteDescriptor.siteName}, " +
            "durationSeconds=$durationSeconds (resume immediately)")
          // Clear any existing cooldown to ensure downloads resume
          cooldownMap.remove(siteDescriptor)
          cooldownJobs.remove(siteDescriptor)?.cancel()
          updateCooldownState()
          
          // Emit cooldown expired event to trigger immediate download restart
          Logger.d(TAG, "Emitting cooldownExpiredFlow for immediate resume")
          _cooldownExpiredFlow.emit(siteDescriptor)
          return@withLock
        }
        
        // Cap cooldown duration to prevent extreme values from server
        val cappedDuration = durationSeconds.coerceIn(1, MAX_COOLDOWN_SECONDS)
        if (cappedDuration != durationSeconds) {
          Logger.w(TAG, "setCooldown() capping excessive duration from ${durationSeconds}s to ${cappedDuration}s " +
            "for site=${siteDescriptor.siteName}")
        }
        
        val endTime = DateTime.now().plusSeconds(cappedDuration)
        val existingCooldown = cooldownMap[siteDescriptor]
        
        // Only update if new cooldown is longer or no existing cooldown
        if (existingCooldown == null || endTime.isAfter(existingCooldown.endTime)) {
          val cooldownInfo = CooldownInfo(endTime, retryCount, cappedDuration)
          cooldownMap[siteDescriptor] = cooldownInfo
          
          Logger.d(TAG, "setCooldown() site=${siteDescriptor.siteName}, cappedDuration=${cappedDuration}s, " +
            "endTime=$endTime, retryCount=$retryCount")
          
          // Cancel any existing expiration job for this site to prevent orphaned coroutines
          cooldownJobs[siteDescriptor]?.cancel()
          
          // Schedule automatic cooldown clear and notify
          val expiryJob = applicationScope.launch {
            delay(cappedDuration * 1000L)
            
            setCooldownMutex.withLock {
              // Only clear if this is still the active cooldown (no newer one set)
              if (cooldownMap[siteDescriptor]?.endTime == endTime) {
                cooldownMap.remove(siteDescriptor)
                cooldownJobs.remove(siteDescriptor)
                
                Logger.d(TAG, "Cooldown expired for site=${siteDescriptor.siteName}, emitting event")
                _cooldownExpiredFlow.emit(siteDescriptor)
              }
            }
            updateCooldownState()
          }
          cooldownJobs[siteDescriptor] = expiryJob
          
          updateCooldownState()
        } else {
          Logger.d(TAG, "Ignoring shorter cooldown for ${siteDescriptor.siteName}. " +
            "Existing: ${existingCooldown.endTime}, New: $endTime")
        }
      }
    }
  }
  
  /**
   * Check if a specific site is currently in a rate limit cooldown period.
   * 
   * @param siteDescriptor The site to check
   * @return true if downloads should be paused for this site
   */
  fun isInCooldown(siteDescriptor: SiteDescriptor): Boolean {
    val cooldownInfo = cooldownMap[siteDescriptor] ?: return false
    
    val isActive = cooldownInfo.endTime.isAfterNow
    if (!isActive) {
      // Cooldown expired, clean up
      cooldownMap.remove(siteDescriptor)
      cooldownJobs.remove(siteDescriptor)?.cancel()
      updateCooldownState()
    }
    
    return isActive
  }
  
  /**
   * Get the remaining cooldown duration for a specific site, if any.
   * 
   * @param siteDescriptor The site to check
   * @return Duration remaining, or null if not in cooldown
   */
  fun getRemainingCooldown(siteDescriptor: SiteDescriptor): Duration? {
    val cooldownInfo = cooldownMap[siteDescriptor] ?: return null
    
    return if (cooldownInfo.endTime.isAfterNow) {
      Duration.millis(cooldownInfo.endTime.millis - DateTime.now().millis)
    } else {
      // Cooldown expired, clean up
      cooldownMap.remove(siteDescriptor)
      cooldownJobs.remove(siteDescriptor)?.cancel()
      updateCooldownState()
      null
    }
  }
  
  /**
   * Get cooldown info for a specific site.
   * 
   * @param siteDescriptor The site to check
   * @return CooldownInfo if active, null otherwise
   */
  fun getCooldownInfo(siteDescriptor: SiteDescriptor): CooldownInfo? {
    val cooldownInfo = cooldownMap[siteDescriptor] ?: return null
    
    if (!cooldownInfo.endTime.isAfterNow) {
      // Cooldown expired, clean up
      cooldownMap.remove(siteDescriptor)
      cooldownJobs.remove(siteDescriptor)?.cancel()
      updateCooldownState()
      return null
    }
    
    return cooldownInfo
  }
  
  /**
   * Validate if a rate limit is still active by sending a HEAD request to test URL.
   * This allows checking if the server has cleared the rate limit early.
   * 
   * @param okHttpClient HTTP client to use for request
   * @param siteDescriptor The site to validate
   * @param testUrl A media URL from this site to test
   * @return ValidationResult indicating current rate limit status
   */
  suspend fun validateCooldownStatus(
    okHttpClient: OkHttpClient,
    siteDescriptor: SiteDescriptor,
    testUrl: HttpUrl
  ): ValidationResult {
    if (!isInCooldown(siteDescriptor)) {
      Logger.d(TAG, "validateCooldownStatus() site=${siteDescriptor.siteName} not in cooldown")
      return ValidationResult.NotInCooldown
    }
    
    Logger.d(TAG, "validateCooldownStatus() testing site=${siteDescriptor.siteName} with HEAD request to $testUrl")
    
    // Send HEAD request to check if 429 is still returned
    val request = Request.Builder()
      .url(testUrl)
      .head() // Only fetch headers, not body
      .build()
    
    return try {
      val response = okHttpClient.suspendCall(request)
      Logger.d(TAG, "validateCooldownStatus() response code=${response.code}")
      
      when (response.code) {
        429 -> {
          // Still rate limited, update cooldown if Retry-After present
          val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()?.toInt() ?: 60
          Logger.d(TAG, "validateCooldownStatus() still rate limited, Retry-After=${retryAfterSeconds}s")
          
          setCooldown(siteDescriptor, retryAfterSeconds)
          ValidationResult.StillRateLimited(retryAfterSeconds)
        }
        200, 304 -> {
          // Rate limit expired, clear cooldown
          Logger.d(TAG, "validateCooldownStatus() rate limit expired (code=${response.code})")
          clearCooldown(siteDescriptor)
          ValidationResult.CooldownExpired
        }
        else -> {
          // Other error, keep existing cooldown
          Logger.w(TAG, "validateCooldownStatus() unexpected code=${response.code}")
          ValidationResult.OtherError(response.code)
        }
      }
    } catch (e: Exception) {
      Logger.e(TAG, "validateCooldownStatus() error", e)
      ValidationResult.NetworkError(e)
    }
  }
  
  /**
   * Manually clear a cooldown for a specific site.
   * Cancels any pending expiration job to prevent memory leaks.
   * 
   * @param siteDescriptor The site to clear
   */
  fun clearCooldown(siteDescriptor: SiteDescriptor) {
    Logger.d(TAG, "clearCooldown() site=${siteDescriptor.siteName}")
    cooldownMap.remove(siteDescriptor)
    cooldownJobs.remove(siteDescriptor)?.cancel()
    updateCooldownState()
  }
  
  /**
   * Clear all cooldowns for all sites.
   * Cancels all pending expiration jobs to prevent memory leaks.
   */
  fun clearAllCooldowns() {
    Logger.d(TAG, "clearAllCooldowns()")
    cooldownMap.clear()
    cooldownJobs.values.forEach { it.cancel() }
    cooldownJobs.clear()
    updateCooldownState()
  }
  
  /**
   * Get all sites currently in cooldown.
   * Cleans up expired cooldowns and their associated jobs to prevent memory leaks.
   */
  fun getActiveCooldowns(): Map<SiteDescriptor, CooldownInfo> {
    val now = DateTime.now()
    
    // Clean up expired cooldowns
    val expired = cooldownMap.entries
      .filter { (_, info) -> !info.endTime.isAfter(now) }
      .map { it.key }
    
    expired.forEach { siteDescriptor ->
      cooldownMap.remove(siteDescriptor)
      // Cancel and remove associated job to prevent memory leak
      cooldownJobs.remove(siteDescriptor)?.cancel()
    }
    
    if (expired.isNotEmpty()) {
      updateCooldownState()
    }
    
    return cooldownMap.toMap()
  }
  
  private fun updateCooldownState() {
    val stateMap = cooldownMap.mapValues { (siteDescriptor, info) ->
      CooldownState.Active(
        siteDescriptor = siteDescriptor,
        endTime = info.endTime,
        retryCount = info.retryCount,
        originalDurationSeconds = info.originalDurationSeconds
      )
    }
    _cooldownState.value = stateMap
    
    // Persist updated state
    saveCooldownsToDisk()
  }
  
  private fun saveCooldownsToDisk() {
    applicationScope.launch(Dispatchers.IO) {
      try {
        val persistentData = cooldownMap.mapNotNull { (site, info) ->
          // Only persist cooldowns that haven't expired
          if (info.endTime.isAfterNow) {
            PersistedCooldown(
              siteName = site.siteName,
              endTimeMillis = info.endTime.millis,
              retryCount = info.retryCount,
              originalDurationSeconds = info.originalDurationSeconds
            )
          } else null
        }
        
        val json = gson.toJson(persistentData)
        prefs.edit().putString(KEY_COOLDOWNS, json).apply()
        Logger.d(TAG, "saveCooldownsToDisk() saved ${persistentData.size} cooldowns")
      } catch (e: Exception) {
        Logger.e(TAG, "saveCooldownsToDisk() error", e)
      }
    }
  }
  
  private suspend fun loadPersistedCooldowns() {
    withContext(Dispatchers.IO) {
      try {
        val json = prefs.getString(KEY_COOLDOWNS, null) ?: return@withContext
        val type = object : TypeToken<List<PersistedCooldown>>() {}.type
        val persistedList: List<PersistedCooldown>? = gson.fromJson(json, type)
        val cooldowns = persistedList ?: emptyList()
        
        val now = DateTime.now()
        var restoredCount = 0
        
        cooldowns.forEach { persisted ->
          val endTime = DateTime(persisted.endTimeMillis)
          
          // Only restore cooldowns that haven't expired
          if (endTime.isAfter(now)) {
            val siteDescriptor = SiteDescriptor.create(persisted.siteName)
            val remainingSeconds = ((endTime.millis - now.millis) / 1000).toInt()
            
            // Use public setCooldown to properly initialize job and state
            setCooldown(siteDescriptor, remainingSeconds, persisted.retryCount)
            restoredCount++
            
            Logger.d(TAG, "loadPersistedCooldowns() restored cooldown for ${persisted.siteName}, " +
              "remaining=${remainingSeconds}s")
          }
        }
        
        Logger.d(TAG, "loadPersistedCooldowns() restored $restoredCount cooldowns")
      } catch (e: Exception) {
        Logger.e(TAG, "loadPersistedCooldowns() error", e)
      }
    }
  }
  
  private data class PersistedCooldown(
    val siteName: String,
    val endTimeMillis: Long,
    val retryCount: Int,
    val originalDurationSeconds: Int
  )
  
  companion object {
    private const val TAG = "RateLimitManager"
    
    // Maximum cooldown duration in seconds (1 hour)
    // Prevents indefinite blocking if server sends extreme Retry-After values
    private const val MAX_COOLDOWN_SECONDS = 3600
    
    // SharedPreferences persistence keys
    private const val PREFS_NAME = "rate_limit_manager_prefs"
    private const val KEY_COOLDOWNS = "active_cooldowns"
  }
}
