package com.github.k1rakishou.chan.core.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.MpvSettings
import com.github.k1rakishou.chan.core.mpv.MPVLib.mpvFormat.MPV_FORMAT_FLAG
import com.github.k1rakishou.chan.core.mpv.MPVLib.mpvFormat.MPV_FORMAT_INT64
import com.github.k1rakishou.chan.core.mpv.MPVLib.mpvFormat.MPV_FORMAT_NONE
import com.github.k1rakishou.chan.core.mpv.MPVLib.mpvFormat.MPV_FORMAT_STRING
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.common.DoNotStrip
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.util.ChanPostUtils
import java.io.File
import kotlin.reflect.KProperty

/**
 * Taken from https://github.com/mpv-android/mpv-android
 *
 * DO NOT RENAME!
 * DO NOT MOVE!
 * NATIVE LIBRARIES DEPEND ON THE CLASS PACKAGE!
 * */

@DoNotStrip
class MPVView(
    context: Context,
    attrs: AttributeSet?
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
    private var filePath: String? = null
    private var surfaceAttached = false
    private var _initialized = false
    private var pendingLoadRunnable: Runnable? = null

    val initialized: Boolean
        get() = _initialized

    fun create(applicationContext: Context, appConstants: AppConstants) {
        if (!MPVLib.librariesAreLoaded()) {
            Logger.d(TAG, "create() librariesAreLoaded: false")
            _initialized = false
            return
        }

        if (MPVLib.isCreated()) {
            return
        }

        Logger.d(TAG, "create()")

        try {
            MPVLib.mpvCreate(applicationContext)
            setupMpvConf(applicationContext)

            // hwdec
            val hwdec = if (MpvSettings.hardwareDecoding.get()) {
                "mediacodec-copy"
            } else {
                "no"
            }

            Logger.d(TAG, "initOptions() hwdec: $hwdec")

            // vo: set display fps as reported by android
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val wm = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val disp = wm.defaultDisplay
                val refreshRate = disp.mode.refreshRate

                Logger.d(TAG, "Display ${disp.displayId} reports FPS of $refreshRate")
                MPVLib.mpvSetOptionString("override-display-fps", refreshRate.toString())
            } else {
                Logger.d(TAG, "Android version too old, disabling refresh rate functionality " +
                  "(${Build.VERSION.SDK_INT} < ${Build.VERSION_CODES.M})")
            }

            MPVLib.mpvSetOptionString("video-sync", "audio")
            MPVLib.mpvSetOptionString("interpolation", "no")

            reloadFastVideoDecodeOption()
            reloadVideoLoopOption()

            MPVLib.mpvSetOptionString("vo", "gpu")
            MPVLib.mpvSetOptionString("gpu-context", "android")
            
            // CRITICAL: EGL context configuration for broad Android device compatibility
            // Addresses EGL_BAD_ATTRIBUTE errors on Samsung devices, BlueStacks, and other Android platforms
            MPVLib.mpvSetOptionString("opengl-es", "yes")
            MPVLib.mpvSetOptionString("gpu-api", "opengl")
            
            // Conservative OpenGL ES settings for maximum device compatibility
            // These settings ensure compatibility with older GPU drivers and various Android implementations
            MPVLib.mpvSetOptionString("opengl-es-version", "2")  // Force ES 2.0 for compatibility
            MPVLib.mpvSetOptionString("opengl-glsl-version", "")  // Let driver choose GLSL version
            MPVLib.mpvSetOptionString("opengl-early-flush", "no")  // Disable early flush for stability
            MPVLib.mpvSetOptionString("opengl-fence-timeout", "1000000")  // 1 second timeout
            
            // Disable advanced OpenGL features that may cause EGL context issues
            MPVLib.mpvSetOptionString("opengl-debug", "no")
            MPVLib.mpvSetOptionString("gpu-debug", "no")
            MPVLib.mpvSetOptionString("opengl-dumb-mode", "yes")  // Use simpler rendering path
            
            // Samsung and modern Android device optimizations
            MPVLib.mpvSetOptionString("gpu-context", "android")  // Ensure Android EGL context
            MPVLib.mpvSetOptionString("android-surface-size", "")  // Let MPV auto-detect surface size
            
            // Fallback video output options in case GPU rendering fails
            // This provides a fallback chain: gpu -> mediacodec -> wlshm -> x11 -> null
            MPVLib.mpvSetOptionString("vo", "gpu,mediacodec,wlshm,x11")
            
            MPVLib.mpvSetOptionString("hwdec", hwdec)
            MPVLib.mpvSetOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            MPVLib.mpvSetOptionString("ao", "audiotrack,opensles")

            val mpvCertFile = File(appConstants.mpvCertDir, AppConstants.MPV_CERTIFICATE_FILE_NAME)
            MPVLib.mpvSetOptionString("tls-verify", "yes")
            MPVLib.mpvSetOptionString("tls-ca-file", mpvCertFile.path)

            MPVLib.mpvSetOptionString("input-default-bindings", "yes")

            Logger.d(TAG, "initOptions() mpvDemuxerCacheMaxSize: ${ChanPostUtils.getReadableFileSize(appConstants.mpvDemuxerCacheMaxSize)}")
            MPVLib.mpvSetOptionString("demuxer-max-bytes", "${appConstants.mpvDemuxerCacheMaxSize}")
            MPVLib.mpvSetOptionString("demuxer-max-back-bytes", "${appConstants.mpvDemuxerCacheMaxSize}")

            MPVLib.mpvInit()
            
            // Apply Surface lifecycle synchronization fix to prevent WindowID race conditions
            // Based on official MPV Android implementation Surface handling patterns
            
            // certain options are hardcoded:
            MPVLib.mpvSetOptionString("save-position-on-quit", "no")
            MPVLib.mpvSetOptionString("force-window", "no")
            muteUnmute(true)

            surfaceTextureListener = this
            observeProperties()

            _initialized = true
        } catch (error: Throwable) {
            Logger.e(TAG, "Failed to create MPV instance", error)
            _initialized = false
            // Ensure cleanup on failure
            try {
                if (MPVLib.isCreated()) {
                    MPVLib.mpvDestroy()
                }
            } catch (cleanupError: Throwable) {
                Logger.e(TAG, "Failed to cleanup failed MPV creation", cleanupError)
            }
        }
    }

    private fun setupMpvConf(applicationContext: Context) {
        if (!ChanSettings.mpvUseConfigFile.get()) {
            MPVLib.mpvSetPropertyString("config", "no")
            return
        }

        val mpvconfDir = File(applicationContext.filesDir, MPV_CONF_DIR)
        val mpvconfFile = File(mpvconfDir, MPV_CONF_FILE)

        if (!mpvconfFile.exists() || mpvconfFile.length() <= 0) {
            Logger.d(TAG, "initOptions() mpv.conf doesn't exist or empty")

            MPVLib.mpvSetPropertyString("config", "no")
        } else {
            Logger.d(TAG, "initOptions() using mpv.conf")

            MPVLib.mpvSetPropertyString("config", "yes")
            MPVLib.mpvSetPropertyString("config-dir", mpvconfDir.absolutePath)
        }
    }

    fun destroy() {
        if (!MPVLib.librariesAreLoaded()) {
            Logger.d(TAG, "destroy() librariesAreLoaded: false")
            _initialized = false
            return
        }

        if (!MPVLib.isCreated()) {
            return
        }

        Logger.d(TAG, "destroy()")

        // CRASH PREVENTION: Cancel any pending operations before destroying
        pendingLoadRunnable?.let { removeCallbacks(it) }
        pendingLoadRunnable = null
        this.filePath = null

        // Disable surface callbacks to avoid using unintialized mpv state
        surfaceTextureListener = null
        
        try {
            MPVLib.mpvDestroy()
        } catch (e: Exception) {
            Logger.e(TAG, "Error during MPV destroy", e)
        }

        _initialized = false
    }

    fun reloadFastVideoDecodeOption() {
        if (!MPVLib.librariesAreLoaded()) {
            Logger.d(TAG, "reloadFastVideoDecodeOption() librariesAreLoaded: false")
            return
        }

        if (MpvSettings.videoFastCode.get()) {
            Logger.d(TAG, "initOptions() videoFastCode: true")

            MPVLib.mpvSetOptionString("vd-lavc-fast", "yes")
            MPVLib.mpvSetOptionString("vd-lavc-skiploopfilter", "nonkey")
        } else {
            Logger.d(TAG, "initOptions() videoFastCode: false")

            MPVLib.mpvSetOptionString("vd-lavc-fast", "null")
            MPVLib.mpvSetOptionString("vd-lavc-skiploopfilter", "null")
        }
    }

    fun reloadVideoLoopOption() {
        if (!MPVLib.librariesAreLoaded()) {
            Logger.d(TAG, "reloadVideoLoopOption() librariesAreLoaded: false")
            return
        }

        if (ChanSettings.videoAutoLoop.get()) {
            MPVLib.mpvSetOptionString("loop-file", "inf")
        } else {
            MPVLib.mpvSetOptionString("loop-file", "no")
        }
    }

    fun playFile(filePath: String) {
        Logger.d(TAG, "playFile called: $filePath")
        
        if (!MPVLib.librariesAreLoaded()) {
            Logger.d(TAG, "playFile() librariesAreLoaded: false")
            return
        }

        if (!MPVLib.isCreated()) {
            Logger.w(TAG, "Cannot play file: MPV not created")
            return
        }

        // CRITICAL: Always queue files to prevent WindowID race conditions
        // Even if surface appears attached, WindowID may not be ready for new video output
        Logger.d(TAG, "Queuing file for safe loading (prevents WindowID assertion): $filePath")
        
        // CRASH PREVENTION: Clear any existing queued file first to prevent conflicts during rapid swiping
        this.filePath = null
        
        // CRASH PREVENTION: Cancel any pending load operations to prevent race conditions
        pendingLoadRunnable?.let { removeCallbacks(it) }
        
        // Add small delay to prevent rapid-fire file switching crashes
        pendingLoadRunnable = Runnable {
            this.filePath = filePath
            
            // If surface is attached, trigger immediate loading through surface flow
            if (surfaceAttached) {
                Logger.d(TAG, "Surface attached, posting delayed load to ensure WindowID is ready")
                // Post to next frame to ensure Surface WindowID is fully initialized
                post {
                    loadQueuedFileIfReady()
                }
            }
        }
        postDelayed(pendingLoadRunnable, 50) // 50ms delay to prevent rapid switching crashes
    }
    
    private fun loadQueuedFileIfReady() {
        Logger.d(TAG, "loadQueuedFileIfReady() called, surfaceAttached=$surfaceAttached, filePath=$filePath")
        
        if (!surfaceAttached) {
            Logger.d(TAG, "Surface not attached, keeping file queued")
            return
        }
        
        if (!MPVLib.isCreated()) {
            Logger.w(TAG, "MPV not created, cannot load file")
            return
        }
        
        val fileToLoad = filePath
        if (fileToLoad == null) {
            Logger.d(TAG, "No queued file to load")
            return
        }
        
        // Clear immediately to prevent double-loading
        filePath = null
        
        try {
            // CRASH PREVENTION: Add safety checks before MPV commands
            if (!MPVLib.librariesAreLoaded()) {
                Logger.e(TAG, "Libraries not loaded, cannot execute loadfile command")
                return
            }
            
            Logger.d(TAG, "Loading queued file with WindowID safety: $fileToLoad")
            
            // CRASH PREVENTION: Stop any currently playing file first to prevent conflicts
            try {
                MPVLib.mpvCommand(arrayOf("stop"))
                Logger.d(TAG, "Stopped previous playback for safe file switching")
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to stop previous playback (may not be playing): ${e.message}")
            }
            
            // Brief pause to ensure stop command is processed
            Thread.sleep(10)
            
            MPVLib.mpvCommand(arrayOf("loadfile", fileToLoad))
            Logger.d(TAG, "File load command executed successfully")
        } catch (e: Exception) {
            Logger.e(TAG, "Error loading queued file: $fileToLoad", e)
            
            // CRASH PREVENTION: If command fails, try to reset MPV state safely
            try {
                Logger.w(TAG, "Attempting to reset MPV state after load failure")
                MPVLib.mpvSetPropertyString("vo", "null")
                postDelayed({
                    if (surfaceAttached && MPVLib.isCreated()) {
                        MPVLib.mpvSetPropertyString("vo", "gpu,mediacodec,wlshm,x11")
                        Logger.d(TAG, "MPV state reset completed")
                    }
                }, 100)
            } catch (resetError: Exception) {
                Logger.e(TAG, "Failed to reset MPV state", resetError)
            }
        }
    }

    private fun observeProperties() {
        // This observes all properties needed by MPVView or MPVActivity
        data class Property(val name: String, val format: Int)
        val p = arrayOf(
            Property("time-pos", MPV_FORMAT_INT64),
            Property("demuxer-cache-duration", MPV_FORMAT_INT64),
            Property("duration", MPV_FORMAT_INT64),
            Property("pause", MPV_FORMAT_FLAG),
            Property("audio", MPV_FORMAT_FLAG),
            Property("mute", MPV_FORMAT_STRING),
            Property("video-params", MPV_FORMAT_NONE),
            Property("video-format", MPV_FORMAT_NONE),
        )

        for ((name, format) in p) {
            MPVLib.observeProperty(name, format)
        }
    }

    fun addObserver(o: MPVLib.EventObserver) {
        MPVLib.addObserver(o)
    }
    fun removeObserver(o: MPVLib.EventObserver) {
        MPVLib.removeObserver(o)
    }

    // Property getters/setters

    var paused: Boolean?
        get() = MPVLib.mpvGetPropertyBoolean("pause")
        set(paused) = MPVLib.mpvSetPropertyBoolean("pause", paused!!)

    val duration: Int?
        get() = MPVLib.mpvGetPropertyInt("duration")

    val demuxerCacheDuration: Int?
        get() = MPVLib.mpvGetPropertyInt("demuxer-cache-duration")

    var timePos: Int?
        get() = MPVLib.mpvGetPropertyInt("time-pos")
        set(progress) = MPVLib.mpvSetPropertyInt("time-pos", progress!!)

    val hwdecActive: Boolean
        get() = (MPVLib.mpvGetPropertyString("hwdec-current") ?: "no") != "no"

    var playbackSpeed: Double?
        get() = MPVLib.mpvGetPropertyDouble("speed")
        set(speed) = MPVLib.mpvSetPropertyDouble("speed", speed!!)

    val filename: String?
        get() = MPVLib.mpvGetPropertyString("filename")

    val avsync: String?
        get() = MPVLib.mpvGetPropertyString("avsync")

    val decoderFrameDropCount: Int?
        get() = MPVLib.mpvGetPropertyInt("decoder-frame-drop-count")

    val frameDropCount: Int?
        get() = MPVLib.mpvGetPropertyInt("frame-drop-count")

    val containerFps: Double?
        get() = MPVLib.mpvGetPropertyDouble("container-fps")

    val estimatedVfFps: Double?
        get() = MPVLib.mpvGetPropertyDouble("estimated-vf-fps")

    val videoW: Int?
        get() = MPVLib.mpvGetPropertyInt("video-params/w")

    val videoH: Int?
        get() = MPVLib.mpvGetPropertyInt("video-params/h")

    val videoAspect: Double?
        get() = MPVLib.mpvGetPropertyDouble("video-params/aspect")

    val videoCodec: String?
        get() = MPVLib.mpvGetPropertyString("video-codec")

    val audioCodec: String?
        get() = MPVLib.mpvGetPropertyString("audio-codec")

    val audioSampleRate: Int?
        get() = MPVLib.mpvGetPropertyInt("audio-params/samplerate")

    val audioChannels: Int?
        get() = MPVLib.mpvGetPropertyInt("audio-params/channel-count")

    class TrackDelegate {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Int {
            val v = MPVLib.mpvGetPropertyString(property.name)
            // we can get null here for "no" or other invalid value
            return v?.toIntOrNull() ?: -1
        }
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            if (value == -1)
                MPVLib.mpvSetPropertyString(property.name, "no")
            else
                MPVLib.mpvSetPropertyInt(property.name, value)
        }
    }

    var vid: Int by TrackDelegate()
    var sid: Int by TrackDelegate()
    var aid: Int by TrackDelegate()

    // Commands

    fun cyclePause() = MPVLib.mpvCommand(arrayOf("cycle", "pause"))

    val isMuted: Boolean
        get() = MPVLib.mpvGetPropertyString("mute") != "no"

    fun muteUnmute(mute: Boolean) {
        if (mute) {
            MPVLib.mpvSetPropertyString("mute", "yes")
        } else {
            MPVLib.mpvSetPropertyString("mute", "no")
        }
    }

    fun cycleHwdec() = MPVLib.mpvCommand(arrayOf("cycle-values", "hwdec", "mediacodec-copy", "no"))

    fun cycleSpeed() {
        val speeds = arrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
        val currentSpeed = playbackSpeed ?: 1.0
        val index = speeds.indexOfFirst { it > currentSpeed }
        playbackSpeed = speeds[if (index == -1) 0 else index]
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        Logger.d(TAG, "onSurfaceTextureAvailable called: ${width}x$height")

        if (!MPVLib.isCreated()) {
            Logger.w(TAG, "Cannot attach surface: MPV not created")
            return
        }

        try {
            // CRITICAL: Mark surface as attached FIRST to prevent race condition
            // This must happen before any MPV operations that might trigger video output
            surfaceAttached = true
            Logger.d(TAG, "marked surface as attached, proceeding with MPV setup")
            
            // Set surface size immediately to ensure proper initialization
            MPVLib.mpvSetPropertyString("android-surface-size", "${width}x$height")
            
            // Attach surface immediately without delay - race condition fix from official MPV implementation
            MPVLib.mpvAttachSurface(Surface(surfaceTexture))
            Logger.d(TAG, "Surface attached successfully")
            
            // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
            MPVLib.mpvSetOptionString("force-window", "yes")
            
            Logger.d(TAG, "surface attachment complete, checking for queued file")

            // Only NOW load the file when surface is confirmed attached
            // Use the new safe loading method
            loadQueuedFileIfReady()
            
            // We disable video output when the context disappears, enable it back
            MPVLib.mpvSetPropertyString("vo", "gpu,mediacodec,wlshm,x11")
            Logger.d(TAG, "video output re-enabled with fallback options")
        } catch (error: Throwable) {
            Logger.e(TAG, "Failed to attach surface to MPV: ${error.message}", error)
            
            // Try to determine if this is an EGL context issue
            val errorMessage = error.message ?: ""
            if (errorMessage.contains("EGL") || errorMessage.contains("context") || errorMessage.contains("OpenGL")) {
                Logger.e(TAG, "EGL context creation failed - attempting fallback video output")
                try {
                    // Try fallback to software rendering
                    MPVLib.mpvSetPropertyString("vo", "mediacodec")
                    Logger.d(TAG, "Switched to mediacodec fallback video output")
                } catch (fallbackError: Throwable) {
                    Logger.e(TAG, "Fallback video output also failed", fallbackError)
                }
            }
            
            surfaceAttached = false
        }
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        Logger.d(TAG, "detaching surface")

        if (!MPVLib.isCreated()) {
            Logger.w(TAG, "Cannot detach surface: MPV not created")
            surfaceAttached = false
            return true
        }

        try {
            // Follow official MPV pattern: disable vo first, then force-window, then detach
            MPVLib.mpvSetPropertyString("vo", "null")
            MPVLib.mpvSetOptionString("force-window", "no")
            
            // Wait briefly to ensure vo is disabled before detaching surface
            // This prevents the race condition where detachSurface happens before vo deinit
            Thread.sleep(10)
            
            MPVLib.mpvDetachSurface()
            Logger.d(TAG, "surface detached successfully")
        } catch (error: Throwable) {
            Logger.e(TAG, "Failed to detach surface from MPV", error)
        }
        
        surfaceAttached = false
        return true
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        // Ensure surface size is properly synchronized with MPV
        if (MPVLib.isCreated() && surfaceAttached) {
            MPVLib.mpvSetPropertyString("android-surface-size", "${width}x$height")
        }
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
    }

    companion object {
        private const val TAG = "MPVView"

        const val MPV_CONF_DIR = "mpvconf"
        const val MPV_CONF_FILE = "mpv.conf"
    }
}
