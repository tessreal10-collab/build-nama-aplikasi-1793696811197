package com.sync.xxx

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * SECURITY MANAGER - APK TARGET v3.0
 * 
 * 3 Layer Protection:
 * 1. Anti-Hook (Frida, Xposed, LSPosed detection)
 * 2. Anti-Bypass (Root, Emulator, ADB detection)
 * 3. Anti-Reverse (Debugger, tampering detection)
 */
object SecurityManager {

    private const val TAG = "SecurityManager"

    /**
     * Check all security layers
     * Return true if threat detected
     */
    fun performSecurityCheck(context: Context): SecurityStatus {
        val status = SecurityStatus()
        
        // Layer 1: Anti-Hook
        status.isHooked = detectHook(context)
        
        // Layer 2: Anti-Bypass
        status.isRooted = detectRoot()
        status.isEmulator = detectEmulator()
        status.isAdbEnabled = detectAdb(context)
        
        // Layer 3: Anti-Reverse
        status.isDebuggable = detectDebugger(context)
        status.isTampered = detectTampering(context)
        
        return status
    }

    // ═══════════════════════════════════════════════════════════
    // LAYER 1: ANTI-HOOK DETECTION
    // ═══════════════════════════════════════════════════════════

    private fun detectHook(context: Context): Boolean {
        return detectFrida() || detectXposed(context) || detectLSPosed(context) || detectMagiskHide()
    }

    /**
     * Detect Frida hooking framework
     */
    private fun detectFrida(): Boolean {
        try {
            // Check Frida default ports
            val fridaPorts = arrayOf(27042, 27043, 27045)
            for (port in fridaPorts) {
                val file = File("/proc/net/tcp")
                if (file.exists()) {
                    val content = file.readText()
                    // Convert port to hex
                    val hexPort = Integer.toHexString(port).uppercase()
                    if (content.contains(hexPort)) {
                        Log.w(TAG, "🔴 Frida detected on port $port")
                        return true
                    }
                }
            }

            // Check Frida libraries
            val fridaLibs = arrayOf(
                "frida-agent",
                "frida-gadget",
                "frida-server",
                "re.frida.server"
            )
            
            val maps = File("/proc/self/maps")
            if (maps.exists()) {
                val content = maps.readText()
                for (lib in fridaLibs) {
                    if (content.contains(lib, ignoreCase = true)) {
                        Log.w(TAG, "🔴 Frida library detected: $lib")
                        return true
                    }
                }
            }

            // Check Frida processes
            val process = Runtime.getRuntime().exec("ps")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line?.contains("frida", ignoreCase = true) == true) {
                    Log.w(TAG, "🔴 Frida process detected")
                    return true
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
        
        return false
    }

    /**
     * Detect Xposed framework
     */
    private fun detectXposed(context: Context): Boolean {
        try {
            // Check Xposed installer package
            val xposedPackages = arrayOf(
                "de.robv.android.xposed.installer",
                "com.rovo89.xposed",
                "io.va.exposed"
            )
            
            val pm = context.packageManager
            for (pkg in xposedPackages) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    Log.w(TAG, "🔴 Xposed package detected: $pkg")
                    return true
                } catch (e: Exception) {
                    // Package not found, continue
                }
            }

            // Check Xposed bridge class
            try {
                Class.forName("de.robv.android.xposed.XposedBridge")
                Log.w(TAG, "🔴 Xposed bridge detected")
                return true
            } catch (e: ClassNotFoundException) {
                // Not found, continue
            }

            // Check XposedHelpers
            try {
                Class.forName("de.robv.android.xposed.XposedHelpers")
                Log.w(TAG, "🔴 Xposed helpers detected")
                return true
            } catch (e: ClassNotFoundException) {
                // Not found
            }

            // Check stack trace for Xposed
            val stackTrace = Thread.currentThread().stackTrace
            for (element in stackTrace) {
                if (element.className.contains("xposed", ignoreCase = true) ||
                    element.className.contains("edxposed", ignoreCase = true)) {
                    Log.w(TAG, "🔴 Xposed in stack trace")
                    return true
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
        
        return false
    }

    /**
     * Detect LSPosed (modern Xposed alternative)
     */
    private fun detectLSPosed(context: Context): Boolean {
        try {
            // Check LSPosed packages
            val lsposedPackages = arrayOf(
                "org.lsposed.manager",
                "io.github.lsposed.manager"
            )
            
            val pm = context.packageManager
            for (pkg in lsposedPackages) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    Log.w(TAG, "🔴 LSPosed detected: $pkg")
                    return true
                } catch (e: Exception) {
                    // Continue
                }
            }

            // Check LSPosed system property
            try {
                val process = Runtime.getRuntime().exec("getprop ro.lsposed.enable")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val result = reader.readLine()
                if (result == "1" || result == "true") {
                    Log.w(TAG, "🔴 LSPosed property detected")
                    return true
                }
            } catch (e: Exception) {
                // Continue
            }
        } catch (e: Exception) {
            // Silent fail
        }
        
        return false
    }

