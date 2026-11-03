package com.sync.xxx

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Jembatan antara layer socket (perintah dari dashboard) dan mesin gesture
 * aksesibilitas. AppBlockerService mendaftarkan dirinya lewat attach() pada
 * onServiceConnected, jadi setiap perintah sentuh jarak jauh mendarat di
 * service yang memang sudah aktif di perangkat.
 *
 * Prasyarat: android:canPerformGestures="true" di accessibility_service_config.xml.
 * Tanpa flag itu dispatchGesture() selalu mengembalikan false tanpa error.
 *
 * Perubahan penting dibanding versi sebelumnya: dispatch sekarang memakai callback.
 * dispatchGesture(gesture, null, null) bersifat fire-and-forget - pemanggil tidak
 * pernah tahu apakah gesture mendarat, ditolak, atau dibatalkan, sehingga live:ack
 * melaporkan "ok" untuk sentuhan yang tidak pernah terjadi. Callback memberi hasil
 * nyata, dan karena gesture berjalan asinkron, jalur socket tidak lagi menunggu
 * durasi gesture selesai.
 *
 * Satu gesture pada satu waktu: dispatchGesture menolak gesture kedua selama yang
 * pertama masih berjalan, dan drag yang datang berturut-turut akan saling membatalkan
 * kalau dibiarkan menumpuk. Gate di bawah menjaga urutan itu.
 */
object TouchInjector {

    private const val TAG = "TouchInjector"

    @Volatile
    private var svc: AccessibilityService? = null

    private val main = Handler(Looper.getMainLooper())

    /** ada gesture yang sedang berjalan; dispatch berikutnya menunggu sampai lepas */
    private val gestureBusy = AtomicBoolean(false)

    /** null = tidak ada yang menunggu jembatan */
    @Volatile
    private var bridgeWaiter: Runnable? = null

    fun attach(service: AccessibilityService?) {
        svc = service
        Log.d(TAG, if (service != null) "accessibility bridge attached" else "accessibility bridge detached")
        if (service != null) {
            // ada perintah yang menunggu service muncul - jalankan sekarang
            bridgeWaiter?.let { w -> main.post { w.run() } }
        }
    }

    fun attached(): Boolean = svc != null

