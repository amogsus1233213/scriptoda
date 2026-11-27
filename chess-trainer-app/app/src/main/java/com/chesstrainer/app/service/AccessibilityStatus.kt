package com.chesstrainer.app.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager

object AccessibilityStatus {

    private const val TAG = "AccessibilityStatus"

    /**
     * Whether the user has switched the board reader on in system settings. This says nothing about
     * the service being connected right now; see [isServiceRunning] for that.
     *
     * The secure setting is the list the system itself reads and writes, so it decides whenever it
     * gives an answer. AccessibilityManager only lists services that are currently bound, so it
     * misses one that was just switched on or has crashed, and is asked only as a fallback.
     */
    fun isServiceEnabled(context: Context): Boolean {
        val service = ComponentName(context, BoardAccessibilityService::class.java)
        return enabledInSecureSettings(context, service) ?: listedByAccessibilityManager(context, service)
    }

    fun isServiceRunning(): Boolean {
        return BoardAccessibilityService.instance != null
    }

    fun openAccessibilitySettings(context: Context) {
        // Not every build ships the accessibility screen; the top-level settings still lead there.
        for (action in arrayOf(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: RuntimeException) {
                // ActivityNotFoundException or SecurityException, depending on the build.
                Log.w(TAG, "Could not open $action", e)
            }
        }
    }

    /** The secure setting's answer, or null when it is unset or cannot be read. */
    private fun enabledInSecureSettings(context: Context, service: ComponentName): Boolean? {
        val enabled = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (e: RuntimeException) {
            // Some manufacturer builds throw SecurityException here instead of returning the value.
            Log.w(TAG, "Could not read the enabled accessibility services setting", e)
            null
        } ?: return null
        // Colon-separated components, each in either the full or the short "package/.Class" form.
        return enabled.split(':').any { ComponentName.unflattenFromString(it.trim()) == service }
    }

    /** Fallback: whether AccessibilityManager lists the service among the enabled, bound ones. */
    private fun listedByAccessibilityManager(context: Context, service: ComponentName): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            // Typed nullable: the platform promises a list, but not every build keeps that promise.
            val services: List<AccessibilityServiceInfo>? =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            services.orEmpty().any { info ->
                val si = info.resolveInfo?.serviceInfo
                si != null && si.packageName == service.packageName && si.name == service.className
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not query AccessibilityManager", e)
            false
        }
    }
}