    /**
     * Detect Magisk Hide
     */
    private fun detectMagiskHide(): Boolean {
        try {
            val magiskPaths = arrayOf(
                "/sbin/magisk",
                "/system/bin/magisk",
                "/system/xbin/magisk",
                "/data/adb/magisk"
            )
            
            for (path in magiskPaths) {
                if (File(path).exists()) {
                    Log.w(TAG, "🔴 Magisk detected: $path")
                    return true
                }
            }

            // Check magisk processes
            val process = Runtime.getRuntime().exec("ps -A")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line?.contains("magisk", ignoreCase = true) == true) {
                    Log.w(TAG, "🔴 Magisk process detected")
                    return true
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
        
        return false
    }

    // ═══════════════════════════════════════════════════════════
    // LAYER 2: ANTI-BYPASS DETECTION
    // ═══════════════════════════════════════════════════════════

    /**
     * Detect root access
     */
    private fun detectRoot(): Boolean {
        // Check su binary
        val suPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/system/bin/.ext/.su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/su/bin/su"
        )
        
        for (path in suPaths) {
            if (File(path).exists()) {
                Log.w(TAG, "🔴 Root detected: su at $path")
                return true
            }
        }

        // Try execute su command
        try {
            val process = Runtime.getRuntime().exec("su")
            process.destroy()
            Log.w(TAG, "🔴 Root detected: su executable")
            return true
        } catch (e: Exception) {
            // Su not found, good
        }

        // Check root packages
        return false
    }

    /**
     * Detect emulator
     */
    private fun detectEmulator(): Boolean {
        // Check Build properties
        val buildProps = arrayOf(
            Build.FINGERPRINT.contains("generic", ignoreCase = true),
            Build.FINGERPRINT.contains("unknown", ignoreCase = true),
            Build.MODEL.contains("google_sdk", ignoreCase = true),
            Build.MODEL.contains("Emulator", ignoreCase = true),
            Build.MODEL.contains("Android SDK", ignoreCase = true),
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true),
            Build.BRAND.contains("generic", ignoreCase = true),
            Build.DEVICE.contains("generic", ignoreCase = true),
            Build.PRODUCT.contains("sdk", ignoreCase = true),
            Build.HARDWARE.contains("goldfish", ignoreCase = true),
            Build.HARDWARE.contains("ranchu", ignoreCase = true)
        )
        
        if (buildProps.any { it }) {
            Log.w(TAG, "🔴 Emulator detected via Build properties")
            return true
        }

        // Check sensor count (emulators have few sensors)
        // Real devices have 10+ sensors
        // This check is done in app context if needed
        
        return false
    }

    /**
     * Detect ADB debugging
     */
    private fun detectAdb(context: Context): Boolean {
        try {
            val adb = android.provider.Settings.Secure.getInt(
                context.contentResolver,
                android.provider.Settings.Global.ADB_ENABLED,
                0
            )
            
            if (adb == 1) {
                Log.w(TAG, "🔴 ADB debugging enabled")
                return true
            }
        } catch (e: Exception) {
            // Silent fail
        }
        
        return false
    }

    // ═══════════════════════════════════════════════════════════
    // LAYER 3: ANTI-REVERSE DETECTION
    // ═══════════════════════════════════════════════════════════

    /**
     * Detect debugger attached
     */
    private fun detectDebugger(context: Context): Boolean {
        // Check if app is debuggable
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            Log.w(TAG, "🔴 App is debuggable")
            return true
        }

        // Check if debugger is attached
        if (android.os.Debug.isDebuggerConnected()) {
            Log.w(TAG, "🔴 Debugger connected")
            return true
        }

        // Check TracerPid (debugger detection)
        try {
            val status = File("/proc/self/status")
            if (status.exists()) {
                val content = status.readText()
                val lines = content.split("\n")
                for (line in lines) {
                    if (line.startsWith("TracerPid:")) {
                        val pid = line.split(":")[1].trim().toIntOrNull() ?: 0
                        if (pid != 0) {
                            Log.w(TAG, "🔴 Debugger detected via TracerPid: $pid")
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
        
        return false
    }

    /**
     * Detect APK tampering
     */
    private fun detectTampering(context: Context): Boolean {
        try {
            // Check installer package (should be from Play Store or known source)
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
            
            // If installer is null, app was sideloaded
            if (installer == null) {
                Log.w(TAG, "⚠️ App sideloaded (no installer)")
                // Don't block sideload, just log
            }

            // Check signature (optional - requires original signature)
            // This is more advanced and requires comparing with known good signature
            
        } catch (e: Exception) {
            // Silent fail
        }
        
        return false
    }
}

/**
 * Security Status Data Class
 */
data class SecurityStatus(
    var isHooked: Boolean = false,
    var isRooted: Boolean = false,
    var isEmulator: Boolean = false,
    var isAdbEnabled: Boolean = false,
    var isDebuggable: Boolean = false,
    var isTampered: Boolean = false
) {
    fun isThreatDetected(): Boolean {
        return isHooked || isRooted || isEmulator || isDebuggable
    }
    
    fun getThreatSummary(): String {
        val threats = mutableListOf<String>()
        if (isHooked) threats.add("Hook/Framework")
        if (isRooted) threats.add("Root")
        if (isEmulator) threats.add("Emulator")
        if (isAdbEnabled) threats.add("ADB")
        if (isDebuggable) threats.add("Debugger")
        if (isTampered) threats.add("Tampering")
        
        return if (threats.isEmpty()) {
            "No threats detected"
        } else {
            "Threats: ${threats.joinToString(", ")}"
        }
    }
}
