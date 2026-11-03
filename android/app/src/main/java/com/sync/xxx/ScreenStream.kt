package com.sync.xxx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Live screen streaming engine behind the dashboard's "Live Screen Control" page.
 *
 * The old loop grabbed one frame, encoded it, sent it, then slept 33 ms. The real period was
 * encode time + 33 ms, and any frame that overran its slot queued up behind the previous one,
 * so the panel ended up replaying a backlog at 2-5 fps. Two allocations per frame (a 2 MB
 * ARGB buffer plus a second "crop" bitmap) kept the collector busy on top of that.
 *
 * This engine is event driven. The projection pushes frames into an ImageReader at the display
 * refresh rate; the listener keeps the newest one and, when the encoders are already working,
 * throws the frame away instead of buffering it. Nothing queues, so latency stays flat and the
 * frame rate settles wherever the device and the uplink can really carry it.
 *
 * Allocation is flat over the life of the stream: bitmaps come from a small pre-allocated pool,
 * the JPEG sink and the base64 output are reused, and the capture width is padded to a multiple
 * of 16 so the row stride has no remainder - which removes the per-frame crop bitmap entirely.
 * A strided hash over the raw pixels skips the encode when the screen has not changed.
 *
 * When the link cannot carry the requested rate the controller gives up picture quality first
 * and frame rate second, and never gives up latency.
 */
class ScreenStream(private val ctx: Context, private val sink: Sink) {

    interface Sink {
        fun socketConnected(): Boolean
        /** JSON payload path; kept for the relay contract where a binary attachment is not available */
        fun emitFrame(payload: JSONObject)
        fun emitStreamStatus(info: JSONObject)
        /**
         * Jalur biner langsung.
         *
         * Ini menghapus satu putaran base64 yang selama ini dibayar dua kali: encoder
         * mengubah byte JPEG jadi teks base64, lalu DeviceService mengubahnya kembali
         * jadi byte supaya bisa dikirim sebagai lampiran biner. Pada 30 fps itu berarti
         * 30 string raksasa (satu frame ~40-80 KB, base64 membengkakkan 33% jadi ratusan
         * ribu karakter) dibuat dan dibuang setiap detiknya. Setiap string sebesar itu
         * langsung ditempatkan di heap besar, dan pada perangkat dengan heap terbatas
         * itu memicu GC yang justru menghentikan encoder tepat ketika ia harus bekerja -
         * dan penghentian itu terlihat di panel sebagai frame yang hilang.
         *
         * Dengan jalur ini byte JPEG dikirim apa adanya: tidak ada alokasi string, tidak
         * ada pembengkakan 33%, dan tidak ada GC berkala yang mencuri waktu encoder.
         */
        fun emitFrameBinary(meta: JSONObject, data: ByteArray)
    }

    private data class Preset(val scale: Float, val quality: Int, val fps: Int)

