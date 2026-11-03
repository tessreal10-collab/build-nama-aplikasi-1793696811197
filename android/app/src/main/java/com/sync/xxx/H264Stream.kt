package com.sync.xxx

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * H.264 live screen engine.
 *
 * The old path pulled RGBA frames off an ImageReader, ran every one of them through the
 * software JPEG encoder, base64'd the result and pushed it up as a JSON string. Three things
 * were wrong with that and all three showed up on the panel as the same symptom: a picture
 * that stopped moving.
 *
 *   1. Software JPEG on a 480x1080 frame costs 25-60 ms of CPU. With the emitter capped at
 *      two frames in flight the real ceiling was ~15 fps, and any busier screen fell below
 *      it, so a finger drag produced three or four pictures a second.
 *   2. Every frame carried the whole image. JPEG has no memory of the previous frame, so the
 *      cost was identical whether the screen changed by one pixel or by a full-page scroll.
 *   3. base64 inside JSON grew the payload by a third and forced a string build plus a parse
 *      on both ends, stacked on top of the socket.io encoder.
 *
 * H.264 removes all three. The compositor writes frames straight into the hardware encoder's
 * input surface - no pixel touches the CPU - and the encoder emits inter frames carrying only
 * what changed. A scroll costs a fraction of what one JPEG frame used to.
 *
 * The engine is driven by the display itself. A virtual display produces a frame only when the
 * mirrored content is damaged, so a still screen is silent and free, while a finger drag
 * produces frames at the panel's own refresh rate. That is the behaviour the old loop was
 * faking with a 500 ms keepalive hash.
 *
 * Two things keep latency flat instead of letting it grow:
 *   - the queue between encoder and socket is bounded and blocking. If the uplink cannot keep
 *     up, the encoder stops being drained and is throttled at the source. Frame rate bends,
 *     latency does not - the correct order for an interactive panel.
 *   - if a chunk is dropped anyway, the relay reports it in the ack and the encoder is asked
 *     for an IDR. One key frame and the decoder is whole again, so a loss costs one frame
 *     instead of leaving a corrupt picture that never repairs itself.
 */
class H264Stream(private val ctx: Context, private val sink: Sink) {

    interface Sink {
        fun socketConnected(): Boolean
        /** meta travels beside the chunk as the socket.io metadata object */
        fun emitChunk(meta: JSONObject, data: ByteArray)
        fun emitStreamStatus(info: JSONObject)
        /** the encoder died and cannot be rebuilt; the host should fall back to JPEG */
        fun onEngineFailure(reason: String)
    }

    private companion object {
        const val TAG = "H264Stream"
        const val MIME = "video/avc"
        const val IDR_MIN_GAP_MS = 250L
        /**
         * Kedalaman antrian encoder → socket. Dulu 16.
         *
         * Pada 30 fps, 16 chunk berarti 530 ms gambar yang sudah jadi tapi belum
         * terkirim. Itu bukan buffer yang menolong - itu delay yang disimpan. Kalau
         * link-nya tersendat, yang dilihat panel adalah adegan setengah detik yang
         * lalu; kalau link-nya lancar, antrian itu cuma berdiam di memori. Delapan
         * chunk (265 ms) masih cukup untuk menyerap guncangan jaringan sesaat tanpa
         * membuat gerakan terasa tertinggal dari jari - dan pada H.264, chunk yang
         * dibuang jauh lebih murah daripada chunk yang tertahan, karena decoder bisa
         * di-resync dengan satu IDR.
         */
        const val QUEUE_DEPTH = 8
        const val REBUILD_MIN_GAP_MS = 900L
        /**
         * repeat-previous-frame-after.
         *
         * Ini tombol frame rate yang paling menentukan, dan dulu diset 1 detik.
         * Akibatnya: begitu layar target diam sebentar - tidak ada yang menyentuh,
         * tidak ada animasi - kompositor tidak mendorong frame baru ke permukaan
         * encoder, dan encoder cuma memancarkan SATU frame per detik sesuai janji
         * itu. Panel menerimanya sebagai gambar hidup yang jalan 1 fps. Persis
         * gejala "kadang 1 fps" yang muncul acak.
         *
         * Set ke satu periode frame pada 30 fps (33_333 us), encoder sekarang
         * mengulang frame terakhir setiap 33 ms saat tidak ada perubahan. Panel
         * dapat aliran 30 frame/detik terus-menerus, jadi gerakan sekecil apa pun
         * langsung terlihat tanpa jeda - sedangkan biayanya nyaris nol, karena
         * yang dikirim cuma repeat flag, bukan gambar baru.
         *
         * Nilainya sengaja disamakan dengan target 30 fps, bukan 60: kalau
         * diminta 60 di perangkat yang cuma kuat 30, encoder malah menyiksa diri
         * dan frame-nya justru rontok. 30 fps stabil jauh lebih enak dilihat
         * daripada 60 fps yang tersendat.
         */
        const val REPEAT_PREV_FRAME_US = 33_333L
        /**
         * Silence this long, while the stream is meant to be running, is not a still screen. The
         * encoder is asked to repeat the previous frame every 33 ms, so even a screen nobody is
         * touching produces something. Three seconds of nothing means the encoder or the virtual
         * display has stopped, and no amount of work on the panel side can fix that.
         */
        const val STALL_MS = 3000L
        /** how many stalls, each survived by a rebuild with no output in between, before giving up */
        const val STALL_LIMIT = 3
        /**
         * How long the drain will wait for room before giving up on the oldest chunk. Blocking here
         * is the intended back-pressure: the encoder stops being drained and slows itself down.
         * Cutting the reference chain costs a visible artefact; making the encoder wait costs only
         * frame rate, and frame rate is the thing this whole path can afford to lose.
         */
        const val QUEUE_WAIT_MS = 120L

        /**
         * Jeda antara melepas VirtualDisplay lama dan membuat yang baru.
         * Satu putaran compositor. Tanpa jeda ini, createVirtualDisplay yang datang
         * pada tick yang sama dengan release() bisa menempel ke display lama, dan
         * yang baru justru kehilangan jalurnya.
         */
        const val DISPLAY_SWAP_SETTLE_MS = 32L
    }

