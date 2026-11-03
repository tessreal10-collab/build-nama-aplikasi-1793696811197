package com.sync.xxx

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager

class SosOverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.sync.xxx.SOS_OVERLAY_SHOW"
        const val ACTION_HIDE = "com.sync.xxx.SOS_OVERLAY_HIDE"
        private const val SOS_VIDEO_URL = "https://videotourl.com/videos/1783477385805-faaca8d6-7b47-42f7-9488-af98fc4a1af9.mp4"
    }

    private var windowManager: WindowManager? = null
    private var textureView: TextureView? = null
    private var videoPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SHOW -> mainHandler.post { showOverlay() }
                ACTION_HIDE -> mainHandler.post { hideOverlay() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW)
            addAction(ACTION_HIDE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun showOverlay() {
        hideOverlay()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )

        try {
            val tv = TextureView(this)

            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                    startVideo(Surface(surface))
                }
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }

            textureView = tv
            windowManager?.addView(tv, params)

            @Suppress("DEPRECATION")
            tv.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        } catch (e: Exception) {
            Log.e("SosOverlay", "showOverlay: ${e.message}")
        }
    }

    private fun startVideo(surface: Surface) {
        try {
            videoPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, Uri.parse(SOS_VIDEO_URL))
                setSurface(surface)
                isLooping = true
                setVolume(1f, 1f) // Full volume for SOS
                setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                setOnPreparedListener { 
                    start()
                    Log.d("SosOverlay", "SOS video started")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("SosOverlay", "ERROR video: what=$what extra=$extra")
                    false
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("SosOverlay", "startVideo: ${e.message}")
        }
    }

    private fun hideOverlay() {
        try { videoPlayer?.stop() } catch (_: Exception) {}
        try { videoPlayer?.release() } catch (_: Exception) {}
        videoPlayer = null

        textureView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        textureView = null
        
        Log.d("SosOverlay", "SOS overlay hidden")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