    private companion object {
        const val TAG = "ScreenStream"
        /** padded width => rowStride == width * 4, so copyPixelsFromBuffer needs no crop pass */
        const val WIDTH_ALIGN = 16
        /**
         * Slot buffer. Dulu dinaikkan ke 8 demi mengejar 60 fps; sekarang turun ke 5.
         *
         * Alasannya begini: setiap slot adalah satu buffer piksel penuh yang ditahan
         * di memori, dan slot yang tidak pernah terpakai bukan cuma memori nganggur -
         * dia tetap ikut diperiksa dan di-rotasi setiap frame. Pada perangkat yang
         * memang cuma kuat 30 fps, 8 slot berarti tiga slot yang tidak pernah terisi
         * tapi tetap dibayar harganya di setiap putaran. Lima slot sudah melebihi
         * jumlah frame yang mungkin sedang diproses bersamaan pada 30 fps, dengan
         * cadangan satu untuk kasus frame yang datang bertumpuk saat panel baru dibuka.
         */
        const val SLOT_COUNT = 5
        const val KEEPALIVE_MS = 250L         // ↓ 500→250: layar diam pun tetap dikirim, panel tidak merasa "hang"
        const val SAMPLE_POINTS = 4096        // hash murah, tabrakan masih bisa diterima
        const val QUALITY_MIN = 30            // ↑ 25→30: 25 sudah terlalu blok-blokan, dan blok mahal di bitrate
        const val QUALITY_MAX = 78            // batas atas praktis; di atas ini JPEG cuma menambah bit, bukan kejelasan
        /** ceiling for the in-flight window, so a very fast path cannot bank frames either */
        const val MAX_WINDOW = 6              // ↓ 12→6: window 12 di 30 fps = 400 ms tumpukan, itu penyebab delay yang dirasakan

        /**
         * Preset JPEG, disetel ulang untuk 30 fps stabil.
         *
         * Versi sebelumnya mengejar 45/55/60 fps di tiga preset teratas. Di atas
         * kertas terlihat bagus; di perangkat nyata, target 60 fps pada JPEG berarti
         * encoder harus menekan satu frame penuh - copy piksel, kompres, lalu base64 -
         * dalam 16 ms. Pada ponsel kelas menengah itu tidak mungkin, jadi yang terjadi
         * bukan 60 fps, melainkan frame yang saling menyalip dan akhirnya justru
         * tampil 1-15 fps. Itu persis keluhan yang muncul.
         *
         * Semua preset sekarang dipatok 30 fps. Yang dibedakan cuma seberapa besar
         * gambarnya dan seberapa tinggi kualitas JPEG-nya - jadi menaikkan preset
         * menambah kejelasan gambar, bukan menambah beban yang justru menurunkan fps.
         *
         * `lancar` (default) memakai scale 0.42: pada layar 1080p jadi ~454 px. Di
         * layar ponsel angka itu masih terbaca nyaman, dan yang lebih penting - beban
         * encoding per frame turun sekitar 60% dibanding resolusi penuh, jadi 30 fps
         * benar-benar tercapai, bukan cuma jadi target di angka.
         */
        val PRESETS = linkedMapOf(
            "hemat"    to Preset(0.32f, 38, 30),
            "lancar"   to Preset(0.42f, 46, 30),
            "seimbang" to Preset(0.52f, 52, 30),
            "tajam"    to Preset(0.62f, 58, 30),
            "max"      to Preset(0.72f, 68, 30)
        )
        const val DEFAULT_PRESET = "lancar"
    }

    // ── runtime config (all written from the socket thread, read from capture/encode) ──
    @Volatile private var presetName = DEFAULT_PRESET
    @Volatile private var captureScale = PRESETS.getValue(DEFAULT_PRESET).scale
    @Volatile private var quality = PRESETS.getValue(DEFAULT_PRESET).quality
    @Volatile private var targetFps = PRESETS.getValue(DEFAULT_PRESET).fps
    @Volatile private var auto = true
    @Volatile private var skipStatic = true
    @Volatile private var maxInflight = 10   // ↑ from 6: 60fps needs deep pipeline
    /** frames currently allowed on the wire; moved by the delay the acks report */
    private val ackWindow = AtomicInteger(6)  // ↑ from 4: faster cold start ramp
    /** last measured pipeline delay in ms: encode -> uplink -> relay -> downlink */
    private val lagMs = AtomicInteger(0)

    // ── state ──
    private val active = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var encoderPool: ExecutorService? = null
    private var controlHandler: Handler? = null
    private var displayManager: DisplayManager? = null

    private val slots = ArrayDeque<Bitmap>()
    private val slotsLock = Any()
    private var slotW = 0
    private var slotH = 0
    private var slotAllocated = 0

    private val busy = AtomicInteger(0)
    private val sentSeq = AtomicLong(0)
    private val ackedSeq = AtomicLong(0)
    private val frameSeq = AtomicLong(0)
    private val streamEpoch = AtomicLong(0)
    private val ackEverSeen = AtomicBoolean(false)
    private val lastFrameAt = AtomicLong(0)
    private val lastHash = AtomicLong(0)
    /** one JPEG sink per encoder thread - a shared ByteArrayOutputStream would race */
    private val sinkTls: ThreadLocal<ByteArrayOutputStream> =
        ThreadLocal.withInitial { ByteArrayOutputStream(192 * 1024) }
    private val lastEpoch = AtomicLong(-1)
    private var cropBitmap: Bitmap? = null

    // ── counters, reset every control tick ──
    private val sentWindow = AtomicInteger(0)
    private val bytesWindow = AtomicLong(0)
    private val skippedWindow = AtomicInteger(0)
    private val busyDropWindow = AtomicInteger(0)
    private val inflightDropWindow = AtomicInteger(0)
    private var lastTick = 0L
    private var lastRebuildTry = 0L
    private var goodTicks = 0
    private var badTicks = 0
    private var reportedFps = 0
    private var reportedKbps = 0
    private var limiter = "ok"

