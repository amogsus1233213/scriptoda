package com.chesstrainer.app.ui

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Colors used for move classification badges and arrows. */
object MoveColors {
    val BEST = Color.parseColor("#96BC4B")
    val GOOD = Color.parseColor("#7FA650")
    val GREAT = Color.parseColor("#5BAAEF")
    val INACCURACY = Color.parseColor("#F7C631")
    val MISTAKE = Color.parseColor("#E58F2A")
    val BLUNDER = Color.parseColor("#CA3431")
    val NEUTRAL = Color.parseColor("#5D5A55")

    /** Maps a classification label (case-insensitive) to its color. */
    fun forLabel(label: String): Int = when (label.trim().lowercase(Locale.ROOT)) {
        "best", "brilliant" -> BEST
        "good", "excellent" -> GOOD
        "great" -> GREAT
        "inaccuracy" -> INACCURACY
        "mistake" -> MISTAKE
        "blunder" -> BLUNDER
        else -> NEUTRAL
    }
}

private fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

private fun Context.dpInt(value: Float): Int = dp(value).roundToInt()

/**
 * Foreground service hosting two overlay windows:
 *  1. A draggable pill / expandable coach panel (touches outside pass through).
 *  2. A full-screen, fully touch-transparent layer drawing the suggested move arrow.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.chesstrainer.app.action.STOP_OVERLAY"
        private const val CHANNEL_ID = "chess_overlay"
        private const val NOTIFICATION_ID = 2002

        /** Currently running service instance, or null if the overlay is not shown. */
        @Volatile
        var instance: OverlayService? = null
            private set

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
    }

    private data class Suggestion(
        val from: String,
        val to: String,
        val evalCp: Int?,
        val mateIn: Int?,
        val label: String,
        val labelColor: Int,
        val isFlipped: Boolean,
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var uiContext: Context
    private lateinit var windowManager: WindowManager

    private var arrowView: ArrowView? = null
    private var panelRoot: FrameLayout? = null
    private lateinit var panelParams: WindowManager.LayoutParams

    private lateinit var pillView: TextView
    private lateinit var expandedView: LinearLayout
    private lateinit var contentLayout: LinearLayout
    private lateinit var badgeView: TextView
    private lateinit var moveText: TextView
    private lateinit var evalText: TextView
    private lateinit var evalBar: EvalBarView

    private var expanded = false
    private var lastScreen = Point()
    private var currentSuggestion: Suggestion? = null
    private var portraitBounds: RectF? = null
    private var landscapeBounds: RectF? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)

        if (!canDrawOverlays(this)) {
            stopSelf()
            return
        }

        uiContext = createOverlayContext()
        windowManager = uiContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        lastScreen = screenSize()

        addArrowWindow()
        addPanelWindow()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        arrowView?.let { removeWindow(it) }
        panelRoot?.let { removeWindow(it) }
        arrowView = null
        panelRoot = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (panelRoot == null) return
        // Window metrics may lag behind the configuration callback, so defer one frame.
        mainHandler.post { handleScreenChange() }
    }

    // ---------------------------------------------------------------------------------------
    // Public API (thread-safe: all calls are marshalled onto the main thread)
    // ---------------------------------------------------------------------------------------

    fun showSuggestion(
        from: String,
        to: String,
        evalCp: Int?,
        mateIn: Int?,
        label: String,
        labelColor: Int,
        isFlipped: Boolean,
    ) {
        val suggestion = Suggestion(from, to, evalCp, mateIn, label, labelColor, isFlipped)
        mainHandler.post {
            currentSuggestion = suggestion
            renderSuggestion()
        }
    }

    fun clearSuggestion() {
        mainHandler.post {
            currentSuggestion = null
            renderSuggestion()
        }
    }

    /** Board bounds as ratios (0..1) of the full screen size, for each orientation. */
    fun setBoardBounds(portrait: RectF, landscape: RectF) {
        val p = RectF(portrait)
        val l = RectF(landscape)
        mainHandler.post {
            portraitBounds = p
            landscapeBounds = l
            arrowView?.setBoardBounds(p, l)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Window setup
    // ---------------------------------------------------------------------------------------

    private fun createOverlayContext(): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager = getSystemService(DisplayManager::class.java)
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            this
        }
    }

    private fun addArrowWindow() {
        val view = ArrowView(uiContext) { screenSize() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // Android 12+ blocks touches passing through overlays more opaque than 0.8.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alpha = 0.8f
            }
        }
        windowManager.addView(view, params)
        arrowView = view
    }

    private fun addPanelWindow() {
        val root = FrameLayout(uiContext)
        pillView = buildPill()
        expandedView = buildPanel()
        root.addView(pillView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(expandedView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        expandedView.visibility = View.GONE

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = uiContext.dpInt(12f)
            y = (lastScreen.y * 0.15f).roundToInt()
        }

        applyOrientationLayout()
        renderSuggestion()
        windowManager.addView(root, panelParams)
        panelRoot = root
    }

    private fun removeWindow(view: View) {
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
            // View was never attached or already removed.
        }
    }

    // ---------------------------------------------------------------------------------------
    // View construction
    // ---------------------------------------------------------------------------------------

    private fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = uiContext.dp(radiusDp)
            setColor(color)
        }

    private fun buildPill(): TextView = TextView(uiContext).apply {
        text = "♞"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        gravity = Gravity.CENTER
        val h = uiContext.dpInt(14f)
        val v = uiContext.dpInt(6f)
        setPadding(h, v, h, v)
        background = roundedBackground(Color.parseColor("#E6262421"), 24f)
        elevation = uiContext.dp(6f)
        contentDescription = "Chess coach"
        setOnClickListener { setExpanded(true) }
        setOnTouchListener(DragTouchListener())
    }

    private fun buildPanel(): LinearLayout {
        val ctx = uiContext
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.parseColor("#262421"), 14f)
            elevation = ctx.dp(8f)
            val p = ctx.dpInt(12f)
            setPadding(p, ctx.dpInt(8f), p, p)
        }

        val header = TextView(ctx).apply {
            text = "Coach ▾"
            setTextColor(Color.parseColor("#BABABA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            val v = ctx.dpInt(4f)
            setPadding(0, v, 0, v + ctx.dpInt(4f))
            setOnClickListener { setExpanded(false) }
            setOnTouchListener(DragTouchListener())
        }
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        contentLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = ctx.dpInt(130f)
        }

        badgeView = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            val h = ctx.dpInt(8f)
            val v = ctx.dpInt(3f)
            setPadding(h, v, h, v)
            background = roundedBackground(MoveColors.NEUTRAL, 6f)
        }
        info.addView(badgeView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        moveText = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        info.addView(moveText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpInt(6f)
        })

        evalText = TextView(ctx).apply {
            setTextColor(Color.parseColor("#D0D0D0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
        }
        info.addView(evalText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpInt(2f)
        })

        evalBar = EvalBarView(ctx)

        contentLayout.addView(info)
        contentLayout.addView(evalBar)
        panel.addView(contentLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return panel
    }

    /** Portrait: vertical stack with horizontal bar. Landscape: side by side with vertical bar. */
    private fun applyOrientationLayout() {
        val landscape = isLandscape()
        val ctx = uiContext
        val barThickness = ctx.dpInt(10f)
        val gap = ctx.dpInt(10f)
        if (landscape) {
            contentLayout.orientation = LinearLayout.HORIZONTAL
            evalBar.vertical = true
            evalBar.layoutParams = LinearLayout.LayoutParams(barThickness, ctx.dpInt(84f)).apply {
                marginStart = gap
                gravity = Gravity.CENTER_VERTICAL
            }
        } else {
            contentLayout.orientation = LinearLayout.VERTICAL
            evalBar.vertical = false
            evalBar.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barThickness).apply {
                topMargin = gap
            }
        }
        contentLayout.requestLayout()
    }

    // ---------------------------------------------------------------------------------------
    // State / rendering
    // ---------------------------------------------------------------------------------------

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        pillView.visibility = if (value) View.GONE else View.VISIBLE
        expandedView.visibility = if (value) View.VISIBLE else View.GONE
        updatePanelWindow()
        // Once the new content is measured, make sure it is still fully on screen.
        panelRoot?.post { clampAndUpdate() }
    }

    private fun renderSuggestion() {
        val s = currentSuggestion
        if (s == null) {
            badgeView.text = "Waiting"
            (badgeView.background as GradientDrawable).setColor(MoveColors.NEUTRAL)
            moveText.text = "—"
            evalText.text = ""
            evalBar.setEvaluation(0, null, false)
            arrowView?.clearArrow()
            return
        }
        badgeView.text = s.label
        (badgeView.background as GradientDrawable).setColor(s.labelColor)
        moveText.text = "${s.from} → ${s.to}"
        evalText.text = formatEval(s.evalCp, s.mateIn)
        evalBar.setEvaluation(s.evalCp ?: 0, s.mateIn, s.isFlipped)
        arrowView?.setArrow(s.from, s.to, s.labelColor, s.isFlipped)
    }

    private fun formatEval(evalCp: Int?, mateIn: Int?): String = when {
        mateIn != null && mateIn != 0 -> if (mateIn > 0) "M$mateIn" else "-M${abs(mateIn)}"
        evalCp != null -> {
            val pawns = evalCp / 100.0
            when {
                evalCp > 0 -> String.format(Locale.US, "+%.1f", pawns)
                evalCp < 0 -> String.format(Locale.US, "%.1f", pawns)
                else -> "0.0"
            }
        }
        else -> ""
    }

    private fun handleScreenChange() {
        val root = panelRoot ?: return
        val newScreen = screenSize()
        val old = lastScreen
        if (old.x > 0 && old.y > 0 && (old.x != newScreen.x || old.y != newScreen.y)) {
            val w = root.width
            val h = root.height
            val cx = (panelParams.x + w / 2f) / old.x
            val cy = (panelParams.y + h / 2f) / old.y
            panelParams.x = (cx * newScreen.x - w / 2f).roundToInt()
            panelParams.y = (cy * newScreen.y - h / 2f).roundToInt()
        }
        lastScreen = newScreen
        applyOrientationLayout()
        clampAndUpdate()
        root.post { clampAndUpdate() }
        arrowView?.invalidate()
    }

    private fun clampAndUpdate() {
        val root = panelRoot ?: return
        val screen = screenSize()
        val maxX = max(0, screen.x - root.width)
        val maxY = max(0, screen.y - root.height)
        panelParams.x = panelParams.x.coerceIn(0, maxX)
        panelParams.y = panelParams.y.coerceIn(0, maxY)
        updatePanelWindow()
    }

    private fun updatePanelWindow() {
        val root = panelRoot ?: return
        if (root.isAttachedToWindow) {
            windowManager.updateViewLayout(root, panelParams)
        }
    }

    private fun isLandscape(): Boolean {
        val size = screenSize()
        return size.x > size.y
    }

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Point(metrics.widthPixels, metrics.heightPixels)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Chess Overlay",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the chess coach overlay running"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPending = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                1,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Chess coach active")
            .setContentText("Showing move suggestions over the board")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    // ---------------------------------------------------------------------------------------
    // Dragging
    // ---------------------------------------------------------------------------------------

    /** Moves the overlay window while dragging; forwards short taps as clicks. */
    private inner class DragTouchListener : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(uiContext).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = panelParams.x
                    startY = panelParams.y
                    dragging = false
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) {
                        dragging = true
                        v.isPressed = false
                    }
                    if (dragging) {
                        panelParams.x = startX + dx.roundToInt()
                        panelParams.y = startY + dy.roundToInt()
                        clampAndUpdate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (!dragging) v.performClick()
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    dragging = false
                }
            }
            return true
        }
    }
}