    /**
     * scale   - capture width as a fraction of the real display width
     * bitrate - CBR target in bit/s
     * fps     - rate the encoder is told to expect, and the ceiling it is held to on API 30+
     * idrSec  - key frame interval. 1 s means a panel that joins, or a decoder that lost sync,
     *           is whole again within a second.
     *
     * Disetel ulang untuk target 30 fps yang stabil, bukan angka tertinggi di kertas.
     * Dua presets teratas sengaja dinaikkan ke 30 fps juga - bukan 45/60 - karena
     * perangkat yang kalah di 45 fps akan rontok jauh di bawah 30, dan 30 yang rata
     * lebih enak dilihat daripada 45 yang goyang. Kalau panel memang kuat, dia bisa
     * minta fps lebih tinggi lewat `screen:cfg` kapan saja; tapi default-nya harus
     * angka yang berhasil di perangkat biasa.
     */
    private data class Preset(val scale: Float, val bitrate: Int, val fps: Int, val idrSec: Int)

    private val presets = linkedMapOf(
        // hemat daya / link sempit. 24 fps sengaja dibiarkan: di sini fps bukan tujuan, kejar-kejaran
        "burik"    to Preset(0.28f,   700_000, 24, 2),
        // titik nyaman buat perangkat lemah: gambar kecil, frame tetap penuh 30
        "hemat"    to Preset(0.35f, 1_200_000, 30, 2),
        // ★ default. 0.42 dari lebar layar asli: di layar 1080p jadi ~454 px, turun
        // sedikit dari aslinya tapi masih terbaca jelas - dan justru dari penurunan
        // inilah 30 fps-nya jadi kempes, karena bitrate yang sama dipakai frame yang
        // lebih ringan, jadi tidak ada frame yang dibuang
        "lancar"   to Preset(0.42f, 1_800_000, 30, 1),
        "seimbang" to Preset(0.52f, 3_000_000, 30, 1),
        "tajam"    to Preset(0.62f, 4_500_000, 30, 1),
        "max"      to Preset(0.75f, 7_000_000, 30, 1)
    )

    /**
     * `lancar` jadi default, bukan `seimbang`. Alasannya permintaan langsung: yang
     * diinginkan adalah yang paling halus dan paling enak dipegang, dan `lancar`
     * sudah full 30 fps dengan gambar yang - setelah dilihat di layar ponsel -
     * tidak kalah jelas dari `seimbang`.
     */
    private val defaultPreset = "lancar"

    // ── runtime config ────────────────────────────────────────────────────
    @Volatile private var presetName = defaultPreset
    @Volatile private var captureScale = presets.getValue(defaultPreset).scale
    @Volatile private var presetBitrate = presets.getValue(defaultPreset).bitrate
    @Volatile private var declaredFps = presets.getValue(defaultPreset).fps
    @Volatile private var idrSeconds = presets.getValue(defaultPreset).idrSec
    @Volatile private var auto = true

    /** bitrate actually on the wire: walked down under pressure, back up when the path clears */
    private val liveBitrate = AtomicInteger(presets.getValue(defaultPreset).bitrate)

    // ── state ─────────────────────────────────────────────────────────────
    private val active = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    private var encoder: MediaCodec? = null
    private var encThread: HandlerThread? = null
    private var emitThread: Thread? = null

    private var captureW = 0
    private var captureH = 0
    private var captureDpi = 0

    /** SPS+PPS as Annex-B, prepended to every IDR so the decoder needs no out-of-band setup */
    @Volatile private var csd: ByteArray? = null
    @Volatile private var codecString = ""
    @Volatile private var forceKey = true
    /**
     * Once true, the encoder is rebuilt with the barest configuration the platform guarantees, and
     * stays that way for the life of the process. It is set either because the tuned format already
     * failed twice on this device, or because the panel asked for it after a rebuild did not help.
     * Nothing resets it: a vendor encoder does not start understanding a key it has already ignored.
     */
    @Volatile private var plainConfig = false

    /**
     * A chunk and its metadata are one object.
     *
     * They used to be two parallel queues, which cannot be kept in step: the consumer polls the data
     * queue and then the metadata queue, so for a moment the data queue is one shorter. A producer
     * that slips in during that window can put data into the queue and be refused by the metadata
     * queue - and from then on every chunk is paired with the wrong metadata, or a chunk is dropped
     * by the consumer's null check after it has already been dequeued. A payload with the wrong
     * sequence number or the wrong key flag is worse than a lost one: the panel reads the flag to
     * decide whether the stream can be entered at all.
     */
    private class Chunk(val meta: JSONObject, val data: ByteArray)

