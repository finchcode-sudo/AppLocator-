package com.example.applocator.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray

class AppRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("app_locations", Context.MODE_PRIVATE)

    fun save(list: List<AppLocation>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit()
            .putString("data", arr.toString())
            .putBoolean("scanned", true)
            .apply()
    }

    fun load(): List<AppLocation> {
        val raw = prefs.getString("data", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { AppLocation.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun hasScanned(): Boolean = prefs.getBoolean("scanned", false)

    companion object {
        /** 列出所有可启动（出现在抽屉/桌面的）应用 */
        fun installedApps(context: Context): List<InstalledApp> {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
                val label = ri.loadLabel(pm)?.toString() ?: return@mapNotNull null
                InstalledApp(label, ri.activityInfo.packageName)
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        }
    }

    data class InstalledApp(val label: String, val packageName: String)
}