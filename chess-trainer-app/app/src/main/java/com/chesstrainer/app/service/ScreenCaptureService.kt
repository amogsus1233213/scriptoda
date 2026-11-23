package com.chesstrainer.app.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Foreground service that mirrors the screen into an [ImageReader] via [MediaProjection]
 * and delivers throttled frames as [Bitmap]s to [listener].
 *
 * The delivered bitmap is reused between frames and is only valid for the duration of
 * [FrameListener.onFrame]. Consumers that need to keep it must copy it
 * (e.g. `bitmap.copy(Bitmap.Config.ARGB_8888, false)`) and must never recycle it.
 * Callbacks are invoked on a dedicated background thread.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"

        const val EXTRA_RESULT_CODE = "com.chesstrainer.app.extra.RESULT_CODE"
        const val EXTRA_DATA = "com.chesstrainer.app.extra.DATA"
        const val EXTRA_TARGET_FPS = "com.chesstrainer.app.extra.TARGET_FPS"
        const val EXTRA_SCALE = "com.chesstrainer.app.extra.SCALE"

        const val ACTION_STOP = "com.chesstrainer.app.action.STOP_SCREEN_CAPTURE"

        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1001
        private const val VIRTUAL_DISPLAY_NAME = "ChessTrainerCapture"

        private const val DEFAULT_SCALE = 0.5f
        private const val DEFAULT_FPS = 2f
        private const val MAX_IMAGES = 2
        private const val WIDTH_ALIGNMENT = 16
        private const val HEIGHT_ALIGNMENT = 2

        fun interface FrameListener {
            fun onFrame(bitmap: Bitmap)
        }

        /** Set externally to receive frames. Invoked on the capture thread. */
        @Volatile
        var listener: FrameListener? = null
    }

    private data class CaptureSize(val width: Int, val height: Int, val densityDpi: Int)

    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler

    // All fields below are only touched on the capture thread (after onCreate).
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var currentSize: CaptureSize? = null
    private var frameBitmap: Bitmap? = null
    private var packedBuffer: ByteBuffer? = null
    private var lastFrameTimeMs = 0L

    @Volatile
    private var scale = DEFAULT_SCALE

    @Volatile
    private var frameIntervalMs = (1000f / DEFAULT_FPS).toLong()

    @Volatile
    private var stopping = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system/user")
            if (!stopping) {
                stopSelf()
            }
        }
    }

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (::captureHandler.isInitialized) {
                captureHandler.post { handleDisplayChange() }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        onImageAvailable(reader)
    }

    // ---------------------------------------------------------------------------------------
    // Service lifecycle
    // ---------------------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("ScreenCapture", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        captureHandler = Handler(captureThread.looper)
        createNotificationChannel()
        registerComponentCallbacks(configCallbacks)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == "com.chesstrainer.app.action.CALIBRATE") {
            val app = com.chesstrainer.app.ChessTrainerApp.get(this)
            app.coordinator.requestCalibration()
            return START_NOT_STICKY
        }

        applyConfig(intent)

        // Android 14: the service must be in the foreground with type mediaProjection
        // before getMediaProjection() is called.
        try {
            startInForeground()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)

        if (mediaProjection != null) {
            // Already capturing; a projection token can only be used once, so only the
            // FPS/scale settings are updated.
            captureHandler.post { handleDisplayChange() }
            return START_NOT_STICKY
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "Missing or invalid MediaProjection permission result")
            stopSelf()
            return START_NOT_STICKY
        }

        val projection = try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }

        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        mediaProjection = projection
        captureHandler.post { startCapture(projection) }

        // The projection token cannot survive process death.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        unregisterComponentCallbacks(configCallbacks)
        captureHandler.post { teardown() }
        captureThread.quitSafely()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Capture setup / teardown (capture thread)
    // ---------------------------------------------------------------------------------------

    private fun startCapture(projection: MediaProjection) {
        if (stopping) return
        // Android 14: callback must be registered before createVirtualDisplay().
        projection.registerCallback(projectionCallback, captureHandler)

        val size = computeCaptureSize()
        val reader = createImageReader(size)

        val display = try {
            projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                size.width,
                size.height,
                size.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            null
        }

        if (display == null) {
            reader.close()
            stopSelf()
            return
        }

        imageReader = reader
        virtualDisplay = display
        currentSize = size
        lastFrameTimeMs = 0L
        Log.i(TAG, "Capture started at ${size.width}x${size.height} @${size.densityDpi}dpi")
    }

    private fun handleDisplayChange() {
        if (stopping) return
        val display = virtualDisplay ?: return
        val newSize = computeCaptureSize()
        if (newSize == currentSize) return

        val oldReader = imageReader
        val newReader = createImageReader(newSize)

        // Resize in place; the MediaProjection (and its one-shot token) is kept alive.
        display.resize(newSize.width, newSize.height, newSize.densityDpi)
        display.surface = newReader.surface

        imageReader = newReader
        currentSize = newSize

        oldReader?.setOnImageAvailableListener(null, null)
        oldReader?.close()

        Log.i(TAG, "Capture resized to ${newSize.width}x${newSize.height}")
    }

    private fun createImageReader(size: CaptureSize): ImageReader =
        ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, MAX_IMAGES).apply {
            setOnImageAvailableListener(imageAvailableListener, captureHandler)
        }

    private fun teardown() {
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        frameBitmap?.recycle()
        frameBitmap = null
        packedBuffer = null
        currentSize = null

        mediaProjection?.let { projection ->
            projection.unregisterCallback(projectionCallback)
            projection.stop()
        }
        mediaProjection = null
    }

    // ---------------------------------------------------------------------------------------
    // Frame handling (capture thread)
    // ---------------------------------------------------------------------------------------

    private fun onImageAvailable(reader: ImageReader) {
        // Always drain the reader so the producer never stalls (maxImages = 2);
        // acquireLatestImage() drops any stale frames.
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "acquireLatestImage failed", e)
            null
        } ?: return

        try {
            if (stopping || reader !== imageReader) return

            val now = SystemClock.elapsedRealtime()
            if (lastFrameTimeMs != 0L && now - lastFrameTimeMs < frameIntervalMs) return

            val target = listener ?: return
            val bitmap = imageToBitmap(image) ?: return
            lastFrameTimeMs = now

            try {
                target.onFrame(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "FrameListener threw", e)
            }
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (width <= 0 || height <= 0 || pixelStride <= 0) return null

        val bitmap = obtainBitmap(width, height)
        val source = plane.buffer
        source.rewind()

        val tightRowBytes = width * pixelStride
        val paddedWidth = rowStride / pixelStride

        if (paddedWidth == width) {
            bitmap.copyPixelsFromBuffer(source)
        } else {
            // Strip the per-row padding into a reused, tightly packed buffer.
            val packed = obtainPackedBuffer(tightRowBytes * height)
            val rowBytes = ByteArray(tightRowBytes)
            for (row in 0 until height) {
                source.position(row * rowStride)
                source.get(rowBytes, 0, tightRowBytes)
                packed.put(rowBytes)
            }
            packed.rewind()
            bitmap.copyPixelsFromBuffer(packed)
        }
        return bitmap
    }

    private fun obtainBitmap(width: Int, height: Int): Bitmap {
        val existing = frameBitmap
        if (existing != null && !existing.isRecycled &&
            existing.width == width && existing.height == height
        ) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { frameBitmap = it }
    }

    private fun obtainPackedBuffer(capacity: Int): ByteBuffer {
        val existing = packedBuffer
        val buffer = if (existing != null && existing.capacity() == capacity) {
            existing
        } else {
            ByteBuffer.allocateDirect(capacity).also { packedBuffer = it }
        }
        buffer.clear()
        return buffer
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun applyConfig(intent: Intent) {
        val requestedScale = intent.getFloatExtra(EXTRA_SCALE, scale)
        scale = if (requestedScale > 0f && requestedScale <= 1f) requestedScale else DEFAULT_SCALE

        val requestedFps = intent.getFloatExtra(EXTRA_TARGET_FPS, Float.NaN).takeIf { !it.isNaN() }
            ?: intent.getIntExtra(EXTRA_TARGET_FPS, -1).takeIf { it > 0 }?.toFloat()
        if (requestedFps != null) {
            val fps = if (requestedFps > 0f) requestedFps else DEFAULT_FPS
            frameIntervalMs = (1000f / fps).toLong()
        }
    }

    private fun computeCaptureSize(): CaptureSize {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (screenWidth, screenHeight) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }

        val s = scale
        val width = alignDown((screenWidth * s).roundToInt(), WIDTH_ALIGNMENT)
        val height = alignDown((screenHeight * s).roundToInt(), HEIGHT_ALIGNMENT)
        val density = max(1, (resources.displayMetrics.densityDpi * s).roundToInt())
        return CaptureSize(width, height, density)
    }

    private fun alignDown(value: Int, alignment: Int): Int =
        max(alignment, value - value % alignment)

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while the board is being captured from the screen"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Chess Trainer")
            .setContentText("Capturing screen for board analysis")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent()
            )
            .build()

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