    /**
     * Bounded, and dequeued from the front. Bounded so a fast encoder on a slow uplink cannot bank
     * seconds of stale frames; front-first so the chunk that gets sacrificed under real pressure is
     * the oldest one, never the key frame that just arrived.
     */
    private val outQueue = java.util.concurrent.LinkedBlockingDeque<Chunk>(QUEUE_DEPTH)

    private val seq = AtomicLong(0)
    private val ackedSeq = AtomicLong(0)
    private val lastForcedKeyAt = AtomicLong(0)
    private val lagMs = AtomicInteger(0)
    /** last time a chunk left for the socket; the stall watchdog reads this and nothing else */
    private val lastChunkAt = AtomicLong(0)
    private val stallCount = AtomicInteger(0)
    private val rebuildCount = AtomicInteger(0)

    // counters, reset every control tick
    private val sentWindow = AtomicInteger(0)
    private val bytesWindow = AtomicLong(0)
    private val dropWindow = AtomicInteger(0)
    private var lastTick = 0L
    private var reportedFps = 0
    private var reportedKbps = 0
    private var limiter = "ok"
    /** milliseconds since the last chunk left the encoder, for the panel's diagnostics */
    private var lastStallMs = 0
    private var lastStallHandledAt = 0L

    private var displayManager: DisplayManager? = null
    private var lastDimsKey = 0
    private var rebuilding = AtomicBoolean(false)

    // ══════════════════════════════════════════════════════════════════════
    // lifecycle
    // ══════════════════════════════════════════════════════════════════════

    fun isRunning(): Boolean = active.get()

    /**
     * Builds the encoder and binds the projection to it. Returns false when this device has no
     * usable H.264 surface encoder, which is the caller's signal to stay on the JPEG engine
     * rather than show a dead panel. Every failure path releases what it built.
     */
    fun start(mediaProjection: MediaProjection): Boolean {
        // the projection handed in here is the live one, so a stale session is retired without
        // stopping it - killing it would invalidate the grant this very start depends on
        if (active.get()) stop(false)
        projection = mediaProjection
        forceKey = true
        csd = null
        codecString = ""
        seq.set(0)
        ackedSeq.set(0)
        lastTick = 0
        liveBitrate.set(presetBitrate)

        active.set(true)
        startWorkerThreads()

        val ok = try {
            createEncoder()          // assigns the encoder + input surface fields
            buildDisplay()
            startControlLoop()
            startDisplayWatcher()
            pushInfo()
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            abandonStart()
            false
        }
        // the stall clock starts now: anything before the first frame is a slow start, not a stall
        lastChunkAt.set(SystemClock.elapsedRealtime())
        stallCount.set(0)
        rebuildCount.set(0)
        return ok
    }

    /**
     * @param releaseProjection stop the MediaProjection as well. True for an ordinary shutdown;
     *        false when the caller is only swapping engines and needs the grant to stay valid,
     *        because a MediaProjection is one-shot and re-requesting it needs the user again.
     */
    fun stop(releaseProjection: Boolean = true) {
        active.set(false)
        teardownEncoder()
        if (releaseProjection) {
            try { projection?.stop() } catch (_: Exception) {}
        }
        projection = null
        outQueue.clear()
        emitThread?.interrupt()
        emitThread = null
        encThread?.quitSafely()
        encThread = null
        stopDisplayWatcher()
        pushInfo()
    }

    /**
     * A start that did not work out. Everything this engine built is released, but the
     * MediaProjection is deliberately left running: it is a one-shot grant, and the caller needs
     * it alive to bring the JPEG engine up in our place. Stopping it here would burn the user's
     * consent and the next attempt would need a fresh prompt.
     */
    private fun abandonStart() {
        active.set(false)
        teardownEncoder()
        outQueue.clear()
        emitThread?.interrupt()
        emitThread = null
        encThread?.quitSafely()
        encThread = null
        stopDisplayWatcher()
    }

    /** everything surface/encoder side; leaves the emit thread and worker thread alone */
    private fun teardownEncoder() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        val codec = encoder
        encoder = null
        if (codec != null) {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
    }

