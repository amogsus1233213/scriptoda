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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.chesstrainer.app.R
import java.util.Locale
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
    val BRILLIANT = Color.parseColor("#1BACA6")  // cyan/teal for brilliant moves
    val BEST = Color.parseColor("#96BC4B")
    val GOOD = Color.parseColor("#7FA650")
    val GREAT = Color.parseColor("#5BAAEF")
    val INACCURACY = Color.parseColor("#F7C631")
    val MISTAKE = Color.parseColor("#E58F2A")
    val BLUNDER = Color.parseColor("#CA3431")
    val NEUTRAL = Color.parseColor("#5D5A55")
    /** Gold, reserved for a mate in one so it cannot be mistaken for an ordinary best move. */
    val MATE = Color.parseColor("#FFD700")

    /** Maps a classification label (case-insensitive) to its color. */
    fun forLabel(label: String): Int = when (val l = label.trim().lowercase(Locale.ROOT)) {
        "mate in 1", "mate in 1!" -> MATE
        else -> if (l.startsWith("mate in ")) BEST else forClassLabel(l)
    }

    private fun forClassLabel(label: String): Int = when (label) {
        "brilliant" -> BRILLIANT
        "best", "best move", "best reply" -> BEST
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

    /** Maps a classification label (case-insensitive) to its chess.com-style icon drawable. */
    fun drawableResForLabel(label: String): Int = when (label.trim().lowercase(Locale.ROOT)) {
        "brilliant" -> R.drawable.ic_move_brilliant
        "great" -> R.drawable.ic_move_great
        "best", "best move", "best reply" -> R.drawable.ic_move_best
        "excellent" -> R.drawable.ic_move_excellent
        "good" -> R.drawable.ic_move_good
        "book" -> R.drawable.ic_move_book
        "inaccuracy" -> R.drawable.ic_move_inaccuracy
        "mistake" -> R.drawable.ic_move_mistake
        "miss" -> R.drawable.ic_move_miss
        "blunder" -> R.drawable.ic_move_blunder
        else -> R.drawable.ic_move_best
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
    val rank: Int,           // 1 = best move, 2 = second best, ... 5 = fifth best
    val commentary: String = "", // short coach comment, only shown for the best line
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
        private const val WAITING_TEXT = "Engine is waiting for a move..."
        private const val THINKING_TEXT = "Calculating (~5s)..."

        /** How long the coach's reaction to the user's own move stays in the bubble. */
        private const val COACH_MESSAGE_MS = 8_000L

        /** Currently running service instance, or null if the overlay is not shown. */
        @Volatile
        var instance: OverlayService? = null
            private set

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
    }

    private val maxSuggestedLines = 5

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var uiContext: Context
    private lateinit var windowManager: WindowManager

    private var arrowView: ArrowView? = null
    private var panelRoot: FrameLayout? = null
    private lateinit var panelParams: WindowManager.LayoutParams

    private lateinit var pillView: TextView
    private lateinit var expandedView: LinearLayout

    /** Mittens commentary bubble; the only content of the expanded panel. */
    private var commentaryView: TextView? = null

    private var expanded = false

    /** Set when the user collapsed the panel by hand; suppresses auto-expand until they reopen it. */
    private var userCollapsed = false
    private var lastScreen = Point()

    /**
     * Top-left position the user chose for the panel (initial spot, end of the last drag, or that
     * spot re-mapped after a rotation). The window sits at the nearest fully visible position, so
     * a temporarily larger panel (expanded, longer text) is pushed in from a screen edge without
     * losing the chosen spot.
     */
    private var anchorX = 0
    private var anchorY = 0

    /** Size of the area the window manager lets the panel occupy; see [refreshUsableArea]. */
    private val usableArea = Point()
    private val displayFrame = Rect()
    private var currentLines: List<SuggestedLine> = emptyList()
    private var currentFlipped = false

    /** Coach reaction to the user's own move, shown while there are no suggestions. Main thread only. */
    private var coachMessage: String? = null
    /** True between [showThinking] and the next suggestion or clearing. Main thread only. */
    private var thinking = false
    private val expireCoachMessage = Runnable { dropCoachMessage(restoreWaiting = true) }
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
        // Callers may still hold this instance for a moment; make late API calls no-ops.
        commentaryView = null
        coachMessage = null
        currentLines = emptyList()
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

    /** Shows up to five suggested lines (ordered by [SuggestedLine.rank]) as arrows + commentary. */
    fun showMultiSuggestion(lines: List<SuggestedLine>, isFlipped: Boolean) {
        val snapshot = lines.sortedBy { it.rank }.take(maxSuggestedLines)
        mainHandler.post {
            val wasEmpty = currentLines.isEmpty()
            currentLines = snapshot
            currentFlipped = isFlipped
            renderMultiSuggestion(autoExpand = wasEmpty)
        }
    }

    /**
     * Shows "Engine is calculating..." in the panel and clears stale arrows. A reaction to the
     * user's own move that is still on screen stays there while the engine thinks: the opponent
     * often replies at once, and the reaction must not vanish before it could be read. The
     * next suggestion (or the reaction's own timeout) replaces it.
     */
    fun showThinking() {
        mainHandler.post {
            currentLines = emptyList()
            thinking = true
            arrowView?.setArrows(emptyList())
            val view = commentaryView ?: return@post
            view.text = coachMessage ?: THINKING_TEXT
            panelRoot?.post { clampAndUpdate() }
        }
    }

    /**
     * Shows the coach's reaction to the move the user just played. It stays in the bubble for
     * [durationMs], or until the next suggestion / "thinking" state replaces it. Ignored while
     * suggestions are on screen, since those carry their own commentary.
     */
    fun showCoachMessage(text: String, durationMs: Long = COACH_MESSAGE_MS) {
        if (text.isEmpty()) return
        mainHandler.post {
            val view = commentaryView ?: return@post
            if (currentLines.isNotEmpty()) return@post
            mainHandler.removeCallbacks(expireCoachMessage)
            coachMessage = text
            view.text = text
            if (!expanded && !userCollapsed) setExpanded(true)
            panelRoot?.post { clampAndUpdate() }
            mainHandler.postDelayed(expireCoachMessage, durationMs)
        }
    }

    /** Removes the coach's reaction, e.g. when the game it belonged to is gone. */
    fun clearCoachMessage() {
        mainHandler.post { dropCoachMessage(restoreWaiting = true) }
    }

    /** Shows a classification badge on the square the opponent's piece landed on. */
    fun showOpponentBadge(square: String, label: String, isFlipped: Boolean) {
        mainHandler.post {
            arrowView?.setOpponentBadge(square, label, isFlipped)
        }
    }

    fun clearOpponentBadge() {
        mainHandler.post {
            arrowView?.clearOpponentBadge()
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
        anchorX = panelParams.x
        anchorY = panelParams.y
        usableArea.set(lastScreen.x, lastScreen.y)

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

    /**
     * Compact speech bubble: a small drag handle (tap to collapse) above the Mittens commentary.
     * The whole bubble can be dragged.
     */
    private fun buildPanel(): LinearLayout {
        val ctx = uiContext
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.parseColor("#262421"), 14f)
            elevation = ctx.dp(8f)
            val h = ctx.dpInt(10f)
            setPadding(h, ctx.dpInt(4f), h, ctx.dpInt(10f))
        }

        val handle = TextView(ctx).apply {
            text = "Mittens ▾"
            setTextColor(Color.parseColor("#8A8A8A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val v = ctx.dpInt(2f)
            setPadding(0, v, 0, v + ctx.dpInt(2f))
            contentDescription = "Collapse coach"
            setOnClickListener { setExpanded(false) }
            setOnTouchListener(DragTouchListener())
        }
        panel.addView(handle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val commentary = buildCommentaryView()
        commentaryView = commentary
        panel.addView(commentary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return panel
    }

    // ---------------------------------------------------------------------------------------
    // State / rendering
    // ---------------------------------------------------------------------------------------

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        if (value) userCollapsed = false else userCollapsed = true
        pillView.visibility = if (value) View.GONE else View.VISIBLE
        expandedView.visibility = if (value) View.VISIBLE else View.GONE
        updatePanelWindow()
        // Once the new content is measured, make sure it is still fully on screen.
        panelRoot?.post { clampAndUpdate() }
    }

    /**
     * Renders [currentLines] into the commentary bubble and arrow layer. Main thread only.
     * @param autoExpand expand the panel if collapsed (used when the first suggestion arrives).
     */
    private fun renderMultiSuggestion(autoExpand: Boolean) {
        val lines = currentLines
        thinking = false
        val view = commentaryView ?: return
        if (lines.isEmpty()) {
            // A reaction to the user's own move outlives the clearing of the arrows.
            view.text = coachMessage ?: WAITING_TEXT
            arrowView?.setArrows(emptyList())
            panelRoot?.post { clampAndUpdate() }
            return
        }
        if (autoExpand && !expanded && !userCollapsed) setExpanded(true)
        // The suggestion's own commentary takes over from the reaction to the previous move.
        dropCoachMessage(restoreWaiting = true)

        val commentary = lines.first().commentary
        // Keep the previous line if this update carries no commentary (e.g. a deeper re-analysis).
        if (commentary.isNotEmpty()) {
            view.text = commentary
        } else if (view.text.isNullOrEmpty() || view.text == WAITING_TEXT) {
            view.text = THINKING_TEXT
        }

        arrowView?.setArrows(
            lines.map {
                // Suggestions are the user's own moves and the user sits at the bottom, so the
                // user is white unless the board is flipped. mateIn is from white's POV.
                val userMate = it.mateIn?.let { m -> if (currentFlipped) -m else m }?.takeIf { m -> m > 0 }
                ArrowData(it.from, it.to, it.labelColor, it.rank, it.label, currentFlipped, userMate)
            },
        )
        // Text length may have changed the panel size; keep it on screen.
        panelRoot?.post { clampAndUpdate() }
    }

    /**
     * Forgets the coach's reaction. With [restoreWaiting] the bubble goes back to the waiting
     * text if it is still showing the reaction. Main thread only.
     */
    private fun dropCoachMessage(restoreWaiting: Boolean) {
        mainHandler.removeCallbacks(expireCoachMessage)
        val message = coachMessage ?: return
        coachMessage = null
        if (!restoreWaiting) return
        val view = commentaryView ?: return
        if (view.text.toString() == message) {
            view.text = if (thinking) THINKING_TEXT else WAITING_TEXT
            panelRoot?.post { clampAndUpdate() }
        }
    }

    private fun buildCommentaryView(): TextView {
        val ctx = uiContext
        return TextView(ctx).apply {
            text = WAITING_TEXT
            setTextColor(Color.parseColor("#E8E8E8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.defaultFromStyle(Typeface.ITALIC)
            // Keep the bubble compact: wrap instead of widening it.
            maxWidth = ctx.dpInt(220f)
            // Room for a reaction to the last move plus advice for this one.
            // A post-move reaction (what was played, what was better, what it cost) needs more.
            maxLines = 6
            ellipsize = android.text.TextUtils.TruncateAt.END
            val h = ctx.dpInt(8f)
            val v = ctx.dpInt(6f)
            setPadding(h, v, h, v)
            background = roundedBackground(Color.parseColor("#33FFFFFF"), 8f)
            // Dragging the bubble itself moves the panel too; taps do nothing.
            setOnTouchListener(DragTouchListener())
        }
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

    /** "M2"-style text shown while there is a forced mate, or null. */
    private var mateText: String? = null
    private var mateForWhite = false
    private val mateTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    /** @param evalCp centipawns from white's perspective; @param mateIn positive = white mates. */
    fun setEvaluation(evalCp: Int, mateIn: Int?, isFlipped: Boolean) {
        flipped = isFlipped
        mateText = mateIn?.takeIf { it != 0 }?.let { "M${kotlin.math.abs(it)}" }
        mateForWhite = mateIn != null && mateIn > 0
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
        drawMateText(canvas, w, h)
        canvas.restore()
    }

    /**
     * Draws the mate count at the winning side's end of the bar, which is always filled with
     * that side's color (also mid-animation), in the contrasting color.
     */
    private fun drawMateText(canvas: Canvas, w: Float, h: Float) {
        val text = mateText ?: return
        val thickness = min(w, h)
        mateTextPaint.color = if (mateForWhite) blackPaint.color else whitePaint.color
        mateTextPaint.textSize = thickness * 0.7f
        // Shrink to fit across a narrow vertical bar.
        val available = (if (vertical) w else w / 2f) * 0.9f
        val measured = mateTextPaint.measureText(text)
        if (measured > available && measured > 0f) mateTextPaint.textSize *= available / measured
        val fm = mateTextPaint.fontMetrics
        val inset = max(thickness, mateTextPaint.measureText(text)) * 0.75f
        // White's end is the bottom / left, or the top / right when flipped; black's the other.
        // atStart = the winner's end is the top (vertical) or the right (horizontal).
        val atStart = mateForWhite == flipped
        val cx: Float
        val cy: Float
        if (vertical) {
            cx = w / 2f
            cy = if (atStart) inset else h - inset
        } else {
            cx = if (atStart) w - inset else inset
            cy = h / 2f
        }
        canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, mateTextPaint)
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
    /** Moves until the mover delivers mate (> 0), or null when the move is not a forced mate. */
    val mateIn: Int? = null,
)

/**
 * Full-screen, non-interactive layer that draws up to five move arrows on top of the detected
 * board, each with a classification badge near its tip. Board bounds are expressed as ratios of
 * the full (real) screen size.
 */
class ArrowView(
    context: Context,
    private val screenSizeProvider: () -> Point,
) : View(context) {

    /**
     * Visual parameters per rank; sizes are fractions of the square size, badge in dp.
     * All ranks share the same geometry; the best move stands out only by being fully opaque.
     */
    private data class ArrowStyle(
        val alpha: Int,
        val shaftFactor: Float,
        val headLengthFactor: Float,
        val headHalfWidthFactor: Float,
        val badgeDp: Float,
    )

    private companion object {
        const val MAX_ARROWS = 5
        val STYLE_RANK_1 = ArrowStyle(255, 0.19f, 0.48f, 0.28f, 22f)  // Best: fully opaque, thicker
        val STYLE_RANK_2 = ArrowStyle(200, 0.14f, 0.40f, 0.22f, 22f)
        val STYLE_RANK_3 = ArrowStyle(200, 0.14f, 0.40f, 0.22f, 22f)
        val STYLE_RANK_4 = ArrowStyle(200, 0.14f, 0.40f, 0.22f, 22f)
        val STYLE_RANK_5 = ArrowStyle(200, 0.14f, 0.40f, 0.22f, 22f)

        /** Alpha of the dark outline drawn behind every arrow. */
        const val BORDER_ALPHA = 100
        /** Total extra width of the outline (split evenly on both sides of the arrow), in dp. */
        const val BORDER_EXTRA_DP = 3f

        fun styleFor(rank: Int): ArrowStyle = when {
            rank <= 1 -> STYLE_RANK_1
            rank == 2 -> STYLE_RANK_2
            rank == 3 -> STYLE_RANK_3
            rank == 4 -> STYLE_RANK_4
            else -> STYLE_RANK_5
        }

        /** Every arrow uses its own classification color, regardless of rank. */
        @Suppress("UNUSED_PARAMETER")
        fun arrowColorForRank(rank: Int, classColor: Int): Int = classColor
    }

    private var portraitBounds: RectF? = null
    private var landscapeBounds: RectF? = null

    private var arrows: List<ArrowData> = emptyList()

    // Opponent move classification badge (shown on the square the opponent's piece landed on)
    private var opBadgeSquare: String? = null
    private var opBadgeLabel: String? = null
    private var opBadgeFlipped: Boolean = false

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

    private val matePillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matePillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B1B1B")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val matePillRect = RectF()

    /** Classification icons keyed by drawable resource id, loaded lazily on first use. */
    private val drawableCache = HashMap<Int, Drawable?>()
    private val headPath = Path()
    private val location = IntArray(2)
    private val density = context.resources.displayMetrics.density

    fun setBoardBounds(portrait: RectF, landscape: RectF) {
        portraitBounds = RectF(portrait)
        landscapeBounds = RectF(landscape)
        invalidate()
    }

    /** Replaces all arrows. Only the five best-ranked arrows are drawn. */
    fun setArrows(arrows: List<ArrowData>) {
        this.arrows = arrows
            .map { it.copy(from = it.from.lowercase(Locale.ROOT), to = it.to.lowercase(Locale.ROOT)) }
            .sortedBy { it.rank }
            .take(MAX_ARROWS)
        invalidate()
    }

    fun clearArrows() {
        arrows = emptyList()
        opBadgeSquare = null
        opBadgeLabel = null
        invalidate()
    }

    fun clearOpponentBadge() {
        opBadgeSquare = null
        opBadgeLabel = null
        invalidate()
    }

    /** Shows a small classification badge on the given square for the opponent's last move. */
    fun setOpponentBadge(square: String, label: String, flipped: Boolean) {
        opBadgeSquare = square.lowercase(Locale.ROOT)
        opBadgeLabel = label
        opBadgeFlipped = flipped
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val hasArrows = arrows.isNotEmpty()
        val hasBadge = opBadgeSquare != null
        if (!hasArrows && !hasBadge) return
        val board = boardRectInView() ?: return
        val square = board.width() / 8f
        if (hasArrows) {
            val drawOrder = arrows.sortedByDescending { it.rank }
            for (arrow in drawOrder) drawArrow(canvas, arrow, board, square)
            for (arrow in drawOrder) drawBadge(canvas, arrow, board, square)
        }
        drawOpponentBadge(canvas, board, square)
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

    private fun getCachedDrawable(resId: Int): Drawable? =
        drawableCache.getOrPut(resId) { AppCompatResources.getDrawable(context, resId)?.mutate() }

    /**
     * Draws the classification icon at the top-right corner of the destination square, clear of
     * the arrowhead. The icon keeps the classification look so the label stays meaningful.
     */
    private fun drawBadge(canvas: Canvas, arrow: ArrowData, board: RectF, square: Float) {
        val end = squareCenter(arrow.to, board, arrow.isFlipped) ?: return
        val style = styleFor(arrow.rank)
        val sizePx = (style.badgeDp * density).roundToInt()
        if (sizePx <= 0) return
        val half = sizePx / 2f
        val offset = square * 0.45f
        val cx = (end.x + offset).coerceIn(half, max(half, width - half))
        val cy = (end.y - offset).coerceIn(half, max(half, height - half))

        val mate = arrow.mateIn
        if (mate != null && mate > 0) {
            drawMatePill(canvas, "M$mate", arrow.color, cx, cy, sizePx.toFloat())
            return
        }

        val drawable = getCachedDrawable(MoveColors.drawableResForLabel(arrow.label)) ?: return
        val left = (cx - half).roundToInt()
        val top = (cy - half).roundToInt()
        drawable.setBounds(left, top, left + sizePx, top + sizePx)
        drawable.draw(canvas)
    }

    /**
     * Draws a "M2"-style pill in place of the classification icon, so a forced mate is readable
     * on the board itself. Filled with the arrow's own color (gold for a mate in one).
     */
    private fun drawMatePill(canvas: Canvas, text: String, color: Int, cx: Float, cy: Float, height: Float) {
        matePillTextPaint.textSize = height * 0.68f
        val pillWidth = max(height, matePillTextPaint.measureText(text) + height * 0.6f)
        val halfW = pillWidth / 2f
        val halfH = height / 2f
        // Keep the (wider than the icon) pill inside the view.
        val x = cx.coerceIn(halfW, max(halfW, width - halfW))
        matePillRect.set(x - halfW, cy - halfH, x + halfW, cy + halfH)

        matePillPaint.style = Paint.Style.FILL
        matePillPaint.color = color
        canvas.drawRoundRect(matePillRect, halfH, halfH, matePillPaint)
        matePillPaint.style = Paint.Style.STROKE
        matePillPaint.strokeWidth = 1.5f * density
        matePillPaint.color = Color.parseColor("#1B1B1B")
        canvas.drawRoundRect(matePillRect, halfH, halfH, matePillPaint)

        val fm = matePillTextPaint.fontMetrics
        canvas.drawText(text, x, cy - (fm.ascent + fm.descent) / 2f, matePillTextPaint)
    }

    /**
     * Draws a small classification icon at the top-right corner of the opponent's destination
     * square, so the user can see how good or bad the opponent's last move was.
     */
    private fun drawOpponentBadge(canvas: Canvas, board: RectF, square: Float) {
        val sq = opBadgeSquare ?: return
        val label = opBadgeLabel ?: return
        val center = squareCenter(sq, board, opBadgeFlipped) ?: return
        val sizePx = (18f * density).roundToInt()
        if (sizePx <= 0) return
        val half = sizePx / 2f
        val offset = square * 0.45f
        val cx = (center.x + offset).coerceIn(half, max(half, width - half))
        val cy = (center.y - offset).coerceIn(half, max(half, height - half))

        val drawable = getCachedDrawable(MoveColors.drawableResForLabel(label)) ?: return
        val left = (cx - half).roundToInt()
        val top = (cy - half).roundToInt()
        drawable.setBounds(left, top, left + sizePx, top + sizePx)
        drawable.draw(canvas)
    }

    /** Applies [alpha] (255 for the best move, 200 for alternatives) to [color]'s RGB. */
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