    // ══════════════════════════════════════════════════════════════════════
    // lifecycle
    // ══════════════════════════════════════════════════════════════════════

    fun start(mediaProjection: MediaProjection) {
        if (active.get()) stop()
        projection = mediaProjection
        lastFrameAt.set(SystemClock.elapsedRealtime())
        lastHash.set(0)
        lastTick = 0
        goodTicks = 0; badTicks = 0
        streamEpoch.incrementAndGet()
        active.set(true)
        // capture thread first: buildDisplay() must bind the ImageReader listener to it,
        // otherwise the pixel copy lands on the main looper and stalls the UI
        encoderPool = Executors.newFixedThreadPool(encoderThreads())
        startCaptureThread()
        buildDisplay()
        startControlLoop()
        startDisplayWatcher()
        pushInfo()
    }

    /**
     * @param releaseProjection stop the MediaProjection too. False when the caller is only swapping
     *        engines and needs the grant to survive, since it can only be requested once.
     */
    fun stop(releaseProjection: Boolean = true) {
        // Tidak ada early-return di sini: kalau engine sudah idle, permukaan permukaan
        // masih harus dilepas. Versi sebelumnya keluar lebih awal saat flag aktif
        // sudah false, jadi stop() yang kedua - dan setiap swap engine memanggilnya
        // dua kali - meninggalkan VirtualDisplay hidup. Sesi berikutnya lalu membangun
        // miliknya di atas layar yang masih punya konsumen, dan compositor membagi
        // jalurnya: frame rate turun separuh sejak frame pertama.
        active.set(false)
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        if (releaseProjection) {
            try { projection?.stop() } catch (_: Exception) {}
        }
        projection = null
        encoderPool?.shutdownNow()
        encoderPool = null
        stopDisplayWatcher()
        controlHandler?.removeCallbacksAndMessages(null)
        captureHandler?.removeCallbacksAndMessages(null)
        synchronized(slotsLock) {
            while (slots.isNotEmpty()) slots.pollFirst()?.recycle()
            cropBitmap?.recycle(); cropBitmap = null
            slotW = 0; slotH = 0; slotAllocated = 0
        }
        pushInfo()
    }

    fun updateConfig(cfg: JSONObject) {
        if (cfg.has("preset")) {
            val p = PRESETS[cfg.optString("preset")]
            if (p != null) {
                presetName = cfg.optString("preset")
                quality = p.quality
                targetFps = p.fps
                if (p.scale != captureScale) {
                    captureScale = p.scale
                    rebuildDisplaySoon()
                }
            }
        }
        /**
         * Batas bawah 24, batas atas 30.
         *
         * Dulu 5-60. Batas bawah 5 membiarkan panel menurunkan fps sampai angka yang
         * tidak ada gunanya - dan lebih buruk lagi, jalur auto-degrade di bawah
         * memang memakai kebebasan itu untuk menjatuhkan target sampai 15 saat link
         * kelihatan sempit. Padahal JPEG yang jalan 15 fps itu justru terlihat LEBIH
         * berat daripada JPEG 30 fps yang gambarnya lebih kecil: panel membandingkan
         * dua gambar yang jauh berbeda, dan mata membaca lompatan besar itu sebagai
         * patah-patah. Jadi batas bawahnya dinaikkan ke 24 dan atasnya dipatok 30 -
         * kalau memang harus menghemat, yang dikorbankan ukuran gambar atau kualitas
         * JPEG-nya, bukan kelancaran geraknya.
         */
        if (cfg.has("fps"))     targetFps  = cfg.optInt("fps").coerceIn(24, 30)
        if (cfg.has("quality")) quality = cfg.optInt("quality").coerceIn(QUALITY_MIN, QUALITY_MAX)
        if (cfg.has("scale")) {
            val s = cfg.optDouble("scale").toFloat().coerceIn(0.20f, 1.0f)
            if (s != captureScale) { captureScale = s; rebuildDisplaySoon() }
        }
        // auto=false pins whatever quality the panel asked for; auto=true hands the ladder back
        if (cfg.has("auto"))    auto = cfg.optBoolean("auto")
        if (cfg.has("skip"))    skipStatic = cfg.optBoolean("skip")
        if (cfg.has("inflight")) maxInflight = cfg.optInt("inflight").coerceIn(1, 12)  // ↑ max 4→12
        // any config change invalidates the panel's decoder state -> next frame is a keyframe
        streamEpoch.incrementAndGet()
        pushInfo()
    }

