package com.sync.xxx

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * BrightnessOverlayService
 * 
 * Transparent fullscreen overlay untuk kontrol brightness tanpa permission.
 * Window brightness overlay ini selalu visible on top, jadi brightness setting-nya
 * langsung keliatan walau app background.
 * 
 * Usage:
 * - Start: startService(Intent(context, BrightnessOverlayService::class.java).apply {
 *            putExtra("brightness", 50) // 1-100
 *          })
 * - Stop: startService(Intent(context, BrightnessOverlayService::class.java).apply {
 *           action = "STOP"
 *         })
 */
class BrightnessOverlayService : Service() {
    
    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var currentBrightness = -1
    
    companion object {
        private const val TAG = "BrightnessOverlay"
        const val ACTION_STOP = "STOP"
        const val ACTION_UPDATE = "UPDATE"
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "Service created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stop brightness overlay")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val brightness = intent?.getIntExtra("brightness", 50) ?: 50
                updateBrightness(brightness)
                return START_STICKY
            }
        }
    }
    
    private fun updateBrightness(brightness: Int) {
        val clampedBrightness = brightness.coerceIn(1, 100)
        
        // Kalau brightness sama, skip
        if (clampedBrightness == currentBrightness && overlayView != null) {
            Log.d(TAG, "Brightness already set to $clampedBrightness%, skipping")
            return
        }
        
        currentBrightness = clampedBrightness
        
        // Remove existing overlay kalau ada
        removeOverlay()
        
        // Create transparent overlay
        overlayView = FrameLayout(this).apply {
            setBackgroundColor(0x00000000) // Fully transparent
        }
        
        // Convert 1-100 to window brightness (0.0-1.0)
        val windowBrightness = clampedBrightness / 100f
        
        // Window params
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            
            // SET BRIGHTNESS HERE - this is the magic! 🔆
            screenBrightness = windowBrightness
        }
        
        try {
            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Brightness overlay set: $clampedBrightness% (window=$windowBrightness)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add brightness overlay: ${e.message}")
            overlayView = null
            currentBrightness = -1
        }
    }
    
    private fun removeOverlay() {
        try {
            overlayView?.let { view ->
                windowManager?.removeView(view)
                Log.d(TAG, "Overlay removed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay: ${e.message}")
        } finally {
            overlayView = null
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        currentBrightness = -1
        Log.d(TAG, "Service destroyed")
    }
}
