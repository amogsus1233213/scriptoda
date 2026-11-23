package com.chesstrainer.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// TemplateManager
// ---------------------------------------------------------------------------------------------

/**
 * Downloads, caches and prepares the chess.com "neo" piece reference images used by
 * [TemplateMatchingClassifier].
 *
 * The 12 PNGs (about 17 KB in total) are fetched once from the chess.com CDN into
 * `filesDir/neo_templates/` and loaded from there afterwards. All network and disk work runs on a
 * background thread started by [ensureLoaded] (or implicitly by [templates]); callers never
 * block. If anything fails (no network, HTTP error, corrupt file) the manager stays not-ready,
 * recognition continues with the other classifiers, and loading is retried at most once per
 * [RETRY_INTERVAL_MS].
 */
class TemplateManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "TemplateManager"

        const val BASE_URL = "https://images.chesscomfiles.com/chess-themes/pieces/neo"
        const val IMAGE_SIZE = 150
        const val CACHE_DIR = "neo_templates"

        /** File stems: colour (w/b) + piece (p, n, b, r, q, k). */
        val PIECE_NAMES: List<String> = listOf("w", "b").flatMap { c -> "pnbrqk".map { p -> "$c$p" } }

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_IMAGE_BYTES = 512 * 1024
        private const val RETRY_INTERVAL_MS = 60_000L
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )

        @Volatile
        private var instance: TemplateManager? = null

        fun getInstance(context: Context): TemplateManager =
            instance ?: synchronized(this) {
                instance ?: TemplateManager(context.applicationContext).also { instance = it }
            }
    }

    @Volatile
    private var set: NeoTemplateSet? = null

    private val loading = AtomicBoolean(false)

    @Volatile
    private var lastFailureAt = Long.MIN_VALUE

    /** True once the templates are downloaded and prepared. */
    val isReady: Boolean get() = set != null

    /** The prepared templates, or null (loading is then started in the background). */
    fun templates(): NeoTemplateSet? {
        val s = set
        if (s == null) ensureLoaded()
        return s
    }

    /** Starts loading (cache first, then download) on a background thread. Never blocks. */
    fun ensureLoaded() {
        if (set != null) return
        val failedAt = lastFailureAt
        if (failedAt != Long.MIN_VALUE && SystemClock.elapsedRealtime() - failedAt < RETRY_INTERVAL_MS) return
        if (!loading.compareAndSet(false, true)) return
        val thread = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val loaded = load()
                if (loaded != null) {
                    set = loaded
                    Log.i(TAG, "Neo piece templates ready")
                } else {
                    lastFailureAt = SystemClock.elapsedRealtime()
                }
            } catch (t: Throwable) {
                // Includes OutOfMemoryError: never take the app down for an optional feature.
                Log.w(TAG, "Loading piece templates failed", t)
                lastFailureAt = SystemClock.elapsedRealtime()
            } finally {
                loading.set(false)
            }
        }, "neo-template-loader")
        thread.isDaemon = true
        thread.start()
    }

    private fun load(): NeoTemplateSet? {
        val dir = File(appContext.filesDir, CACHE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "Cannot create ${dir.path}")
            return null
        }
        val bitmaps = HashMap<String, Bitmap>()
        try {
            for (name in PIECE_NAMES) {
                val file = File(dir, "$name.png")
                var bmp = decode(file)
                if (bmp == null) {
                    file.delete() // missing or corrupt: fetch it again
                    if (!download("$BASE_URL/$IMAGE_SIZE/$name.png", file)) return null
                    bmp = decode(file)
                    if (bmp == null) {
                        Log.w(TAG, "Downloaded $name.png does not decode")
                        file.delete()
                        return null
                    }
                }
                bitmaps[name] = bmp
            }
            val prepared = NeoTemplateSet.fromBitmaps(bitmaps)
            if (prepared == null) {
                // Decodable but unusable (e.g. fully transparent): drop the cache, retry later.
                Log.w(TAG, "Piece templates are invalid; clearing cache")
                for (name in PIECE_NAMES) File(dir, "$name.png").delete()
            }
            return prepared
        } finally {
            for (b in bitmaps.values) b.recycle()
        }
    }

    private fun decode(file: File): Bitmap? {
        if (!file.isFile || file.length() == 0L) return null
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        return try {
            BitmapFactory.decodeFile(file.path, opts)?.takeIf { it.width >= 20 && it.height >= 20 }
        } catch (e: RuntimeException) {
            null
        }
    }

    /** Downloads [url] into [target] atomically (temp file + rename). */
    private fun download(url: String, target: File): Boolean {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", "ChessTrainer")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Template download failed: HTTP $code for $url")
                return false
            }
            val bytes = conn.inputStream.use { readLimited(it, MAX_IMAGE_BYTES) }
            if (bytes == null) {
                Log.w(TAG, "Template too large: $url")
                return false
            }
            if (bytes.size < PNG_SIGNATURE.size || !PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) {
                Log.w(TAG, "Template is not a PNG: $url")
                return false
            }
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
            return true
        } catch (e: IOException) {
            Log.w(TAG, "Template download failed for $url: ${e.message}")
            return false
        } catch (e: SecurityException) {
            Log.w(TAG, "No network permission for template download", e)
            return false
        } finally {
            conn?.disconnect()
            tmp.delete()
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            if (buf.size() + n > limit) return null
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }
}

