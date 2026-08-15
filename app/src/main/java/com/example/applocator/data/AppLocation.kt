package com.example.applocator.data

import org.json.JSONObject

/**
 * 应用在桌面上的位置。
 *
 * @param page  页码（0 起，-1 表示未知）
 * @param row   行（0 起）
 * @param col   列（0 起）
 * @param folder 所在文件夹名（null 表示直接在页面空白处）
 * @param dock  是否位于底部 Dock 栏
 */
data class AppLocation(
    val label: String,
    val packageName: String,
    val page: Int,
    val row: Int,
    val col: Int,
    val folder: String?,
    val onDesktop: Boolean,
    val dock: Boolean = false
) {

    fun positionText(): String = when {
        !onDesktop -> "未在桌面（被隐藏/停用，或需要重新扫描）"
        dock -> "Dock栏 · 第 ${col + 1} 个"
        folder != null -> "第 ${page + 1} 页 · 文件夹「$folder」· 第 ${row + 1} 行第 ${col + 1} 列"
        page < 0 -> "在桌面，位置未知（请重新扫描）"
        else -> "第 ${page + 1} 页 · 第 ${row + 1} 行 · 第 ${col + 1} 列"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("label", label)
        put("package", packageName)
        put("page", page)
        put("row", row)
        put("col", col)
        put("folder", folder ?: "")
        put("onDesktop", onDesktop)
        put("dock", dock)
    }

    companion object {
        fun fromJson(o: JSONObject) = AppLocation(
            label = o.optString("label"),
            packageName = o.optString("package"),
            page = o.optInt("page", -1),
            row = o.optInt("row", -1),
            col = o.optInt("col", -1),
            folder = o.optString("folder").ifBlank { null },
            onDesktop = o.optBoolean("onDesktop", false),
            dock = o.optBoolean("dock", false)
        )
    }
}