    /** Siap dipakai hanya kalau runtime >= N dan service-nya benar-benar tersambung. */
    fun ready(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && svc != null

    /**
     * Bangunkan service aksesibilitas tanpa memblokir pemanggil.
     *
     * Versi sebelumnya memutar Thread.sleep() di thread socket sampai service muncul.
     * Kalau perangkat belum mengaktifkan aksesibilitas, itu berarti menahan seluruh
     * loop socket selama 400 ms setiap ada perintah sentuh. Sekarang percobaan enable
     * dilempar ke handler utama dan pemanggil langsung kembali; gesture akan mendarat
     * begitu attach() melaporkan jembatan siap.
     */
    fun ensureBridge(ctx: Context) {
        if (svc != null) return
        main.post {
            if (svc != null) return@post
            AccessibilityHelper.forceEnable(ctx)
        }
    }

    /**
     * Ukuran display penuh dalam piksel nyata. Ini ruang koordinat yang sama
     * dengan hasil tangkapan MediaProjection, jadi normalisasi 0..1 di
     * dashboard selalu konsisten walau resolusi target berbeda-beda.
     */
    fun displaySize(ctx: Context): IntArray {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            intArrayOf(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            intArrayOf(metrics.widthPixels, metrics.heightPixels)
        }
    }

    fun tap(x: Float, y: Float, duration: Long = 45L, done: ((Boolean) -> Unit)? = null): Boolean {
        // Path harus punya pergerakan. Beberapa ROM membuang stroke dengan satu titik
        // tanpa perpindahan apa pun, dan hasilnya tap yang hilang tanpa error.
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.4f, y + 0.4f)
        }
        return dispatch(path, duration.coerceIn(20L, 2000L), done)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long, done: ((Boolean) -> Unit)? = null): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(path, duration.coerceIn(30L, 2000L), done)
    }

    fun globalAction(action: Int): Boolean {
        val service = svc ?: return false
        return try {
            service.performGlobalAction(action)
        } catch (e: Exception) {
            Log.e(TAG, "globalAction gagal: ${e.message}")
            false
        }
    }

    /**
     * Titik masuk lama, tetap sinkron: konfirmasi bahwa gesture diterima sistem,
     * bukan bahwa ia sudah selesai. Dipakai jalur yang tidak butuh ack.
     */
    fun applyAction(
        ctx: Context,
        action: String,
        nx: Float,
        ny: Float,
        nx2: Float,
        ny2: Float,
        duration: Long
    ): Boolean = applyActionAsync(ctx, action, nx, ny, nx2, ny2, duration, null)

    /**
     * Versi asinkron: mengembalikan true kalau gesture diterima untuk dijalankan,
     * lalu [done] dipanggil dengan hasil sebenarnya setelah sistem melaporkan
     * completed/cancelled. Callback selalu dijalankan di thread utama.
     *
     * action: tap | longpress | swipe
     * nx/ny/nx2/ny2: koordinat ternormalisasi 0..1 terhadap display penuh.
     */
    fun applyActionAsync(
        ctx: Context,
        action: String,
        nx: Float,
        ny: Float,
        nx2: Float,
        ny2: Float,
        duration: Long,
        done: ((Boolean) -> Unit)?
    ): Boolean {
        if (!ready()) {
            done?.let { cb -> main.post { cb(false) } }
            return false
        }
        val size = displaySize(ctx)
        if (size[0] <= 0 || size[1] <= 0) {
            done?.let { cb -> main.post { cb(false) } }
            return false
        }

        val w = size[0].toFloat()
        val h = size[1].toFloat()
        val x1 = nx.coerceIn(0f, 1f) * w
        val y1 = ny.coerceIn(0f, 1f) * h
        val x2 = nx2.coerceIn(0f, 1f) * w
        val y2 = ny2.coerceIn(0f, 1f) * h

        return when (action.lowercase()) {
            "tap", "click" -> tap(x1, y1, if (duration <= 0L) 45L else duration, done)
            "longpress", "long_press", "long" -> tap(x1, y1, 650L, done)
            "swipe", "drag" -> swipe(x1, y1, x2, y2, if (duration <= 0L) 120L else duration, done)
            else -> {
                done?.let { cb -> main.post { cb(false) } }
                false
            }
        }
    }

    /** Nama aksi navigasi -> GLOBAL_ACTION_* bawaan AccessibilityService. */
    fun navAction(name: String): Boolean = when (name.lowercase()) {
        "back" -> globalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        "home" -> globalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        "recent", "recents" -> globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        "notifications", "shade" -> globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        "quicksettings" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2)
            globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) else false
        "lock", "lockscreen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) else false
        else -> false
    }

    /**
     * Kirim satu stroke ke sistem.
     *
     * Gate [gestureBusy] penting untuk drag: sebuah drag memakan waktu sampai 260 ms,
     * dan gesture berikutnya datang setiap kali jari operator berpindah beberapa piksel.
     * Tanpa gate, gesture kedua membatalkan yang pertama dan permukaan justru bergerak
     * tersendat-sendat. Dengan gate, gesture yang tiba saat sibuk langsung dilaporkan
     * "diterima" tanpa diantre - urutan drag tetap utuh karena panel hanya peduli pada
     * posisi terakhir, dan mengejar posisi terakhir selalu lebih benar daripada memutar
     * ulang posisi lama.
     */
    private fun dispatch(path: Path, duration: Long, done: ((Boolean) -> Unit)?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            done?.let { cb -> main.post { cb(false) } }
            return false
        }
        val service = svc ?: run {
            done?.let { cb -> main.post { cb(false) } }
            return false
        }
        if (!gestureBusy.compareAndSet(false, true)) {
            // gesture lain sedang berjalan; posisi yang lebih baru akan datang sendiri
            done?.let { cb -> main.post { cb(true) } }
            return true
        }
        return try {
            val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val accepted = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        gestureBusy.set(false)
                        done?.let { cb -> main.post { cb(true) } }
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        gestureBusy.set(false)
                        done?.let { cb -> main.post { cb(false) } }
                    }
                },
                null
            )
            if (!accepted) {
                gestureBusy.set(false)
                done?.let { cb -> main.post { cb(false) } }
            }
            accepted
        } catch (e: Exception) {
            Log.e(TAG, "dispatchGesture gagal: ${e.message}")
            gestureBusy.set(false)
            done?.let { cb -> main.post { cb(false) } }
            false
        }
    }
}
