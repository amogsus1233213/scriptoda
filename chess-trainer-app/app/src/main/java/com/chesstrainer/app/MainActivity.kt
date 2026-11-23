package com.chesstrainer.app

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chesstrainer.app.service.ScreenCaptureService
import com.chesstrainer.app.ui.OverlayService
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        /** Intent extras / actions understood by [ScreenCaptureService]. */
        const val EXTRA_RESULT_CODE = "com.chesstrainer.app.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.chesstrainer.app.extra.DATA"
        const val ACTION_CALIBRATE = "com.chesstrainer.app.action.CALIBRATE"

        private const val CALIBRATE_DELAY_MS = 5_000L
        private const val KEY_RUNNING = "running"
    }

    private lateinit var statusText: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var calibrateButton: MaterialButton

    private var running = false
    private var calibrationTimer: CountDownTimer? = null

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Notifications disabled; service status will be hidden", Toast.LENGTH_SHORT).show()
            }
            ensureOverlayPermission()
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (OverlayService.canDrawOverlays(this)) {
                requestScreenCapture()
            } else {
                setStatus("Overlay permission denied. Enable \"Display over other apps\" to continue.")
            }
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startServices(result.resultCode, data)
            } else {
                setStatus("Screen capture permission denied.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        running = savedInstanceState?.getBoolean(KEY_RUNNING) ?: false
        setContentView(buildContent())
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        // The overlay may have been stopped from its notification while we were away.
        running = isServiceRunning(OverlayService::class.java)
        refreshUi()
    }

    /** getRunningServices is deprecated but still reports this app's own services. */
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == serviceClass.name }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_RUNNING, running)
    }

    override fun onDestroy() {
        calibrationTimer?.cancel()
        calibrationTimer = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------------------------

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).roundToInt()

    private fun buildContent(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = dp(24f)
            setPadding(p, dp(48f), p, p)
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = "Live move suggestions over your chess app"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        root.addView(subtitle, matchWrap().apply { topMargin = dp(4f) })

        statusText = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            val p = dp(16f)
            setPadding(p, p, p, p)
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.surface))
        }
        root.addView(statusText, matchWrap().apply { topMargin = dp(32f) })

        startButton = button(getString(R.string.start), R.color.accent_green) { onStartClicked() }
        root.addView(startButton, matchWrap().apply { topMargin = dp(32f) })

        stopButton = button(getString(R.string.stop), R.color.accent_red) { onStopClicked() }
        root.addView(stopButton, matchWrap().apply { topMargin = dp(12f) })

        // Secondary fallback: calibration normally happens automatically.
        calibrateButton = button("Re-calibrate", R.color.accent_blue) { onCalibrateClicked() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minHeight = dp(40f)
            minimumHeight = dp(40f)
        }
        root.addView(
            calibrateButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(24f)
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )

        val hint = TextView(this).apply {
            text = "Start the service, then open your chess app. The board is detected " +
                "automatically when you show the starting position. " +
                "Use Re-calibrate if detection isn't working."
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        root.addView(hint, matchWrap().apply { topMargin = dp(16f) })

        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.background))
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun button(label: String, colorRes: Int, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Color.WHITE)
        backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, colorRes)
        cornerRadius = dp(10f)
        minHeight = dp(56f)
        setOnClickListener { onClick() }
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    private fun refreshUi() {
        if (calibrationTimer == null) {
            setStatus(
                if (running) "Running — open your chess app"
                else "Press Start, then open your chess app"
            )
        }
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        calibrateButton.isEnabled = running && calibrationTimer == null
    }

    // ---------------------------------------------------------------------------------------
    // Start flow: notifications -> overlay -> screen capture -> services
    // ---------------------------------------------------------------------------------------

    private fun onStartClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ensureOverlayPermission()
        }
    }

    private fun ensureOverlayPermission() {
        if (OverlayService.canDrawOverlays(this)) {
            requestScreenCapture()
            return
        }
        setStatus("Grant \"Display over other apps\" permission")
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            overlayPermissionLauncher.launch(intent)
        } catch (_: Exception) {
            overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun requestScreenCapture() {
        setStatus("Requesting screen capture permission…")
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startServices(resultCode: Int, data: Intent) {
        val captureIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(this, captureIntent)
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))

        val app = ChessTrainerApp.get(this)
        app.coordinator.statusListener = { msg -> runOnUiThread { setStatus(msg) } }
        app.coordinator.calibrationListener = { ok ->
            runOnUiThread {
                if (ok) setStatus("Calibration saved")
                else setStatus("Calibration failed — try again with the starting position")
            }
        }
        app.coordinator.start()

        running = true
        refreshUi()
        // Set after refreshUi() so the generic "Running" text doesn't overwrite it;
        // the coordinator replaces this via statusListener once it finds the board.
        setStatus("Looking for chess board...")

        // Auto-open chess.com app
        try {
            val chessIntent = packageManager.getLaunchIntentForPackage("com.chess")
            if (chessIntent != null) {
                chessIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(chessIntent)
            }
        } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------------------------------
    // Stop / calibrate
    // ---------------------------------------------------------------------------------------

    private fun onStopClicked() {
        calibrationTimer?.cancel()
        calibrationTimer = null
        ChessTrainerApp.get(this).coordinator.stop()
        stopService(Intent(this, OverlayService::class.java))
        stopService(Intent(this, ScreenCaptureService::class.java))
        running = false
        refreshUi()
    }

    private fun onCalibrateClicked() {
        if (!running) {
            Toast.makeText(this, "Press Start first", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            this,
            "Open your chess app at the starting position. Capturing in ${CALIBRATE_DELAY_MS / 1000}s…",
            Toast.LENGTH_LONG,
        ).show()

        calibrationTimer = object : CountDownTimer(CALIBRATE_DELAY_MS, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished + 999) / 1000
                setStatus("Calibrating in ${seconds}s — show the starting position")
            }

            override fun onFinish() {
                calibrationTimer = null
                val intent = Intent(this@MainActivity, ScreenCaptureService::class.java)
                    .setAction(ACTION_CALIBRATE)
                try {
                    startService(intent)
                    setStatus("Calibration screenshot captured")
                } catch (e: IllegalStateException) {
                    setStatus("Calibration failed: ${e.message}")
                }
                startButton.isEnabled = !running
                stopButton.isEnabled = running
                calibrateButton.isEnabled = running
            }
        }.start()
        refreshUi()
        moveTaskToBack(true)
    }
}