/**
 * Evaluation bar showing white's share of a win-probability curve.
 * Horizontal: white fills from the left (right when flipped).
 * Vertical: white fills from the bottom (top when flipped).
 */
class EvalBarView(context: Context) : View(context) {

    var vertical: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var whiteFraction = 0.5f
    private var flipped = false
    private var animator: ValueAnimator? = null

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F0F0F0") }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#403D39") }
    private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80808080")
        strokeWidth = context.resources.displayMetrics.density
    }
    private val clipPath = Path()
    private val rect = RectF()

    /** @param evalCp centipawns from white's perspective; @param mateIn positive = white mates. */
    fun setEvaluation(evalCp: Int, mateIn: Int?, isFlipped: Boolean) {
        flipped = isFlipped
        val target = when {
            mateIn != null && mateIn > 0 -> 1f
            mateIn != null && mateIn < 0 -> 0f
            else -> winProbability(evalCp)
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(whiteFraction, target).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                whiteFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun winProbability(cp: Int): Float {
        val clamped = cp.coerceIn(-2000, 2000)
        return (1.0 / (1.0 + exp(-0.00368208 * clamped))).toFloat()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = min(w, h) / 2f
        rect.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(rect, blackPaint)
        val f = whiteFraction.coerceIn(0f, 1f)
        if (vertical) {
            val whiteH = h * f
            if (flipped) canvas.drawRect(0f, 0f, w, whiteH, whitePaint)
            else canvas.drawRect(0f, h - whiteH, w, h, whitePaint)
            canvas.drawLine(0f, h / 2f, w, h / 2f, midPaint)
        } else {
            val whiteW = w * f
            if (flipped) canvas.drawRect(w - whiteW, 0f, w, h, whitePaint)
            else canvas.drawRect(0f, 0f, whiteW, h, whitePaint)
            canvas.drawLine(w / 2f, 0f, w / 2f, h, midPaint)
        }
        canvas.restore()
    }
}

/**
 * Full-screen, non-interactive layer that draws a move arrow on top of the detected board.
 * Board bounds are expressed as ratios of the full (real) screen size.
 */
class ArrowView(
    context: Context,
    private val screenSizeProvider: () -> Point,
) : View(context) {

    private var portraitBounds: RectF? = null
    private var landscapeBounds: RectF? = null

    private var from: String? = null
    private var to: String? = null
    private var flipped = false

    private val shaftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val headPath = Path()
    private val location = IntArray(2)

    init {
        setColor(MoveColors.BEST)
    }

    fun setBoardBounds(portrait: RectF, landscape: RectF) {
        portraitBounds = RectF(portrait)
        landscapeBounds = RectF(landscape)
        invalidate()
    }

    fun setArrow(from: String, to: String, color: Int, isFlipped: Boolean) {
        this.from = from.lowercase(Locale.ROOT)
        this.to = to.lowercase(Locale.ROOT)
        flipped = isFlipped
        setColor(color)
        invalidate()
    }

    fun clearArrow() {
        from = null
        to = null
        invalidate()
    }

    private fun setColor(color: Int) {
        val withAlpha = Color.argb(220, Color.red(color), Color.green(color), Color.blue(color))
        shaftPaint.color = withAlpha
        headPaint.color = withAlpha
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val src = from ?: return
        val dst = to ?: return
        val board = boardRectInView() ?: return
        val start = squareCenter(src, board) ?: return
        val end = squareCenter(dst, board) ?: return

        val square = board.width() / 8f
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = hypot(dx, dy)
        if (length < 1f) return

        val angle = atan2(dy, dx)
        val headLength = min(square * 0.5f, length * 0.6f)
        val headHalfWidth = square * 0.32f
        val shaftWidth = square * 0.2f

        val baseX = end.x - headLength * cos(angle)
        val baseY = end.y - headLength * sin(angle)

        shaftPaint.strokeWidth = shaftWidth
        canvas.drawLine(start.x, start.y, baseX, baseY, shaftPaint)

        val perpX = -sin(angle) * headHalfWidth
        val perpY = cos(angle) * headHalfWidth
        headPath.reset()
        headPath.moveTo(end.x, end.y)
        headPath.lineTo(baseX + perpX, baseY + perpY)
        headPath.lineTo(baseX - perpX, baseY - perpY)
        headPath.close()
        canvas.drawPath(headPath, headPaint)
    }

    /** Converts the screen-ratio board bounds for the current orientation into view pixels. */
    private fun boardRectInView(): RectF? {
        val screen = screenSizeProvider()
        if (screen.x <= 0 || screen.y <= 0) return null
        val ratios = (if (screen.x > screen.y) landscapeBounds else portraitBounds) ?: return null
        if (ratios.width() <= 0f || ratios.height() <= 0f) return null
        getLocationOnScreen(location)
        return RectF(
            ratios.left * screen.x - location[0],
            ratios.top * screen.y - location[1],
            ratios.right * screen.x - location[0],
            ratios.bottom * screen.y - location[1],
        )
    }

    /** Returns the pixel center of an algebraic square (e.g. "e4"), honoring board flip. */
    private fun squareCenter(square: String, board: RectF): PointF? {
        if (square.length < 2) return null
        val file = square[0] - 'a'
        val rank = square[1] - '1'
        if (file !in 0..7 || rank !in 0..7) return null
        val col = if (flipped) 7 - file else file
        val row = if (flipped) rank else 7 - rank
        val sw = board.width() / 8f
        val sh = board.height() / 8f
        return PointF(board.left + (col + 0.5f) * sw, board.top + (row + 0.5f) * sh)
    }
}
