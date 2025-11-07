package com.github.k1rakishou.chan.core.manager

import com.github.k1rakishou.chan.utils.BackgroundUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.joda.time.DateTime
import org.joda.time.Duration
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages rate limiting state for thread downloads. Tracks cooldown periods
 * when API returns 429 Too Many Requests and coordinates pause/resume of downloads.
 * 
 * Provides reactive state via StateFlow for UI to display countdown timers.
 */
@Singleton
class RateLimitManager @Inject constructor(
  private val applicationScope: CoroutineScope
) {
  private val _cooldownState = MutableStateFlow<CooldownState>(CooldownState.None)
  val cooldownState: StateFlow<CooldownState> = _cooldownState.asStateFlow()
  
  private val cooldownEndTime = AtomicReference<DateTime?>(null)
  
  sealed class CooldownState {
    object None : CooldownState()
    
    data class Active(
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
  
  /**
   * Set a cooldown period for the specified duration.
   * 
   * @param durationSeconds How long to wait before allowing requests again
   * @param retryCount Number of times we've retried (for UI display)
   */
  fun setCooldown(durationSeconds: Int, retryCount: Int = 0) {
    BackgroundUtils.ensureBackgroundThread()
    
    val endTime = DateTime.now().plusSeconds(durationSeconds)
    cooldownEndTime.set(endTime)
    _cooldownState.value = CooldownState.Active(endTime, retryCount, durationSeconds)
    
    // Schedule automatic cooldown clear
    applicationScope.launch {
      delay(durationSeconds * 1000L)
      
      // Only clear if this is still the active cooldown
      if (cooldownEndTime.get() == endTime) {
        cooldownEndTime.set(null)
        _cooldownState.value = CooldownState.None
      }
    }
  }
  
  /**
   * Check if we're currently in a rate limit cooldown period.
   * 
   * @return true if downloads should be paused
   */
  fun isInCooldown(): Boolean {
    val endTime = cooldownEndTime.get() ?: return false
    return endTime.isAfterNow
  }
  
  /**
   * Get the remaining cooldown duration, if any.
   * 
   * @return Duration remaining, or null if not in cooldown
   */
  fun getRemainingCooldown(): Duration? {
    val endTime = cooldownEndTime.get() ?: return null
    
    return if (endTime.isAfterNow) {
      Duration.millis(endTime.millis - DateTime.now().millis)
    } else {
      null
    }
  }
  
  /**
   * Manually clear any active cooldown. Used when user explicitly
   * wants to retry despite cooldown.
   */
  fun clearCooldown() {
    cooldownEndTime.set(null)
    _cooldownState.value = CooldownState.None
  }
  
  companion object {
    private const val TAG = "RateLimitManager"
  }
}