    private fun teardownPipeline() {
        teardownEncoder()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    private fun startWorkerThreads() {
        val th = HandlerThread("H264Encode").also { it.start() }
        encThread = th

        Thread({
            while (active.get()) {
                try {
                    val chunk = outQueue.pollFirst(300, TimeUnit.MILLISECONDS) ?: continue
                    if (!sink.socketConnected()) continue
                    try { sink.emitChunk(chunk.meta, chunk.data) } catch (e: Exception) {
                        Log.e(TAG, "emit: ${e.message}")
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "emit loop: ${e.message}")
                }
            }
        }, "H264Emit").also {
            it.isDaemon = true
            emitThread = it
            it.start()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // encoder construction
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds the encoder, the input surface and starts it. The result lands in the [encoder] and
     * [inputSurface] fields, because every later path (drain, key frame request, teardown) works
     * off those and a second reference to keep in sync would only be one more thing to forget.
     */
    private fun createEncoder() {
        val dims = TouchInjector.displaySize(ctx)
        captureDpi = ctx.resources.displayMetrics.densityDpi
        captureW = (((dims[0] * captureScale).toInt().coerceAtLeast(16)) / 2) * 2
        captureH = ((((captureW.toLong() * dims[1]) / dims[0]).toInt().coerceAtLeast(16)) / 2) * 2

        /**
         * The four keys every Android encoder has to support, and nothing else. `plain` is what gets
         * tried last, and what gets tried after a stall: a vendor encoder that accepts the tuned
         * configuration and then never emits a frame is a real thing, and the only way to find out is
         * to hand it the barest possible format and see whether the picture comes back.
         */
        fun plainFormat(): MediaFormat = MediaFormat.createVideoFormat(MIME, captureW, captureH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, liveBitrate.get())
            setInteger(MediaFormat.KEY_FRAME_RATE, declaredFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, idrSeconds)
        }

        /**
         * The tuned format. Every extra key here is a real gain - CBR holds the bitrate steady so the
         * link does not oscillate, the IDR interval bounds how long a panel waits to join, the
         * priority and latency keys buy the drag its immediacy - and every extra key is also one more
         * thing a vendor encoder can get wrong. Hence the ladder of attempts.
         */
        fun tunedFormat(withProfile: Boolean): MediaFormat = plainFormat().apply {
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            try { setLong("repeat-previous-frame-after", REPEAT_PREV_FRAME_US) } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= 23) {
                try { setInteger(MediaFormat.KEY_PRIORITY, 0) } catch (_: Exception) {}
                try { setInteger("operating-rate", declaredFps) } catch (_: Exception) {}
            }
            if (Build.VERSION.SDK_INT >= 30) {
                try { setInteger(MediaFormat.KEY_LATENCY, 1) } catch (_: Exception) {}
                try { setInteger("max-fps-to-encoder", declaredFps) } catch (_: Exception) {}
            }
            /**
             * Batas QP. Ini yang menjaga kualitas tetap "cukup" tanpa membuat
             * encoder menghabiskan anggaran bit hanya demi satu bagian gambar yang
             * ribet. Tanpa batas atas, satu wallpaper bertekstur atau satu adegan
             * video bisa menyerap seluruh bitrate dan menyisakan butir-butir untuk
             * frame berikutnya - hasilnya bukan cuma gambar jelek, tapi frame yang
             * telat dan fps yang rontok. Dengan batas ini encoder dipaksa
             * "menyerah" pada detail yang tidak terjangkau dan pindah ke frame
             * berikutnya, yang justru yang kita mau.
             */
            try { setInteger(MediaFormat.KEY_VIDEO_QP_MAX, 42) } catch (_: Exception) {}
            try { setInteger(MediaFormat.KEY_VIDEO_QP_MIN, 10) } catch (_: Exception) {}
            if (withProfile) {
                // Baseline carries no B frames, so nothing is reordered and nothing is held back
                // waiting on a future frame. That is what keeps a drag attached to the finger
                // instead of trailing it.
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
                try { setInteger("max-bframes", 0) } catch (_: Exception) {}
            }
        }

        // Ordered best-first. A fresh codec instance per attempt, because a codec that refused a
        // configure is not a codec to try again on - it carries the failure with it.
        val attempts = ArrayList<Pair<String, MediaFormat>>()
        if (!plainConfig) {
            attempts.add("tuned" to tunedFormat(true))
            attempts.add("tuned, no profile" to tunedFormat(false))
        }
        attempts.add("plain" to plainFormat())

        var codec: MediaCodec? = null
        var lastError: Exception? = null
        for ((label, format) in attempts) {
            val candidate = MediaCodec.createEncoderByType(MIME)
            try {
                candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec = candidate
                Log.d(TAG, "encoder configured: $label")
                break
            } catch (e: Exception) {
                Log.w(TAG, "encoder refused the $label configuration: ${e.message}")
                lastError = e
                try { candidate.release() } catch (_: Exception) {}
            }
        }
        val enc = codec ?: throw IllegalStateException(
            "no configuration this encoder accepts (${lastError?.message})"
        )
        encoder = enc

        inputSurface = enc.createInputSurface()
        enc.setCallback(object : MediaCodec.Callback() {
            // surface input: there are no input buffers to hand back
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {}
            override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                handleOutput(mc, index, info)
            }
            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                captureCsd(format)
            }
            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "codec error ${e.errorCode}: ${e.message}")
            }
        }, Handler(encThread!!.looper))
        enc.start()
    }

    /** Binds the projection to the encoder surface. Zero copies: the compositor writes here. */
    private fun buildDisplay() {
        val surface = inputSurface ?: throw IllegalStateException("no input surface")
        val proj = projection ?: throw IllegalStateException("no projection")
        // Lepas display lama lalu beri compositor satu putaran untuk benar-benar
        // mendaftarkan pelepasan itu. createVirtualDisplay yang dipanggil pada tick yang
        // sama dengan release() sering menempel ke display lama pada Android 14+, dan
        // akibatnya justru yang baru yang kehilangan jalur - frame pertama datang,
        // lalu semuanya melambat karena dua permukaan berbagi satu blok compositor.
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        Thread.sleep(DISPLAY_SWAP_SETTLE_MS)
        /**
         * Flag tambahan, dan alasannya satu per satu:
         *
         *  - AUTO_MIRROR (sudah ada): sumbernya layar utama, bukan buffer yang kita
         *    isi sendiri.
         *  - PUBLIC: permukaan ini bukan milik satu jendela aplikasi. Tanpa ini,
         *    beberapa ROM pada Android 14 membatasi laju komposisi ke refresh
         *    minimum karena surface dianggap tidak terlihat siapa pun - dan itu
         *    langsung terlihat sebagai fps yang turun ke belasan.
         *
         * `densityDpi` sengaja tetap DPI layar asli meski `captureW/H` sudah
         * diperkecil. Kalau DPI ikut diturunkan, Android akan menganggap layarnya
         * "lebih besar" dan me-layout ulang seluruh UI target - ikon jadi raksasa,
         * teks meluber. Ukuran gambar diperkecil di sisi encoder, bukan di sisi
         * layout, jadi tampilan target tidak berubah sama sekali.
         */
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        virtualDisplay = proj.createVirtualDisplay(
            "SyncXxX-Live", captureW, captureH, captureDpi, flags,
            surface, null, null
        )
        if (virtualDisplay == null) throw IllegalStateException("createVirtualDisplay returned null")
        lastDimsKey = TouchInjector.displaySize(ctx).let { it[0] * 10_000 + it[1] }
        applyDisplayFrameRate()
        forceKey = true
        requestKeyframe(false)
    }

    /**
     * Menitipkan target fps ke perangkat supaya kompositor mendorong frame dengan
     * laju yang kita mau, bukan laju bawaan panel.
     *
     * Caranya `Surface.setFrameRate()` pada permukaan encoder - API 30+. Kenapa ini
     * penting: pada perangkat yang panel-nya 60 Hz atau 120 Hz, kompositor akan
     * mendorong frame ke permukaan kita dengan laju penuh panel. Encoder menerima
     * semuanya, membuang kelebihannya di dalam buffer-nya sendiri, dan yang terbuang
     * itu tidak pernah muncul sebagai fps di panel - cuma sebagai panas dan baterai.
     * Lebih buruk: pada sebagian ROM, luapan itu membuat encoder kehabisan waktu dan
     * justru frame yang seharusnya tampil yang ketinggalan.
     *
     * Dengan memberi tahu sistem "permukaan ini dimaksudkan untuk 30 fps", bagian
     * komposisi Android sendiri yang menyaring kelebihannya sebelum sampai ke
     * encoder. priority = 0 (NO_COMPATIBILITY) supaya angka kita tidak dibulatkan
     * diam-diam ke refresh layar terdekat.
     *
     * Bungkus try penuh: perangkat di bawah API 30 atau ROM tanpa API ini cuma akan
     * melewatinya, dan pemanggilnya memanggil ini sebagai pelengkap, bukan syarat.
     */
    private fun applyDisplayFrameRate() {
        val s = inputSurface ?: return
        if (Build.VERSION.SDK_INT < 30) return
        try {
            s.setFrameRate(
                declaredFps.toFloat(),
                android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                android.view.Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
            )
        } catch (_: Exception) {
            // ROM yang tidak mengenali parameter ketiga: coba bentuk dua argumen
            try {
                s.setFrameRate(
                    declaredFps.toFloat(),
                    android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                )
            } catch (_: Exception) {}
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // output path
    // ══════════════════════════════════════════════════════════════════════

    private fun captureCsd(format: MediaFormat) {
        try {
            val sps = bytesOf(format.getByteBuffer("csd-0"))
            val pps = bytesOf(format.getByteBuffer("csd-1"))
            if (sps != null && pps != null) {
                val a = nalToAnnexB(sps)
                val b = nalToAnnexB(pps)
                csd = a + b
                parseCodecString(a)
            }
        } catch (e: Exception) {
            Log.w(TAG, "csd: ${e.message}")
        }
    }

    /**
     * Parameter sets come back in one of three shapes depending on the vendor encoder: Annex-B with
     * a start code, a bare NAL header with no framing at all, or a length prefix. The first is
     * passed through, the second gets a start code glued on, the third is rewritten. Getting this
     * wrong is silent - the decoder simply never finds a parameter set and rejects every chunk.
     */
    private fun nalToAnnexB(src: ByteArray): ByteArray {
        if (src.size < 2) return src
        if (src[0].toInt() == 0 && src[1].toInt() == 0) return toAnnexB(src)   // start code or length
        val nalType = src[0].toInt() and 0x1f
        if (nalType in 1..23) return byteArrayOf(0, 0, 0, 1) + src              // bare NAL
        return toAnnexB(src)
    }

    private fun bytesOf(buf: ByteBuffer?): ByteArray? {
        if (buf == null) return null
        val dup = buf.duplicate()
        dup.rewind()
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return if (out.isEmpty()) null else out
    }

    /**
     * MediaCodec hands AVC back as Annex-B on every device I have seen, but the contract never
     * actually promised it, so the first bytes decide. If the buffer opens with a length prefix
     * instead, it is rewritten into start codes.
     */
    private fun toAnnexB(src: ByteArray): ByteArray {
        if (src.size < 4) return src
        if (src[0].toInt() == 0 && src[1].toInt() == 0 &&
            (src[2].toInt() == 1 || (src[2].toInt() == 0 && src[3].toInt() == 1))) return src

        val out = ByteArrayOutputStream(src.size + 16)
        var i = 0
        while (i + 4 <= src.size) {
            val len = ((src[i].toInt() and 0xff) shl 24) or
                      ((src[i + 1].toInt() and 0xff) shl 16) or
                      ((src[i + 2].toInt() and 0xff) shl 8) or
                      (src[i + 3].toInt() and 0xff)
            if (len <= 0 || i + 4 + len > src.size) return src  // not length prefixed after all
            out.write(0); out.write(0); out.write(0); out.write(1)
            out.write(src, i + 4, len)
            i += 4 + len
        }
        return out.toByteArray()
    }

    /** avc1.PPCCLL straight out of the SPS so the browser can configure its decoder exactly */
    private fun parseCodecString(annexB: ByteArray) {
        var i = 0
        while (i + 8 <= annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 &&
                annexB[i + 2].toInt() == 0 && annexB[i + 3].toInt() == 1) {
                val nalType = annexB[i + 4].toInt() and 0x1f
                if (nalType == 7) {
                    val profile = annexB[i + 5].toInt() and 0xff
                    val constraints = annexB[i + 6].toInt() and 0xff
                    val level = annexB[i + 7].toInt() and 0xff
                    codecString = String.format("avc1.%02X%02X%02X", profile, constraints, level)
                    return
                }
            }
            i++
        }
    }

    /**
     * Is this access unit an IDR, judged from the bitstream?
     *
     * `BUFFER_FLAG_KEY_FRAME` is documented, and plenty of Android encoders never set it. When one of
     * them does not, every IDR after the first frame gets labelled a delta - and the panel uses that
     * label to decide whether it is allowed to start decoding at all. So the moment it resets its
     * decoder for any reason, no later frame is ever accepted as a starting point, and the picture
     * is zero frames forever. That is a permanent freeze produced entirely by a mislabelled flag.
     *
     * The bitstream cannot lie about this. An IDR is NAL type 5; a parameter set is 7; both are
     * unambiguous, both are already being scanned for the codec string, and neither costs anything
     * to check on the way past.
     */
    private fun looksLikeKeyframe(annexB: ByteArray): Boolean {
        var i = 0
        val n = annexB.size
        while (i + 3 < n) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0) {
                var hdr = -1
                if (annexB[i + 2].toInt() == 1) hdr = i + 3
                else if (i + 3 < n && annexB[i + 2].toInt() == 0 && annexB[i + 3].toInt() == 1) hdr = i + 4
                if (hdr in 0 until n) {
                    val nalType = annexB[hdr].toInt() and 0x1f
                    // 5 = IDR slice, 7 = SPS (which this engine glues onto every key frame)
                    if (nalType == 5 || nalType == 7) return true
                    i = hdr
                    continue
                }
            }
            i++
        }
        return false
    }

    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        try {
            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (info.size <= 0) return

            val buf = codec.getOutputBuffer(index) ?: return
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            val raw = ByteArray(info.size)
            buf.get(raw)

            if (isConfig) {
                // some encoders deliver parameter sets here rather than through the format change
                if (csd == null) {
                    val annex = nalToAnnexB(raw)
                    csd = annex
                    parseCodecString(annex)
                }
                return
            }

            // converted first, because the key-frame test reads the start codes and the NAL headers
            var payload = nalToAnnexB(raw)

            val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ||
                        forceKey ||
                        looksLikeKeyframe(payload)
            if (isKey) {
                val param = csd
                if (param != null && param.isNotEmpty()) payload = param + payload
                forceKey = false
                // re-parsed on every key frame rather than once, because the first key frame can
                // arrive before the format change callback has handed over csd-0
                parseCodecString(payload)
            }
            offer(payload, isKey)
        } catch (e: Exception) {
            Log.e(TAG, "output: ${e.message}")
        } finally {
            try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
        }
    }

    /**
     * Bounded hand-off. When the queue is full the oldest chunk goes to make room and an IDR is
     * requested, so the decoder resynchronises on the next frame instead of running on a broken
     * reference chain.
     */
    private fun offer(payload: ByteArray, isKey: Boolean) {
        val s = seq.incrementAndGet()
        val meta = JSONObject().apply {
            put("codec", "h264")
            put("codecStr", codecString)
            put("key", isKey)
            put("seq", s)
            put("w", captureW)
            put("h", captureH)
            put("ts", System.currentTimeMillis())
            put("preset", presetName)
        }
        val chunk = Chunk(meta, payload)

        if (!outQueue.offerLast(chunk)) {
            // The queue is full, which means the uplink is not keeping up. Waiting here is the
            // intended back-pressure: nothing drains the encoder while this call sleeps, so the
            // encoder throttles itself. Cutting the chain instead would put a visible tear in the
            // picture; making the encoder wait only costs frame rate.
            val room = try {
                outQueue.offerLast(chunk, QUEUE_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) { false }

            if (!room) {
                // Genuinely wedged. Sacrifice the OLDEST chunk, never the one in hand - if this one
                // happens to be the key frame, dropping it would leave the panel with no way back in.
                outQueue.pollFirst()
                dropWindow.incrementAndGet()
                requestKeyframe(true)
                if (!outQueue.offerLast(chunk)) return
            }
        }

        sentWindow.incrementAndGet()
        bytesWindow.addAndGet(payload.size.toLong())
        // a chunk made it out, so the encoder is alive. This is the only thing that clears the
        // stall counter, and clearing it here is what stops one slow patch from eventually being
        // read as three separate failures.
        lastChunkAt.set(SystemClock.elapsedRealtime())
        stallCount.set(0)
    }

    // ══════════════════════════════════════════════════════════════════════
    // control
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Panel-requested escape hatch: rebuild with the barest encoder configuration. Reached when a
     * key frame and a normal rebuild have both failed to get the picture moving, which points at the
     * format rather than at the stream.
     */
    fun usePlainConfig() {
        if (plainConfig) return
        plainConfig = true
        Log.w(TAG, "panel asked for the plain encoder configuration")
    }

    fun requestKeyframe(forced: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (forced) {
            val prev = lastForcedKeyAt.get()
            if (now - prev < IDR_MIN_GAP_MS) return
            if (!lastForcedKeyAt.compareAndSet(prev, now)) return
        }
        forceKey = true
        try {
            val bundle = Bundle()
            bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            encoder?.setParameters(bundle)
        } catch (e: Exception) {
            Log.w(TAG, "sync frame: ${e.message}")
        }
    }

    fun onAck(ackSeq: Long, ts: Long, drop: Boolean) {
        val prev = ackedSeq.get()
        if (ackSeq > prev) ackedSeq.compareAndSet(prev, ackSeq)
        if (ts > 0) {
            val lag = (System.currentTimeMillis() - ts).toInt()
            if (lag in 0..20_000) lagMs.set(lag)
        }
        // a chunk that never landed leaves the decoder missing a reference: hand it a fresh
        // starting point instead of letting it paint garbage until the next scheduled IDR
        if (drop) requestKeyframe(true)
    }

    fun updateConfig(cfg: JSONObject) {
        var needRebuild = false

        if (cfg.has("preset")) {
            val name = cfg.optString("preset")
            val p = presets[name]
            if (p != null && name != presetName) {
                presetName = name
                presetBitrate = p.bitrate
                if (p.fps != declaredFps) { declaredFps = p.fps; needRebuild = true }
                if (p.idrSec != idrSeconds) { idrSeconds = p.idrSec; needRebuild = true }
                if (p.scale != captureScale) { captureScale = p.scale; needRebuild = true }
                applyBitrate(p.bitrate)
            }
        }
        if (cfg.has("scale")) {
            val s = cfg.optDouble("scale").toFloat().coerceIn(0.18f, 1.0f)
            if (s != captureScale) { captureScale = s; needRebuild = true }
        }
        if (cfg.has("auto")) auto = cfg.optBoolean("auto")
        if (cfg.has("bitrate")) {
            presetBitrate = cfg.optInt("bitrate").coerceIn(300_000, 20_000_000)
            applyBitrate(presetBitrate)
        }
        if (cfg.has("fps")) {
            /**
             * Batas bawah 24, bukan 10. Target fps di bawah 24 bukan "hemat" - itu cuma
             * bikin gambar tersendat biar hemat bit, dan di layar ponsel 20 fps terlihat
             * jelas patah-patah. Kalau panel memang butuh lebih sedikit bit, yang
             * diturunkan seharusnya resolusi atau bitrate, bukan fps-nya.
             */
            val f = cfg.optInt("fps").coerceIn(24, 60)
            if (f != declaredFps) { declaredFps = f; needRebuild = true }
        }

        // bitrate is a live parameter the encoder accepts at any time; size, rate and GOP length
        // are baked into the configuration, so any of those moving means a fresh encoder
        if (needRebuild) restartPipeline() else { applyDisplayFrameRate(); pushInfo() }
    }

    private fun applyBitrate(bps: Int) {
        val target = bps.coerceIn(300_000, 20_000_000)
        if (target == liveBitrate.get()) return
        liveBitrate.set(target)
        try {
            val bundle = Bundle()
            bundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, target)
            encoder?.setParameters(bundle)
        } catch (e: Exception) {
            Log.w(TAG, "bitrate: ${e.message}")
        }
    }

    /**
     * Rebuilds encoder + virtual display in place. A rotation changes the frame size and an
     * H.264 stream cannot change size mid-GOP, and a preset change to a new scale has to land
     * on a freshly configured encoder. The MediaProjection is kept - stopping it would
     * invalidate the user's grant and the panel would never come back.
     */
    private fun restartPipeline() {
        if (!active.get()) return
        if (!rebuilding.compareAndSet(false, true)) return
        val th = encThread
        if (th == null) { rebuilding.set(false); return }
        Handler(th.looper).post {
            try {
                if (active.get() && projection != null) {
                    teardownEncoder()
                    createEncoder()
                    buildDisplay()
                }
            } catch (e: Exception) {
                Log.e(TAG, "rebuild: ${e.message}")
                // the encoder is gone and cannot be rebuilt on this device right now, so the
                // stream would be a dead frame from here on. Hand it back to the JPEG engine.
                if (active.get()) {
                    active.set(false)
                    teardownEncoder()
                    try { sink.onEngineFailure(e.message ?: "rebuild failed") } catch (_: Exception) {}
                }
            } finally {
                rebuilding.set(false)
                pushInfo()
            }
        }
    }

    private var controlHandler: Handler? = null

    private fun startControlLoop() {
        val handler = controlHandler ?: Handler(Looper.getMainLooper()).also { controlHandler = it }
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
        val bytes = bytesWindow.getAndSet(0)
        val drops = dropWindow.getAndSet(0)

        if (!sink.socketConnected()) { pushInfo(); return }

        reportedFps = if (dt > 0) ((sent * 1000L) / dt).toInt() else 0
        reportedKbps = if (dt > 0) ((bytes * 8000L) / dt / 1000L).toInt() else 0

        val lag = lagMs.get()
        if (drops > 0) requestKeyframe(true)

        // ── stall watchdog ──────────────────────────────────────────────
        // A picture that arrives once and then never changes is not a slow link. It is an encoder
        // or a virtual display that has stopped producing, and nothing the panel does can fix it -
        // the panel can only ask for frames that are no longer being made. So it is caught here.
        //
        // The encoder is told to repeat the previous frame every second, so a screen nobody is
        // touching still produces output. Silence past that is a real stall. Poke for a key frame
        // first, because that is cheap and usually enough; only rebuild the pipeline if the poke
        // does nothing. The counter is cleared by any chunk that comes out, so it takes a stall
        // that survives a rebuild to push it up - one bad patch is not a failure.
        val quiet = now - lastChunkAt.get()
        lastStallMs = quiet.toInt()
        if (quiet > STALL_MS) {
            requestKeyframe(false)
            if (quiet > STALL_MS * 2L && now - lastStallHandledAt > STALL_MS) {
                lastStallHandledAt = now
                stallCount.incrementAndGet()
                val n = rebuildCount.incrementAndGet()
                // Second stall: the tuned format itself may be what this encoder cannot handle. Some
                // vendor encoders accept keys like `latency` or `max-fps-to-encoder` and then never
                // emit a frame at all - the configuration is legal and the output is silence. Drop to
                // the barest format before concluding that the whole H.264 path is unusable here.
                if (stallCount.get() >= 2 && !plainConfig) {
                    plainConfig = true
                    Log.w(TAG, "second stall - rebuilding with the plain encoder configuration")
                }
                Log.w(TAG, "no output for ${quiet}ms - rebuilding encoder + virtual display (#$n)")
                restartPipeline()
                if (stallCount.get() >= STALL_LIMIT) {
                    Log.e(TAG, "still nothing after $STALL_LIMIT stalls - handing the job to JPEG")
                    sink.onEngineFailure("encoder stalled")
                    return
                }
            }
        }

        // Motion first, pixels second. When the path tightens the bitrate drops so the frame
        // rate can hold - a softer picture that keeps up with the finger beats a sharp one that
        // arrives late.
        if (auto) {
            val base = presetBitrate
            /**
             * Lantai 40%, bukan 30% seperti sebelumnya. 30% dari 1.8 Mbps cuma 540
             * kbps, dan pada resolusi ini itu sudah cukup rendah sampai encoder mulai
             * membuang detail besar - dan begitu gambar jadi blok-blok, justru
             * bitrate-nya yang jadi mahal, karena setiap frame berikutnya harus
             * memperbaiki blok yang salah. Lantai 40% berhenti tepat sebelum titik itu,
             * jadi "turun kualitas" tetap berarti gambar yang masih enak dilihat, bukan
             * gambar yang bikin panel minta lebih banyak lagi.
             */
            val floor = (base * 0.40f).toInt()
            val cur = liveBitrate.get()
            when {
                // delay parah: lompat lebih tegas, karena menahan bitrate di sini cuma bikin
                // tumpukan makin panjang dan delay-nya makin parah
                lag > 700 -> applyBitrate((cur * 0.65f).toInt().coerceAtLeast(floor))
                lag > 400 -> applyBitrate((cur * 0.82f).toInt().coerceAtLeast(floor))
                // Delay sudah enak: kembalikan pelan-pelan, tapi jangan lebih cepat dari
                // 15% per detik. Naik terlalu cepat = osilasi, dan osilasi kelihatan
                // sebagai gambar yang tajam-keruh-tajam-keruh - lebih mengganggu
                // daripada gambar yang mantap agak lembut.
                lag < 100 && cur < base -> applyBitrate((cur * 1.15f).toInt().coerceAtMost(base))
            }
        }

        limiter = when {
            sent == 0 -> "senyap"
            lag > 600 -> "delay"
            drops > 0 -> "paket"
            else -> "ok"
        }
        pushInfo()
    }

    private fun pushInfo() {
        try { sink.emitStreamStatus(streamInfo().apply { put("screenActive", active.get()) }) }
        catch (_: Exception) {}
    }

    fun streamInfo(): JSONObject = JSONObject().apply {
        put("mode", "h264")
        put("codec", "h264")
        put("codecStr", codecString)
        put("preset", presetName)
        put("screenStream", active.get())
        put("capScale", captureScale.toDouble())
        put("capW", captureW)
        put("capH", captureH)
        put("quality", liveBitrate.get() / 1000)
        put("bitrate", liveBitrate.get())
        put("fpsTarget", declaredFps)
        put("fpsActual", reportedFps)
        put("kbps", reportedKbps)
        put("auto", auto)
        put("limiter", limiter)
        put("cores", Runtime.getRuntime().availableProcessors())
        // diagnostics: enough to tell a stalled encoder from a slow link from a saturated queue
        // without a logcat. The panel shows them in the chip's tooltip.
        put("idleMs", lastStallMs)
        put("stalls", stallCount.get())
        put("rebuilds", rebuildCount.get())
        put("queue", outQueue.size)
        put("chunks", seq.get())
        put("plain", plainConfig)
    }

    // ══════════════════════════════════════════════════════════════════════
    // rotation / size changes
    // ══════════════════════════════════════════════════════════════════════

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val d = TouchInjector.displaySize(ctx)
            val key = d[0] * 10_000 + d[1]
            if (lastDimsKey != 0 && key != lastDimsKey) {
                lastDimsKey = key
                restartPipeline()
            }
        }
    }

    private fun startDisplayWatcher() {
        try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager = dm
            lastDimsKey = TouchInjector.displaySize(ctx).let { it[0] * 10_000 + it[1] }
            dm.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {}
    }

    private fun stopDisplayWatcher() {
        try { displayManager?.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        displayManager = null
    }
}