// ---------------------------------------------------------------------------------------------
// NeoTemplateSet
// ---------------------------------------------------------------------------------------------

/** A template composited onto a background: [size] x [size] grayscale plus its anchor. */
class CompositedTemplate(val size: Int, val gray: FloatArray, val anchor: PieceAnchor.Anchor)

/** All templates of one colour composited on [background]: templates[type][scaleIndex]. */
class CompositedBank(val background: Float, val templates: Array<Array<CompositedTemplate?>>)

/**
 * Prepared "neo" templates: per colour, type and scale the alpha mask and alpha-premultiplied
 * luma, area-resampled from the 150 px PNGs to the size they have in the 32x32 wide sample.
 *
 * Compositing onto a background is done in grayscale (`g = premul + (1 - alpha) * bg`), which
 * is exact because luma is linear in RGB. Composites are cached per colour and quantized
 * background luma (LRU, [MAX_CACHED_BANKS] entries of ~130 KB each).
 */
class NeoTemplateSet private constructor(
    /** rendered[colour][type][scale], colour 0 = white, 1 = black; type order p n b r q k. */
    private val rendered: Array<Array<Array<Rendered>>>,
) {
    private class Rendered(val size: Int, val alpha: FloatArray, val premul: FloatArray)

    companion object {
        private const val TYPES = "pnbrqk"
        private const val BG_QUANTUM = 4
        private const val MAX_CACHED_BANKS = 12

        /**
         * Builds the set from the 12 bitmaps keyed by [TemplateManager.PIECE_NAMES]. Returns null
         * if any is missing or unusable. The bitmaps are not recycled.
         */
        fun fromBitmaps(
            bitmaps: Map<String, Bitmap>,
            canvas: Int = CellReader.WIDE_SIZE,
            sampledFraction: Float = CellReader.WIDE_FRACTION,
            scales: FloatArray = TemplateMatchingClassifier.SCALES,
        ): NeoTemplateSet? {
            // The PNG covers a whole square; the wide sample covers [sampledFraction] of it.
            val sizes = IntArray(scales.size) { max(4, (canvas * scales[it] / sampledFraction).roundToInt()) }
            val rendered = Array(2) { colour ->
                Array(TYPES.length) { t ->
                    val name = (if (colour == 0) "w" else "b") + TYPES[t]
                    val bmp = bitmaps[name] ?: return null
                    if (bmp.isRecycled) return null
                    val w = bmp.width
                    val h = bmp.height
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    val alpha = FloatArray(w * h)
                    val premul = FloatArray(w * h)
                    var opaque = 0
                    for (i in px.indices) {
                        val c = px[i]
                        val a = ((c ushr 24) and 0xFF) / 255f
                        if (a > 0.5f) opaque++
                        val l = CellReader.luma(((c shr 16) and 0xFF).toFloat(), ((c shr 8) and 0xFF).toFloat(), (c and 0xFF).toFloat())
                        alpha[i] = a
                        premul[i] = l * a
                    }
                    if (opaque < w * h / 50) return null // (almost) fully transparent: corrupt
                    Array(sizes.size) { s ->
                        val n = sizes[s]
                        Rendered(n, resampleArea(alpha, w, h, n, n), resampleArea(premul, w, h, n, n))
                    }
                }
            }
            return NeoTemplateSet(rendered)
        }

        /** Area (box) resampling with fractional source coverage; separable. */
        private fun resampleArea(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
            val (xStart, xWeights) = areaWeights(sw, dw)
            val (yStart, yWeights) = areaWeights(sh, dh)
            val tmp = FloatArray(dw * sh)
            for (y in 0 until sh) {
                val row = y * sw
                for (x in 0 until dw) {
                    val ws = xWeights[x]
                    val s0 = xStart[x]
                    var acc = 0f
                    for (k in ws.indices) acc += ws[k] * src[row + s0 + k]
                    tmp[y * dw + x] = acc
                }
            }
            val out = FloatArray(dw * dh)
            for (y in 0 until dh) {
                val ws = yWeights[y]
                val s0 = yStart[y]
                for (x in 0 until dw) {
                    var acc = 0f
                    for (k in ws.indices) acc += ws[k] * tmp[(s0 + k) * dw + x]
                    out[y * dw + x] = acc
                }
            }
            return out
        }

        /** For each destination index: first source index and normalized overlap weights. */
        private fun areaWeights(srcLen: Int, dstLen: Int): Pair<IntArray, Array<FloatArray>> {
            val ratio = srcLen.toFloat() / dstLen
            val starts = IntArray(dstLen)
            val weights = Array(dstLen) { d ->
                val lo = d * ratio
                val hi = min(srcLen.toFloat(), (d + 1) * ratio)
                val i0 = floor(lo).toInt().coerceIn(0, srcLen - 1)
                val i1 = ceil(hi).toInt().coerceIn(i0 + 1, srcLen)
                starts[d] = i0
                val w = FloatArray(i1 - i0) { k ->
                    val i = i0 + k
                    max(0f, min(hi, i + 1f) - max(lo, i.toFloat()))
                }
                val total = w.sum()
                if (total > 0f) for (k in w.indices) w[k] /= total
                w
            }
            return Pair(starts, weights)
        }
    }

    private val cache = object : LinkedHashMap<Int, CompositedBank>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CompositedBank>?): Boolean =
            size > MAX_CACHED_BANKS
    }

    /** Templates of one colour composited on (quantized) [backgroundLuma]. Thread-safe. */
    fun composited(white: Boolean, backgroundLuma: Float): CompositedBank {
        val bucket = (backgroundLuma / BG_QUANTUM).toInt().coerceIn(0, 255 / BG_QUANTUM)
        val key = (if (white) 0 else 1000) + bucket
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        val bg = (bucket * BG_QUANTUM + BG_QUANTUM / 2f).coerceAtMost(255f)
        val colour = rendered[if (white) 0 else 1]
        val templates = Array(colour.size) { t ->
            Array(colour[t].size) { s ->
                val r = colour[t][s]
                val gray = FloatArray(r.size * r.size) { i -> r.premul[i] + (1f - r.alpha[i]) * bg }
                val anchor = PieceAnchor.find(gray, r.size, r.size, bg, null)
                if (anchor == null) null else CompositedTemplate(r.size, gray, anchor)
            }
        }
        val bank = CompositedBank(bg, templates)
        synchronized(cache) { cache[key] = bank }
        return bank
    }
}

