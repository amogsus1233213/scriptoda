package com.chesstrainer.app.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Installs the Stockfish executable shipped in the APK assets into the app's files directory.
 *
 * Stockfish is a standalone (statically linked) executable rather than a shared library, so it is
 * packaged as an asset (`assets/stockfish_arm64`, `assets/stockfish_arm32`) instead of as
 * `jniLibs/<abi>/libstockfish.so`.
 */
object StockfishBinaryExtractor {

    private const val TAG = "StockfishExtractor"

    private const val ARM64_ASSET = "stockfish_arm64"
    private const val ARM32_ASSET = "stockfish_arm32"
    private const val BINARY_NAME = "stockfish"

    private const val PREFS_NAME = "stockfish_extractor"

    /** Asset name + install time of the APK the current copy was extracted from. */
    private const val KEY_EXTRACTED_FROM = "extracted_from"

    private val lock = Any()

    /**
     * Returns an executable Stockfish binary for this device's ABI, extracting it from the assets
     * on first run and again after every app update (the APK may carry a new binary).
     *
     * @return the binary, or null if it could not be extracted or made executable.
     */
    fun extractBinary(context: Context): File? = synchronized(lock) {
        val appContext = context.applicationContext
        val assetName = if (Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) ARM64_ASSET else ARM32_ASSET
        val outFile = File(appContext.filesDir, BINARY_NAME)
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val source = "$assetName@${installStamp(appContext)}"

        // Skip extraction if the copy from this exact APK is already in place and executable.
        if (outFile.isFile && outFile.length() > 0 && prefs.getString(KEY_EXTRACTED_FROM, null) == source) {
            if (!outFile.canExecute()) outFile.setExecutable(true, true)
            if (outFile.canExecute()) return outFile
        }

        // Write to a temporary file first so an interrupted copy never leaves a truncated binary.
        val tmp = File(appContext.filesDir, "$BINARY_NAME.tmp")
        return try {
            appContext.assets.open(assetName).use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (outFile.exists() && !outFile.delete()) {
                Log.w(TAG, "Could not delete stale engine binary")
            }
            if (!tmp.renameTo(outFile)) throw IOException("Could not move $tmp to $outFile")

            outFile.setExecutable(true, true)
            if (outFile.canExecute()) {
                prefs.edit().putString(KEY_EXTRACTED_FROM, source).apply()
                outFile
            } else {
                Log.e(TAG, "Extracted engine binary is not executable: $outFile")
                null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not extract Stockfish from assets/$assetName", e)
            tmp.delete()
            null
        }
    }

    private fun installStamp(context: Context): Long = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.lastUpdateTime
    } catch (e: PackageManager.NameNotFoundException) {
        0L
    }
}
