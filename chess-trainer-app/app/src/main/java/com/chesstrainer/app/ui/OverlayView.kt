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
import android.widget.ScrollView
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

    /** Maps a classification label (case-insensitive) to its short symbol (matches MoveClass). */
    fun symbolForLabel(label: String): String = when (label.trim().lowercase(Locale.ROOT)) {
        "brilliant" -> "!!"
        "great" -> "!"
        "best" -> "★"
        "excellent" -> "●"
        "good" -> "○"
        "book" -> "📖"
        "inaccuracy" -> "?!"
        "mistake" -> "?"
        "miss" -> "△"
        "blunder" -> "??"
        else -> "·"
    }
}

/** A single engine-suggested line shown on the overlay. */
data class SuggestedLine(
    val from: String,
    val to: String,
    val evalCp: Int?,
    val mateIn: Int?,
    val label: String,       // "Best", "Good", "Inaccuracy", etc.
    val labelColor: Int,
    val rank: Int,           // 1 = best move, 2 = second best, 3 = third best
)

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

    private val maxSuggestedLines = 3

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var uiContext: Context
    private lateinit var windowManager: WindowManager

    private var arrowView: ArrowView? = null
    private var panelRoot: FrameLayout? = null
    private lateinit var panelParams: WindowManager.LayoutParams

    private lateinit var pillView: TextView
    private lateinit var expandedView: LinearLayout
    private lateinit var contentLayout: LinearLayout
    private lateinit var linesContainer: LinearLayout
    private lateinit var evalBar: EvalBarView

    private var expanded = false
    private var lastScreen = Point()
    private var currentLines: List<SuggestedLine> = emptyList()
    private var currentFlipped = false
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

    /** Single-move API kept for backwards compatibility; wraps into a one-line multi suggestion. */
    fun showSuggestion(
        from: String,
        to: String,
        evalCp: Int?,
        mateIn: Int?,
        label: String,
        labelColor: Int,
        isFlipped: Boolean,
    ) {
        showMultiSuggestion(
            listOf(SuggestedLine(from, to, evalCp, mateIn, label, labelColor, rank = 1)),
            isFlipped,
        )
    }

    /** Shows up to three suggested lines (ordered by [SuggestedLine.rank]) as arrows + panel rows. */
    fun showMultiSuggestion(lines: List<SuggestedLine>, isFlipped: Boolean) {
        val snapshot = lines.sortedBy { it.rank }.take(maxSuggestedLines)
        mainHandler.post {
            val wasEmpty = currentLines.isEmpty()
            currentLines = snapshot
            currentFlipped = isFlipped
            renderMultiSuggestion(autoExpand = wasEmpty)
        }
    }

    /** Clears all arrows and resets the panel to its waiting state. */
    fun clearSuggestion() {
        mainHandler.post {
            currentLines = emptyList()
            renderMultiSuggestion(autoExpand = false)
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
        renderMultiSuggestion(autoExpand = false)
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

        linesContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = ctx.dpInt(170f)
        }
        val info = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(linesContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

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

    /**
     * Renders [currentLines] into the panel list, eval bar and arrow layer. Main thread only.
     * @param autoExpand expand the panel if collapsed (used when the first suggestion arrives).
     */
    private fun renderMultiSuggestion(autoExpand: Boolean) {
        val lines = currentLines
        linesContainer.removeAllViews()
        if (lines.isEmpty()) {
            linesContainer.addView(buildLineRow(MoveColors.NEUTRAL, "·", "Waiting", "—", isBest = true), rowParams())
            evalBar.setEvaluation(0, null, false)
            arrowView?.clearArrows()
            panelRoot?.post { clampAndUpdate() }
            return
        }
        if (autoExpand && !expanded) setExpanded(true)

        lines.forEachIndexed { index, line ->
            linesContainer.addView(
                buildLineRow(
                    color = line.labelColor,
                    symbol = MoveColors.symbolForLabel(line.label),
                    move = "${line.from} → ${line.to}",
                    eval = formatEval(line.evalCp, line.mateIn),
                    isBest = index == 0,
                ),
                rowParams(),
            )
        }

        val best = lines.first()
        evalBar.setEvaluation(best.evalCp ?: 0, best.mateIn, currentFlipped)
        arrowView?.setArrows(
            lines.map {
                ArrowData(it.from, it.to, it.labelColor, it.rank, it.label, currentFlipped)
            },
        )
        // Row count may have changed the panel size; keep it on screen.
        panelRoot?.post { clampAndUpdate() }
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    /** One panel row: colored dot, classification symbol, move text, eval. */
    private fun buildLineRow(
        color: Int,
        symbol: String,
        move: String,
        eval: String,
        isBest: Boolean,
    ): LinearLayout {
        val ctx = uiContext
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val v = ctx.dpInt(if (isBest) 4f else 3f)
            setPadding(0, v, 0, v)
        }

        val dotSize = ctx.dpInt(10f)
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
        row.addView(dot, LinearLayout.LayoutParams(dotSize, dotSize).apply {
            marginEnd = ctx.dpInt(6f)
        })

        val symbolView = TextView(ctx).apply {
            text = symbol
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isBest) 15f else 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = ctx.dpInt(20f)
        }
        row.addView(symbolView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = ctx.dpInt(6f)
        })

        val moveView = TextView(ctx).apply {
            text = move
            setTextColor(if (isBest) Color.WHITE else Color.parseColor("#D8D8D8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isBest) 17f else 14f)
            typeface = if (isBest) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            maxLines = 1
        }
        row.addView(moveView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val evalView = TextView(ctx).apply {
            text = eval
            setTextColor(Color.parseColor(if (isBest) "#E8E8E8" else "#B0B0B0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isBest) 14f else 12f)
            typeface = if (isBest) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
            gravity = Gravity.END
            maxLines = 1
        }
        row.addView(evalView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = ctx.dpInt(10f)
        })
        return row
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

/** One arrow to draw on the board. [rank] 1 = best move (drawn most prominently). */
data class ArrowData(
    val from: String,
    val to: String,
    val color: Int,
    val rank: Int,
    val label: String,
    val isFlipped: Boolean,
)

/**
 * Full-screen, non-interactive layer that draws up to three move arrows on top of the detected
 * board, each with a classification badge near its tip. Board bounds are expressed as ratios of
 * the full (real) screen size.
 */
class ArrowView(
    context: Context,
    private val screenSizeProvider: () -> Point,
) : View(context) {

    /**
     * Visual parameters per rank; sizes are fractions of the square size, badge in dp.
     * All ranks are fully opaque: hierarchy comes from thickness and color, never transparency.
     */
    private data class ArrowStyle(
        val alpha: Int,
        val shaftFactor: Float,
        val headLengthFactor: Float,
        val headHalfWidthFactor: Float,
        val badgeDp: Float,
    )

    private companion object {
        const val MAX_ARROWS = 3
        val STYLE_RANK_1 = ArrowStyle(255, 0.24f, 0.55f, 0.36f, 24f)  // Best: opaque, thick
        val STYLE_RANK_2 = ArrowStyle(255, 0.12f, 0.40f, 0.22f, 16f)  // 2nd: opaque, thin
        val STYLE_RANK_3 = ArrowStyle(255, 0.08f, 0.32f, 0.16f, 14f)  // 3rd: opaque, very thin

        /** Muted steel blue for the second-best arrow. */
        val COLOR_RANK_2 = Color.parseColor("#6B8EB5")
        /** Muted warm gray for the third-best arrow. */
        val COLOR_RANK_3 = Color.parseColor("#8B8680")

        /** Alpha of the dark outline drawn behind every arrow. */
        const val BORDER_ALPHA = 100
        /** Total extra width of the outline (split evenly on both sides of the arrow), in dp. */
        const val BORDER_EXTRA_DP = 3f

        fun styleFor(rank: Int): ArrowStyle = when {
            rank <= 1 -> STYLE_RANK_1
            rank == 2 -> STYLE_RANK_2
            else -> STYLE_RANK_3
        }

        /** Best move keeps its classification color; alternatives use fixed muted colors. */
        fun arrowColorForRank(rank: Int, classColor: Int): Int = when {
            rank <= 1 -> classColor
            rank == 2 -> COLOR_RANK_2
            else -> COLOR_RANK_3
        }
    }

    private var portraitBounds: RectF? = null
    private var landscapeBounds: RectF? = null

    private var arrows: List<ArrowData> = emptyList()

    private val shaftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Outline paints are opaque black; the translucency (BORDER_ALPHA) is applied once via a
    // layer so overlapping shaft/head outlines don't darken where they meet.
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
    }
    private val borderHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Fill + stroke grows the triangle outward by half the stroke width on every edge.
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    private val layerBounds = RectF()

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badgeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 255, 255, 255)
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val headPath = Path()
    private val location = IntArray(2)
    private val density = context.resources.displayMetrics.density

    fun setBoardBounds(portrait: RectF, landscape: RectF) {
        portraitBounds = RectF(portrait)
        landscapeBounds = RectF(landscape)
        invalidate()
    }

    /** Replaces all arrows. Only the three best-ranked arrows are drawn. */
    fun setArrows(arrows: List<ArrowData>) {
        this.arrows = arrows
            .map { it.copy(from = it.from.lowercase(Locale.ROOT), to = it.to.lowercase(Locale.ROOT)) }
            .sortedBy { it.rank }
            .take(MAX_ARROWS)
        invalidate()
    }

    fun clearArrows() {
        arrows = emptyList()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (arrows.isEmpty()) return
        val board = boardRectInView() ?: return
        val square = board.width() / 8f
        // Worst rank first so the best arrow (and its badge) ends up on top.
        val drawOrder = arrows.sortedByDescending { it.rank }
        for (arrow in drawOrder) drawArrow(canvas, arrow, board, square)
        for (arrow in drawOrder) drawBadge(canvas, arrow, board, square)
    }

    private fun drawArrow(canvas: Canvas, arrow: ArrowData, board: RectF, square: Float) {
        val start = squareCenter(arrow.from, board, arrow.isFlipped) ?: return
        val end = squareCenter(arrow.to, board, arrow.isFlipped) ?: return
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = hypot(dx, dy)
        if (length < 1f) return

        val style = styleFor(arrow.rank)
        val color = withAlpha(arrowColorForRank(arrow.rank, arrow.color), style.alpha)
        shaftPaint.color = color
        headPaint.color = color

        val angle = atan2(dy, dx)
        val headLength = min(square * style.headLengthFactor, length * 0.6f)
        val headHalfWidth = square * style.headHalfWidthFactor

        val baseX = end.x - headLength * cos(angle)
        val baseY = end.y - headLength * sin(angle)

        val shaftWidth = square * style.shaftFactor
        val borderExtra = BORDER_EXTRA_DP * density
        shaftPaint.strokeWidth = shaftWidth
        borderPaint.strokeWidth = shaftWidth + borderExtra
        borderHeadPaint.strokeWidth = borderExtra

        val perpX = -sin(angle) * headHalfWidth
        val perpY = cos(angle) * headHalfWidth
        headPath.reset()
        headPath.moveTo(end.x, end.y)
        headPath.lineTo(baseX + perpX, baseY + perpY)
        headPath.lineTo(baseX - perpX, baseY - perpY)
        headPath.close()

        // 1-2. Dark outline (shaft + head) in a single translucent layer, bounded to this arrow.
        val pad = max(headHalfWidth, shaftWidth) + borderExtra * 2f
        layerBounds.set(
            min(start.x, end.x) - pad,
            min(start.y, end.y) - pad,
            max(start.x, end.x) + pad,
            max(start.y, end.y) + pad,
        )
        val saveCount = canvas.saveLayerAlpha(layerBounds, BORDER_ALPHA)
        canvas.drawLine(start.x, start.y, baseX, baseY, borderPaint)
        canvas.drawPath(headPath, borderHeadPaint)
        canvas.restoreToCount(saveCount)

        // 3-4. Solid colored shaft and arrowhead on top.
        canvas.drawLine(start.x, start.y, baseX, baseY, shaftPaint)
        canvas.drawPath(headPath, headPaint)
    }

    /**
     * Draws the classification badge at the top-right corner of the destination square, clear of
     * the arrowhead. The badge keeps the classification color so the label stays meaningful.
     */
    private fun drawBadge(canvas: Canvas, arrow: ArrowData, board: RectF, square: Float) {
        val end = squareCenter(arrow.to, board, arrow.isFlipped) ?: return
        val style = styleFor(arrow.rank)
        val radius = style.badgeDp * density / 2f
        val offset = square * 0.45f
        val cx = (end.x + offset).coerceIn(radius, max(radius, width - radius))
        val cy = (end.y - offset).coerceIn(radius, max(radius, height - radius))

        badgePaint.color = withAlpha(arrow.color, 255)
        canvas.drawCircle(cx, cy, radius, badgePaint)
        badgeRingPaint.strokeWidth = max(1f, density * if (arrow.rank <= 1) 1.5f else 1f)
        canvas.drawCircle(cx, cy, radius, badgeRingPaint)

        val symbol = MoveColors.symbolForLabel(arrow.label)
        badgeTextPaint.textSize = radius * 2f * if (symbol.length >= 2) 0.52f else 0.66f
        val fm = badgeTextPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(symbol, cx, baseline, badgeTextPaint)
    }

    /** Applies [alpha] (255 for all arrow styles, i.e. fully opaque) to [color]'s RGB. */
    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

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
    private fun squareCenter(square: String, board: RectF, flipped: Boolean): PointF? {
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
