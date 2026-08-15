package com.example.applocator.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.applocator.data.AppLocation

/**
 * 无障碍模式核心：解析桌面视图树，识别图标、行列、页码、文件夹。
 */
object GridMapper {

    private const val TAG = "GridMapper"

    class Hit(
        val label: String,
        val bounds: Rect,
        val folder: String?,
        val node: AccessibilityNodeInfo
    )

    /** 扫描一页，page 为页码（从 0 开始） */
    fun scanPage(
        root: AccessibilityNodeInfo,
        labels: Map<String, List<LauncherAccessibilityService.AppInfo>>,
        page: Int
    ): List<AppLocation> {
        val hits = mutableListOf<Hit>()
        collectHits(root, labels, hits, 0)

        // 去重：同一应用可能有 icon + 文本两个节点
        val dedup = linkedMapOf<String, Hit>()
        hits.forEach { h ->
            val key = dedupKey(h)
            val old = dedup[key]
            if (old == null || h.bounds.width() > old.bounds.width()) dedup[key] = h
        }
        val uniq = dedup.values.toList()
        if (uniq.isEmpty()) return emptyList()

        // 按页内坐标聚类出行列
        val xs = distinctSorted(uniq.map { it.bounds.centerX() })
        val ys = distinctSorted(uniq.map { it.bounds.centerY() })

        return uniq.mapNotNull { h ->
            val ci = xs.indexOf(h.bounds.centerX())
            val ri = ys.indexOf(h.bounds.centerY())
            if (ci < 0 || ri < 0) null else AppLocation(
                label = h.label,
                packageName = resolvePackage(labels, h),
                page = page,
                row = ri,
                col = ci,
                folder = h.folder,
                onDesktop = true
            )
        }
    }

    /** 估算当前可见页的页码（通过工作区子页面的位置） */
    fun estimatePageIndex(root: AccessibilityNodeInfo): Int? {
        val workspace = findWorkspace(root) ?: return null
        val pages = findPageChildren(workspace)
        if (pages.size <= 1) return 0
        val screen = Rect()
        root.getBoundsInScreen(screen)
        val screenCenterX = screen.centerX()
        var best = 0
        var bestDist = Int.MAX_VALUE
        pages.forEachIndexed { index, p ->
            val r = Rect()
            p.getBoundsInScreen(r)
            val dist = kotlin.math.abs(r.centerX() - screenCenterX)
            if (dist < bestDist) { bestDist = dist; best = index }
        }
        return best
    }

    private fun dedupKey(h: Hit): String =
        "${h.label}@${h.bounds.centerX() / 20},${h.bounds.centerY() / 20}"

    private fun resolvePackage(
        labels: Map<String, List<LauncherAccessibilityService.AppInfo>>,
        h: Hit
    ): String {
        val list = labels[h.label] ?: return h.label
        if (list.size == 1) return list[0].packageName
        val np = h.node.packageName?.toString()
        list.firstOrNull { it.packageName == np }?.let { return it.packageName }
        return list[0].packageName
    }

    private fun collectHits(
        node: AccessibilityNodeInfo?,
        labels: Map<String, List<LauncherAccessibilityService.AppInfo>>,
        out: MutableList<Hit>,
        depth: Int
    ) {
        if (node == null || depth > 30) return
        val cls = node.className?.toString().orEmpty()
        val desc = node.contentDescription?.toString()
        val text = if (cls.contains("TextView") || cls.contains("EditText")) node.text?.toString() else null
        val label = when {
            desc != null && labels.containsKey(desc) -> desc
            text != null && labels.containsKey(text) -> text
            else -> null
        }

        if (label != null && node.isVisibleToUser) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0 && r.width() < 800 && r.height() < 800) {
                val folder = findFolder(node, labels, 2)
                out.add(Hit(label, r, folder, node))
            }
        }

        for (i in 0 until node.childCount) {
            collectHits(node.getChild(i), labels, out, depth + 1)
        }
    }

    /** 向上查找：该图标是否位于某个有名文件夹中 */
    private fun findFolder(
        node: AccessibilityNodeInfo,
        labels: Map<String, List<LauncherAccessibilityService.AppInfo>>,
        maxUp: Int
    ): String? {
        var cur = node.parent
        var up = 0
        while (cur != null && up < maxUp) {
            val name = cur.contentDescription?.toString() ?: cur.text?.toString()
            if (!name.isNullOrBlank() && !labels.containsKey(name)) {
                val appLabels = HashSet<String>()
                countAppChildren(cur, labels, appLabels)
                if (appLabels.size > 1) return name
            }
            cur = cur.parent
            up++
        }
        return null
    }

    private fun countAppChildren(
        node: AccessibilityNodeInfo,
        labels: Map<String, List<LauncherAccessibilityService.AppInfo>>,
        out: MutableSet<String>
    ) {
        val cls = node.className?.toString().orEmpty()
        val desc = node.contentDescription?.toString()
        val text = if (cls.contains("TextView")) node.text?.toString() else null
        if (desc != null && labels.containsKey(desc)) { out.add(desc); return }
        if (text != null && labels.containsKey(text)) { out.add(text); return }
        for (i in 0 until node.childCount) {
            countAppChildren(node.getChild(i), labels, out)
        }
    }

    private fun findWorkspace(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 5000) {
            val n = queue.removeFirst()
            val cls = n.className?.toString().orEmpty()
            val childCount = n.childCount
            val score = when {
                cls.contains("Workspace") -> 10000 + childCount
                cls.contains("PagedView") -> 9000 + childCount
                cls.contains("CellLayout") -> 5000 + childCount
                cls.contains("Launcher") -> 4000 + childCount
                else -> childCount
            }
            if (score > bestScore) { bestScore = score; best = n }
            for (i in 0 until childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    private fun findPageChildren(workspace: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val pages = mutableListOf<AccessibilityNodeInfo>()
        for (i in 0 until workspace.childCount) {
            val c = workspace.getChild(i) ?: continue
            val cls = c.className?.toString().orEmpty()
            if (cls.contains("CellLayout") || cls.contains("Page") || cls.contains("Workspace")) {
                val r = Rect()
                c.getBoundsInScreen(r)
                if (r.width() > 0) pages.add(c)
            }
        }
        return pages
    }

    private fun distinctSorted(values: List<Int>): List<Int> {
        val sorted = values.sorted()
        val result = mutableListOf<Int>()
        sorted.forEach { v ->
            if (result.isEmpty() || kotlin.math.abs(v - result.last()) > 30) result.add(v)
        }
        return result
    }
}