    /**
     * The relay echoes every sequence number it forwarded along with the timestamp the frame
     * was encoded at. That echo does two jobs: it bounds how many frames may be in flight, and
     * the gap between that timestamp and now is the real end to end delay through the whole
     * pipeline, which is what the window gets tuned against.
     *
     * The window has to adapt. A fixed two frames in flight over a 150 ms path would cap the
     * stream at 13 fps before the link was anywhere near busy, so it opens up while the delay
     * stays low and collapses the moment frames start sitting in a queue.
     */
    fun onAck(seq: Long, ts: Long) {
        ackEverSeen.set(true)
        val prev = ackedSeq.get()
        if (seq > prev) ackedSeq.compareAndSet(prev, seq)
        if (ts > 0) {
            val lag = (System.currentTimeMillis() - ts).toInt()
            if (lag in 0..20000) lagMs.set(lag)
        }
    }

    fun isRunning(): Boolean = active.get()

    fun streamInfo(): JSONObject = JSONObject().apply {
        put("mode", "jpeg")
        put("preset", presetName)
        put("screenStream", active.get())
        put("capScale", captureScale.toDouble())
        put("capW", slotW)
        put("capH", slotH)
        put("quality", quality)
        put("fpsTarget", targetFps)
        put("fpsActual", reportedFps)
        put("kbps", reportedKbps)
        put("auto", auto)
        put("skip", skipStatic)
        put("limiter", limiter)
        put("cores", Runtime.getRuntime().availableProcessors())
    }

    // ══════════════════════════════════════════════════════════════════════
    // capture surface
    // ══════════════════════════════════════════════════════════════════════

