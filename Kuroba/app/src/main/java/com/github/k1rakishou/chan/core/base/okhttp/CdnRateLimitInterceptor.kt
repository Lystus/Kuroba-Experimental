package com.github.k1rakishou.chan.core.base.okhttp

import com.github.k1rakishou.chan.core.manager.RateLimitManager
import com.github.k1rakishou.chan.core.site.SiteResolver
import com.github.k1rakishou.core_logger.Logger
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp network interceptor that detects CDN 429 (Too Many Requests) responses
 * and sets a site-level cooldown via [RateLimitManager].
 *
 * This closes the feedback loop between the browsing/prefetch path and the rate limit
 * system — without this, CDN 429s from image loading are silently ignored and the app
 * keeps firing requests that get rejected.
 */
class CdnRateLimitInterceptor(
  private val siteResolver: SiteResolver,
  private val rateLimitManager: RateLimitManager
) : Interceptor {

  override fun intercept(chain: Interceptor.Chain): Response {
    val response = chain.proceed(chain.request())

    if (response.code == 429) {
      val url = chain.request().url.toString()
      val site = siteResolver.findSiteForUrl(url)

      if (site != null) {
        val siteDescriptor = site.siteDescriptor()
        val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: DEFAULT_CDN_COOLDOWN_SECONDS

        Logger.w(TAG, "CDN 429 detected for site=${siteDescriptor.siteName}, " +
          "url=${chain.request().url.host}, Retry-After=${retryAfter}s")

        rateLimitManager.setCooldown(siteDescriptor, retryAfter)
      }
    }

    return response
  }

  companion object {
    private const val TAG = "CdnRateLimitInterceptor"
    private const val DEFAULT_CDN_COOLDOWN_SECONDS = 60
  }
}
