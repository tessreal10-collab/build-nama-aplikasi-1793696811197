package com.sync.xxx

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Satu-satunya jalur consent MediaProjection di seluruh app.
 *
 * Dulu ada dua: activity ini, dan sebuah BroadcastReceiver di MainActivity yang juga
 * memanggil createScreenCaptureIntent(). Keduanya bisa hidup bersamaan, dan tiap
 * dialog yang di-ACC memasang VirtualDisplay baru pada layar yang sama. Beberapa
 * konsumen permukaan sekaligus membuat compositor kehabisan jalur - hasilnya frame
 * beku pada percobaan pertama. Receiver itu sudah dihapus; activity ini sekarang
 * satu-satunya yang meminta.
 *
 * Guard di bawah menutup jalur sisa: watchdog DeviceService, ack panel, dan tap
 * operator bisa menembak hampir bersamaan. Hanya satu dialog yang boleh hidup pada
 * satu waktu; sisanya no-op, bukan tumpukan activity.
 */
class ScreenCaptureActivity : Activity() {

    companion object {
        private const val TAG = "ScreenCaptureActivity"
        private const val REQ_SCREEN = 1001
        const val ACTION_PHOTO_RESULT = "com.sync.xxx.PHOTO_RESULT"

        /**
         * Volatile, dan dipegang sampai onActivityResult.
         *
         * requestCode/receiver yang datang pada saat ini di-drop. System dialog ini
         * modal dan tidak bisa dibatalkan secara programatik, jadi larangan masuk
         * adalah satu-satunya kontrol yang tersedia.
         */
        @Volatile
        private var consentInFlight = false
    }

    private var isPhotoMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (consentInFlight) {
            Log.d(TAG, "consent sudah di layar - permintaan kedua diabaikan")
            finish()
            return
        }
        consentInFlight = true

        isPhotoMode = intent.getBooleanExtra("isPhotoMode", false)

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_SCREEN)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_SCREEN) {
            consentInFlight = false

            // data hanya boleh dipakai sekali pada Android 14+, dan startScreenCapture()
            // memegang salinan yang dipakai ulang saat reattach. Salinan di sini harus
            // clone, bukan objek yang sama, supaya pelepasan di satu sisi tidak merusak
            // sisi lain.
            val payload = data?.let { Intent(it) }

            val action = if (isPhotoMode) ACTION_PHOTO_RESULT else DeviceService.ACTION_SCREEN_RESULT
            val out = Intent(action).apply {
                putExtra(DeviceService.EXTRA_RESULT_CODE, resultCode)
                putExtra(DeviceService.EXTRA_RESULT_DATA, payload)
                setPackage(packageName)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
            }
            sendBroadcast(out)
            finish()
        } else {
            consentInFlight = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Jaring pengaman: kalau activity dibunuh sebelum hasil kembali, guard harus
        // lepas, kalau tidak feature ini mati sampai proses restart.
        if (isFinishing) consentInFlight = false
    }
}