// ---------------------------------------------------------------------------------------------
// PieceAnchor
// ---------------------------------------------------------------------------------------------

/**
 * The reference point used to align templates with captured squares: the bottom-most foreground
 * row (all neo pieces share one baseline) and the mean x of the foreground pixels. The same rule
 * is applied to captured squares and to composited templates so both anchors are comparable.
 */
object PieceAnchor {
    /** Luma difference from the background above which a pixel is foreground. */
    const val FOREGROUND_DIFF = 30f

    /** A row counts as part of the piece once it has this many foreground pixels. */
    const val MIN_ROW_PIXELS = 2

    class Anchor(val bottom: Int, val cx: Float)

    /** [valid] (optional, w*h) excludes pixels such as label corners. */
    fun find(gray: FloatArray, w: Int, h: Int, background: Float, valid: BooleanArray?): Anchor? {
        var bottom = -1
        var sumX = 0.0
        var count = 0
        for (y in 0 until h) {
            var rowCount = 0
            for (x in 0 until w) {
                val k = y * w + x
                if (valid != null && !valid[k]) continue
                if (kotlin.math.abs(gray[k] - background) > FOREGROUND_DIFF) {
                    rowCount++
                    sumX += x
                    count++
                }
            }
            if (rowCount >= MIN_ROW_PIXELS) bottom = y
        }
        if (bottom < 0 || count == 0) return null
        return Anchor(bottom, (sumX / count).toFloat())
    }
}
