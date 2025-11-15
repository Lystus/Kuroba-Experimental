package com.github.k1rakishou.chan.features.thread_downloading

/**
 * Decision about whether a thread download should be completed or continue running.
 */
sealed class CompletionDecision {
  /**
   * Thread download should be marked as Completed with success status.
   */
  data class Complete(val reason: String) : CompletionDecision()

  /**
   * Thread download should be marked as Completed but with a warning message.
   * Used when some media could not be downloaded but continuing is pointless.
   */
  data class CompleteWithWarning(val reason: String) : CompletionDecision()

  /**
   * Thread download should continue Running.
   */
  data class KeepRunning(val reason: String) : CompletionDecision()

  fun shouldComplete(): Boolean = this is Complete || this is CompleteWithWarning

  fun getCompletionMessage(): String? {
    return when (this) {
      is Complete -> reason
      is CompleteWithWarning -> reason
      is KeepRunning -> null
    }
  }
}