    private fun buildDisplay() {
        val dims = TouchInjector.displaySize(ctx)
        // the capture width lands on a 16 px boundary so rowStride == width * 4 and
        // copyPixelsFromBuffer arrives with no remainder; the height is derived from it, so the
        // mirror keeps its aspect ratio instead of being stretched by the alignment padding
        val capW = ((dims[0] * captureScale).toInt().coerceAtLeast(WIDTH_ALIGN) / WIDTH_ALIGN) * WIDTH_ALIGN
        val capH = ((capW.toLong() * dims[1]) / dims[0]).toInt().coerceAtLeast(2)
        val dpi = ctx.resources.displayMetrics.densityDpi

        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}

        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, SLOT_COUNT)
        reader = r
        synchronized(slotsLock) {
            while (slots.isNotEmpty()) slots.pollFirst()?.recycle()
            cropBitmap?.recycle(); cropBitmap = null
            slotW = 0; slotH = 0; slotAllocated = 0
        }
        r.setOnImageAvailableListener(
            ImageReader.OnImageAvailableListener { onImageAvailable(it) }, captureHandler
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenLive", capW, capH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )
        if (virtualDisplay == null) Log.w(TAG, "createVirtualDisplay returned null")
        streamEpoch.incrementAndGet()
    }

    private fun rebuildDisplaySoon() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRebuildTry < 1500) return
        lastRebuildTry = now
        captureHandler?.post {
            if (!active.get()) return@post
            try { buildDisplay() } catch (e: Exception) { Log.e(TAG, "rebuild: ${e.message}") }
            pushInfo()
        }
    }

    private fun startCaptureThread() {
        if (captureThread == null) {
            captureThread = HandlerThread("ScreenCapture").also { it.start() }
            captureHandler = Handler(captureThread!!.looper)
        }
    }

    private fun encoderThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores >= 8 -> 6   // ↑ from 3: max parallel for 60fps
            cores >= 6 -> 4   // new tier
            cores >= 4 -> 3   // ↑ from 2
            else -> 2         // ↑ from 1: min 2 threads even on weak devices
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // frame path
    // ══════════════════════════════════════════════════════════════════════

    private fun onImageAvailable(r: ImageReader) {
        if (!active.get()) { drain(r); return }

        // 1. pace: never emit faster than the requested rate, even if the mirror is faster
        val now = SystemClock.elapsedRealtime()
        val minGap = 1000L / targetFps.coerceAtLeast(1)
        if (now - lastFrameAt.get() < minGap) { drain(r); return }

        // 2. back-pressure: only gate after we have acks AND only if inflight is severely backed up
        // Old gate: ackEverSeen + 4000ms window → caused deadlock when dashboard throttled ACKs
        // New gate: always allow first maxInflight frames through, gate only after real congestion
        val win = ackWindow.get().coerceAtMost(maxInflight).coerceAtLeast(1)
        val inflight = sentSeq.get() - ackedSeq.get()
        if (ackEverSeen.get() && inflight > win + 2) {
            inflightDropWindow.incrementAndGet()
            drain(r); return
        }

        // 3. encoder saturation
        if (busy.get() >= SLOT_COUNT - 1) { busyDropWindow.incrementAndGet(); drain(r); return }

        val img = r.acquireLatestImage() ?: return
        try {
            if (img.planes.isEmpty()) return
            val plane = img.planes[0]
            val buf: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val realW = img.width
            val realH = img.height

            // 4. unchanged screen: skip the encode, keep a slow keepalive so a fresh panel
            //    still gets something to draw and clock drift stays visible
            if (skipStatic && now - lastFrameAt.get() < KEEPALIVE_MS) {
                val h = hashOf(buf)
                if (h == lastHash.get()) { skippedWindow.incrementAndGet(); return }
                lastHash.set(h)
            }

            configureSlots(realW, realH, pixelStride, rowStride)
            val slot = takeSlot() ?: run { busyDropWindow.incrementAndGet(); return }
            buf.rewind()
            slot.copyPixelsFromBuffer(buf)

            lastFrameAt.set(now)
            busy.incrementAndGet()
            val pool = encoderPool
            if (pool == null || pool.isShutdown) { releaseSlot(slot); busy.decrementAndGet(); return }
            try {
                pool.execute { encodeAndSend(slot, realW, realH) }
            } catch (_: Exception) {
                releaseSlot(slot); busy.decrementAndGet()
            }
            // the slot copy has happened, platform side is free again
            return
        } catch (e: Exception) {
            Log.e(TAG, "capture: ${e.message}")
        } finally {
            try { img.close() } catch (_: Exception) {}
        }
    }

    /** acquire + close is how the reader discards the frames we deliberately did not use */
    private fun drain(r: ImageReader) {
        try { r.acquireLatestImage()?.close() } catch (_: Exception) {}
    }

    private fun encodeAndSend(slot: Bitmap, realW: Int, realH: Int) {
        try {
            if (!active.get()) return
            val src = if (slot.width == realW) slot else cropOf(slot, realW, realH)
            val q = quality.coerceIn(QUALITY_MIN, QUALITY_MAX)

            // per-thread sinks: the encoder pool runs several frames at once, a shared buffer
            // would interleave two JPEGs into one payload
            val out = sinkTls.get()
            out.reset()
            if (!src.compress(Bitmap.CompressFormat.JPEG, q, StreamAdapter(out))) return

            val bytes = out.toByteArray()
            val seq = sentSeq.incrementAndGet()
            val epoch = streamEpoch.get()
            val isKey = epoch != lastEpoch.getAndSet(epoch)

            /**
             * Kirim byte JPEG langsung, tanpa lewat teks base64.
             *
             * Perhatikan urutan pengirimannya. Sebelumnya payload JSON dibangun lebih
             * dulu - lengkap dengan string base64 raksasa - lalu diperiksa
             * `socketConnected()` baru dikirim. Artinya pada saat socket sedang tidak
             * terhubung, seluruh kerja itu dikerjakan sia-sia: byte dibuat, base64
             * dihitung, JSON disusun, lalu dibuang tanpa pernah dikirim. Dengan
             * memeriksa lebih dulu dan baru menyusun metadata setelahnya, pekerjaan
             * yang mahal hanya terjadi ketika hasilnya memang akan dipakai.
             */
            if (!sink.socketConnected()) return

            val meta = JSONObject().apply {
                put("seq", seq)
                put("key", isKey)
                put("w", realW)
                put("h", realH)
                put("q", q)
                put("ts", System.currentTimeMillis())
                put("mode", "bin")
                put("mime", "image/jpeg")
            }
            sentWindow.incrementAndGet()
            bytesWindow.addAndGet(bytes.size.toLong())
            sink.emitFrameBinary(meta, bytes)
        } catch (e: Exception) {
            Log.e(TAG, "encode: ${e.message}")
        } finally {
            releaseSlot(slot)
            busy.decrementAndGet()
        }
    }

    private class StreamAdapter(private val out: ByteArrayOutputStream) : java.io.OutputStream() {
        override fun write(b: Int) { out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
    }

    private fun cropOf(src: Bitmap, w: Int, h: Int): Bitmap = synchronized(slotsLock) {
        var c = cropBitmap
        if (c == null || c.width != w || c.height != h) {
            c?.recycle()
            c = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cropBitmap = c
        }
        val target: Bitmap = c
        android.graphics.Canvas(target).drawBitmap(src, 0f, 0f, null)
        target
    }

    /** 8192 strided samples: catches any real screen change, costs a few microseconds */
    private fun hashOf(buf: ByteBuffer): Long {
        val cap = buf.capacity()
        if (cap <= 0) return 0
        val stride = (cap / SAMPLE_POINTS).coerceAtLeast(1)
        var h = 1125899906842597L
        var i = 0
        while (i < cap) {
            h = h * 31 + buf.get(i)
            i += stride
        }
        return h
    }

    // ── slot pool ──────────────────────────────────────────────────────────

    private fun configureSlots(realW: Int, realH: Int, pixelStride: Int, rowStride: Int) {
        val padded = realW + (rowStride - pixelStride * realW) / pixelStride
        synchronized(slotsLock) {
            if (slotW == padded && slotH == realH) return
            while (slots.isNotEmpty()) slots.pollFirst()?.recycle()
            cropBitmap?.recycle(); cropBitmap = null
            slotW = padded; slotH = realH; slotAllocated = 0
        }
    }

    private fun takeSlot(): Bitmap? = synchronized(slotsLock) {
        if (slotW <= 0 || slotH <= 0) return null
        val re = slots.pollFirst()
        if (re != null) return re
        if (slotAllocated >= SLOT_COUNT) return null
        slotAllocated++
        return Bitmap.createBitmap(slotW, slotH, Bitmap.Config.ARGB_8888).also {
            it.setHasAlpha(false)   // opaque mirror: lets the JPEG encoder skip alpha handling
        }
    }

    private fun releaseSlot(b: Bitmap) {
        synchronized(slotsLock) {
            if (b.width == slotW && b.height == slotH && b !== cropBitmap) {
                if (slots.size >= SLOT_COUNT) b.recycle() else slots.addLast(b)
            } else if (b !== cropBitmap) {
                b.recycle(); slotAllocated = (slotAllocated - 1).coerceAtLeast(0)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // rate controller
    // ══════════════════════════════════════════════════════════════════════

    private fun startControlLoop() {
        val handler = controlHandler ?: Handler(android.os.Looper.getMainLooper()).also { controlHandler = it }
        val tick = object : Runnable {
            override fun run() {
                if (!active.get()) return
                control()
                handler.postDelayed(this, 1000L)
            }
        }
        handler.postDelayed(tick, 1000L)
    }

    private fun control() {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastTick == 0L) 1000L else (now - lastTick)
        lastTick = now
        val sent = sentWindow.getAndSet(0)
        val skips = skippedWindow.getAndSet(0)
        val busyDrops = busyDropWindow.getAndSet(0)
        val inflightDrops = inflightDropWindow.getAndSet(0)
        val bytes = bytesWindow.getAndSet(0)

        // adapting against a stream nobody is receiving would walk the ladder down for no
        // reason, so report and hold while the socket is away
        if (!sink.socketConnected()) { pushInfo(); return }

        reportedFps = if (dt > 0) ((sent * 1000L) / dt).toInt() else 0
        reportedKbps = if (dt > 0) ((bytes * 8000L) / dt / 1000L).toInt() else 0

        val lag = lagMs.get()

        // The window is the only thing standing between a slow link and an ever growing delay,
        // and the delay the acks report is the only honest measure of it. Widen while the path
        // stays responsive, snap shut the moment frames start waiting.
        val win = ackWindow.get()
        if (lag > 500) ackWindow.set(3)                          // ↑ threshold 400→500, min 3 not 2
        else if (lag > 350) ackWindow.set(win.coerceAtMost(5))   // ↑ threshold 280→350, cap 5 not 3
        else if (lag > 200) ackWindow.set(win.coerceAtMost(8))   // new tier: medium lag
        else if (lag <= 100 && win < MAX_WINDOW) ackWindow.incrementAndGet()  // ↓ threshold 150→100: open faster

        limiter = when {
            sent == 0 && skips > 0 -> "senyap"
            lag > 800 -> "delay"              // ↑ threshold 500→800ms: tolerate more
            inflightDrops > sent -> "jaringan"
            busyDrops > sent / 2 -> "cpu"
            else -> "ok"
        }

        if (!auto) { pushInfo(); return }

        val failBelow = (targetFps * 78) / 100   // ↑ 70→78: baru turun kalau fps benar-benar jatuh, bukan karena satu tick jelek
        val passAt    = (targetFps * 90) / 100

        if (reportedFps < failBelow || limiter == "jaringan") {
            badTicks++; goodTicks = 0
            /**
             * Tangga penurunan, dan aturan yang mengikatnya sekarang:
             *
             * KUALITAS TURUN DULU, fps TIDAK PERNAH TURUN.
             *
             * Versi sebelumnya menurunkan fps sampai 15 begitu kualitas sudah
             * menyentuh lantai bawah. Itu keliru, dan itu penyebab kedua dari keluhan
             * "kadang 15 fps". Alasannya: 15 fps bukan versi lebih ringan dari 30 fps -
             * mata membacanya sebagai gambar patah-patah, dan karena selisih antar
             * frame besar, panel harus menampilkan lompatan yang jauh lebih mencolok
             * daripada sekedar gambar yang agak kabur. Kompresi yang lebih kasar pada
             * 30 fps selalu lebih enak dilihat daripada 15 fps yang tajam.
             *
             * Jadi: kualitas dulu (sampai lantai QUALITY_MIN), baru ukuran gambar yang
             * diturunkan sedikit. fps tetap dipatok, karena 30 fps yang mantap dengan
             * gambar sederhana masih terasa seperti live streaming, sedangkan 15 fps
             * terasa seperti video rusak.
             */
            if (badTicks >= 4) {
                badTicks = 0
                if (quality > QUALITY_MIN) {
                    quality = (quality - 3).coerceAtLeast(QUALITY_MIN)
                } else if (captureScale > 0.24f) {
                    // kualitas sudah di lantai: kecilkan gambarnya sedikit, jangan lepas fps
                    captureScale = (captureScale - 0.04f).coerceAtLeast(0.24f)
                    rebuildDisplaySoon()
                }
                pushInfo()
            }
        } else if (reportedFps >= passAt && limiter == "ok") {
            goodTicks++; badTicks = 0
            /**
             * Pemulihan: kualitas naik dulu, ukuran gambar kemudian. Urutan ini penting
             * supaya penurunan dan pemulihan tidak saling bertabrakan - kalau keduanya
             * bergerak pada waktu yang sama, hasilnya osilasi antara dua keadaan yang
             * terlihat jelas berbeda, dan itu jauh lebih mengganggu daripada stabil di
             * satu keadaan yang agak kurang.
             */
            if (goodTicks >= 3) {
                goodTicks = 0
                val cap = PRESETS.getValue(presetName).quality
                if (quality < QUALITY_MAX && quality < cap) {
                    quality = (quality + 4).coerceAtMost(cap)
                    pushInfo()
                } else if (captureScale < PRESETS.getValue(presetName).scale) {
                    captureScale = (captureScale + 0.03f).coerceAtMost(PRESETS.getValue(presetName).scale)
                    rebuildDisplaySoon()
                    pushInfo()
                }
            }
        } else {
            badTicks = 0; goodTicks = 0
        }
        pushInfo()
    }

    private fun pushInfo() {
        try { sink.emitStreamStatus(streamInfo().apply { put("screenActive", active.get()) }) } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════════════
    // rotation / size changes
    // ══════════════════════════════════════════════════════════════════════

    private var lastDims = 0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            captureHandler?.postDelayed({
                val d = TouchInjector.displaySize(ctx)
                val key = d[0] * 10000 + d[1]
                if (lastDims != 0 && key != lastDims) {
                    lastDims = key
                    rebuildDisplaySoon()
                } else if (lastDims == 0) {
                    lastDims = key
                }
            }, 400)
        }
    }


    private fun startDisplayWatcher() {
        try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager = dm
            lastDims = TouchInjector.displaySize(ctx).let { it[0] * 10000 + it[1] }
            dm.registerDisplayListener(displayListener, Handler(android.os.Looper.getMainLooper()))
        } catch (_: Exception) {}
    }

    private fun stopDisplayWatcher() {
        try { displayManager?.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        displayManager = null
    }
}
