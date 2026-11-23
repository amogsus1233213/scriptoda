package com.chesstrainer.app.service

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object AccessibilityStatus {

    fun isServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val myService = "${context.packageName}/.service.BoardAccessibilityService"
        return enabledServices.any {
            it.resolveInfo?.serviceInfo?.let { si ->
                "${si.packageName}/${si.name}" == "${context.packageName}/com.chesstrainer.app.service.BoardAccessibilityService"
            } == true
        }
    }

    fun isServiceRunning(): Boolean {
        return BoardAccessibilityService.instance != null
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
