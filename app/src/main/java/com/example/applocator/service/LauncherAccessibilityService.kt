package com.example.applocator.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.applocator.data.AppLocation
import kotlinx.coroutines.*

/**
 * 无障碍模式（免 Root 兜底方案）：
 * 读取桌面视图层级，通过图标节点坐标估算行列，模拟左滑逐页扫描。
 */
class LauncherAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AppLocator"
        private const val MAX_PAGES = 30

        @Volatile
        var instance: LauncherAccessibilityService? = null
            private set

        @Volatile
        var launcherVisible: Boolean = false
            private set

        @Volatile
        var scanning: Boolean = false
            private set

        /** 扫描完成回调 */
        var onScanFinished: ((List<AppLocation>, List<String>) -> Unit)? = null

        // 常见桌面/启动器包名（兜底识别）
        private val LAUNCHERS = setOf(
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.sec.android.app.launcher",
            "com.oppo.launcher",
            "com.vivo.launcher",
            "com.coloros.launcher",
            "com.bbk.launcher",
            "com.oneplus.launcher",
            "com.meizu.flyme.launcher",
            "com.smartisanos.launcher",
            "com.tencent.launcher"
        )
    }

    data class AppInfo(val label: String, val packageName: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanJob: Job? = null
    private var launcherPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        launcherPackage = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        Log.i(TAG, "无障碍服务已连接，默认桌面: $launcherPackage")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val isLauncher = launcherPackage == pkg || LAUNCHERS.contains(pkg)
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                launcherVisible = isLauncher
                if (isLauncher) scheduleScan(700)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (launcherVisible && isLauncher) scheduleScan(500)
            }
        }
    }

    override fun onInterrupt() {}

    private fun scheduleScan(delayMs: Long) {
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(delayMs)
            if (!scanning) scanOnce()
        }
    }

    /** 单次扫描当前可见页面 */
    private fun scanOnce() {
        val root = rootInActiveWindow ?: return
        val labels = buildLabelCache()
        val page = GridMapper.estimatePageIndex(root) ?: 0
        val list = GridMapper.scanPage(root, labels, page)
        if (list.isNotEmpty()) {
            onScanFinished?.invoke(list, labels.keys.toList())
        }
    }

    /** 自动翻页扫描全部桌面（由 UI 触发） */
    fun startAutoScan() {
        if (scanning) return
        scanning = true
        scope.launch {
            try {
                val labels = buildLabelCache()
                val results = LinkedHashMap<String, AppLocation>()
                val firstPageKeys = HashSet<String>()
                var page = 0
                var lastKey: Set<String>? = null
                var emptyStreak = 0

                while (page < MAX_PAGES) {
                    delay(800) // 等待页面动画稳定
                    val root = rootInActiveWindow ?: break
                    val list = GridMapper.scanPage(root, labels, page)
                    val key = list.map { "${it.col},${it.row}" }.toSet()

                    if (page == 0) {
                        firstPageKeys.addAll(key)
                    } else if (key == firstPageKeys || (list.isNotEmpty() && key == lastKey)) {
                        break // 回到第一页，或页面不再变化（最后一页）
                    }
                    lastKey = key
                    list.forEach { results.putIfAbsent(it.packageName, it) }

                    if (list.isEmpty()) {
                        if (++emptyStreak >= 2) break
                    } else emptyStreak = 0

                    if (!swipeLeft()) break
                    page++
                }

                val merged = mergeWithInstalled(results.values.toList(), labels)
                onScanFinished?.invoke(merged, labels.keys.toList())
                Log.i(TAG, "自动扫描完成，共 ${merged.size} 条")
            } finally {
                scanning = false
            }
        }
    }

    /** 与已安装应用对比，标记未在桌面的应用 */
    private fun mergeWithInstalled(
        located: List<AppLocation>,
        labels: Map<String, List<AppInfo>>
    ): List<AppLocation> {
        val foundPkgs = located.map { it.packageName }.toHashSet()
        val extra = labels.values.flatten()
            .filter { it.packageName !in foundPkgs }
            .map { AppLocation(it.label, it.packageName, -1, -1, -1, null, false) }
        return (located + extra).sortedBy { it.label.lowercase() }
    }

    private fun buildLabelCache(): Map<String, List<AppInfo>> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val map = HashMap<String, MutableList<AppInfo>>()
        pm.queryIntentActivities(intent, 0).forEach { ri ->
            val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty()
            if (label.isNotEmpty()) {
                map.getOrPut(label) { mutableListOf() }
                    .add(AppInfo(label, ri.activityInfo.packageName))
            }
        }
        return map
    }

    private fun swipeLeft(): Boolean {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        if (width <= 0 || height <= 0) return false
        val path = Path().apply {
            moveTo(width * 0.92f, height * 0.5f)
            lineTo(width * 0.08f, height * 0.5f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}