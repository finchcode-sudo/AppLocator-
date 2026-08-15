package com.example.applocator.data.db

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.applocator.data.AppLocation
import java.io.File

/**
 * Root 模式：读取桌面数据库 launcher.db，解析每个应用图标的精确位置。
 *
 * 支持 Launcher3 系（AOSP/Pixel/Trebuchet/EMUI/三星/MIUI 等）通用 schema：
 * workspace 表：screen(页码)、cellX(列)、cellY(行)、container(-100 桌面 / -101 Dock / 文件夹id)、
 * itemType(0 应用 / 1 文件夹 / 2 小部件 / 4 快捷方式)、title、intent
 */
object LauncherDbReader {

    private const val TAG = "LauncherDb"

    private const val CONTAINER_DESKTOP = -100L
    private const val CONTAINER_HOTSEAT = -101L
    private const val ITEM_APP = 0
    private const val ITEM_FOLDER = 1
    private const val ITEM_DEEP_SHORTCUT = 4

    // 各桌面数据库中的条目表名（Launcher3系：workspace/favorites；ColorOS/OPPO：singledesktopitems）
    private val TABLE_CANDIDATES = listOf(
        "workspace", "favorites", "singledesktopitems", "singledesktopitems_simple"
    )

    // 常见桌面/启动器包名（兜底遍历数据库，避免包可见性限制导致解析不到）
    private val CANDIDATE_LAUNCHERS = listOf(
        "com.android.launcher",               // AOSP / ColorOS / OPPO 系统桌面
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.oplus.launcher",                 // ColorOS 12+
        "com.coloros.launcher",               // ColorOS 11-
        "com.oneplus.launcher",
        "com.oppo.launcher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.sec.android.app.launcher",
        "com.vivo.launcher",
        "com.meizu.flyme.launcher"
    )

    private data class WorkspaceRow(
        val id: Long,
        val screen: Int,
        val cellX: Int,
        val cellY: Int,
        val container: Long,
        val itemType: Int,
        val title: String?,
        val intentUri: String?,
        val folderId: Long
    )

    data class ScanResult(
        val launcherPackage: String,
        val dbPath: String,
        val items: List<AppLocation>
    )

    /** 找到当前默认桌面包名 */
    fun resolveLauncher(context: Context): String? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }.getOrNull()

    /** 用 root shell 解析默认桌面（不受包可见性限制，优先使用） */
    fun resolveLauncherViaShell(): String? {
        val out = RootShell.exec(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
        ) ?: return null
        val line = out.lineSequence().lastOrNull { it.contains("/") && !it.contains(" ") }
            ?: return null
        return line.substringBefore("/")
    }

    /** 扫描：返回 null 表示 root 不可用或数据库读取失败 */
    fun scan(context: Context): ScanResult? {
        if (!RootShell.isRooted()) return null
        val cacheDir = File(context.cacheDir, "launcher_db").apply { mkdirs() }

        // 候选桌面包名：shell 解析 > PackageManager 解析 > 已知列表兜底
        val pkgs = LinkedHashSet<String>()
        resolveLauncherViaShell()?.let { pkgs.add(it) }
        resolveLauncher(context)?.let { pkgs.add(it) }
        pkgs.addAll(CANDIDATE_LAUNCHERS)

        // 遍历包名 + 路径组合，找到第一个可用的桌面数据库
        for (pkg in pkgs) {
            val result = findAndParseDb(context, pkg, cacheDir)
            if (result != null) return result
        }
        return null
    }

    /** 对单个桌面包名尝试所有数据库路径，成功则解析 */
    private fun findAndParseDb(
        context: Context,
        pkg: String,
        cacheDir: File
    ): ScanResult? {
        // 候选数据库路径（兼容不同 ROM，含设备加密存储 user_de）
        val candidates = buildList {
            for (base in listOf(
                "/data/user_de/0/$pkg",
                "/data/user/0/$pkg",
                "/data/data/$pkg"
            )) {
                for (rel in listOf("databases/launcher.db", "databases/app_icons.db")) {
                    add("$base/$rel")
                }
            }
        }.distinct()

        for (p in candidates) {
            if (!RootShell.exists(p)) continue
            val local = File(cacheDir, "launcher.db")
            if (!RootShell.catToFile(p, local) || local.length() <= 10) continue

            // 连同 WAL/journal 一起复制，避免丢失最新写入
            RootShell.catToFile(p + "-wal", File(cacheDir, "launcher.db-wal"))
            RootShell.catToFile(p + "-shm", File(cacheDir, "launcher.db-shm"))
            RootShell.catToFile(p + "-journal", File(cacheDir, "launcher.db-journal"))

            val rows = readWorkspaceRows(local) ?: continue
            if (rows.isEmpty()) continue
            return ScanResult(pkg, p, buildItems(context, rows))
        }
        return null
    }

    private fun readWorkspaceRows(dbFile: File): List<WorkspaceRow>? = runCatching {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val tables = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'", null
            ).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            val table = tables.firstOrNull { it in TABLE_CANDIDATES } ?: return null

            // 列名映射（兼容不同桌面）
            val cols = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(1)) }
            }.toSet()
            fun col(vararg names: String) = names.firstOrNull { it in cols } ?: ""

            val idC = col("_id", "id")
            val screenC = col("screen", "screenId", "page")
            val cellXC = col("cellX", "x")
            val cellYC = col("cellY", "y")
            val containerC = col("container")
            val itemTypeC = col("itemType", "item_type")
            val titleC = col("title", "label", "name")
            val intentC = col("intent", "intentUri", "uri")
            val folderIdC = col("folderId", "folder_id", "parent")
            if (screenC.isBlank() && cellXC.isBlank() && cellYC.isBlank()) return null

            val out = mutableListOf<WorkspaceRow>()
            db.rawQuery("SELECT * FROM $table", null).use { c ->
                fun getInt(name: String): Int =
                    if (name.isBlank()) 0 else c.getInt(c.getColumnIndexOrThrow(name))
                fun getLong(name: String): Long =
                    if (name.isBlank()) 0L else c.getLong(c.getColumnIndexOrThrow(name))
                fun getStr(name: String): String? =
                    if (name.isBlank()) null else c.getString(c.getColumnIndexOrThrow(name))

                while (c.moveToNext()) {
                    out.add(
                        WorkspaceRow(
                            id = getLong(idC),
                            screen = getInt(screenC),
                            cellX = getInt(cellXC),
                            cellY = getInt(cellYC),
                            container = getLong(containerC),
                            itemType = getInt(itemTypeC),
                            title = getStr(titleC),
                            intentUri = getStr(intentC),
                            folderId = getLong(folderIdC)
                        )
                    )
                }
            }
            out
        } finally {
            db.close()
        }
    }.getOrElse { e ->
        Log.e(TAG, "读取桌面数据库失败", e)
        null
    }

    private fun buildItems(context: Context, rows: List<WorkspaceRow>): List<AppLocation> {
        val pm = context.packageManager

        // 文件夹标题映射
        val folderTitles = HashMap<Long, String>()
        rows.filter { it.itemType == ITEM_FOLDER }.forEach { folderTitles[it.id] = it.title ?: "文件夹" }

        val items = mutableListOf<AppLocation>()

        for (r in rows) {
            when (r.itemType) {
                ITEM_FOLDER -> {
                    // 文件夹本体（用于定位文件夹位置）
                    items += AppLocation(
                        label = "📁 ${r.title ?: "文件夹"}",
                        packageName = "",
                        page = r.screen,
                        row = r.cellY,
                        col = r.cellX,
                        folder = null,
                        onDesktop = true
                    )
                    // 文件夹内的应用：直接展开
                    rows.filter { it.container == r.id }.forEach { ch ->
                        val (label, pkg) = resolveApp(pm, ch)
                        items += AppLocation(
                            label = label,
                            packageName = pkg,
                            page = r.screen,
                            row = ch.cellY,
                            col = ch.cellX,
                            folder = r.title ?: "文件夹",
                            onDesktop = true
                        )
                    }
                }
                ITEM_APP, ITEM_DEEP_SHORTCUT -> {
                    val (label, pkg) = resolveApp(pm, r)
                    if (pkg.isBlank()) continue
                    val isDock = r.container == CONTAINER_HOTSEAT
                    items += AppLocation(
                        label = label,
                        packageName = pkg,
                        page = if (isDock) 0 else r.screen,
                        row = r.cellY,
                        col = r.cellX,
                        folder = null,
                        onDesktop = true,
                        dock = isDock
                    )
                }
            }
        }
        return items.sortedWith(compareBy({ it.page }, { it.row }, { it.col }))
    }

    private fun resolveApp(pm: PackageManager, r: WorkspaceRow): Pair<String, String> {
        val pkg = parseComponentPackage(r.intentUri)
        val label = if (pkg != null) {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))?.toString() }
                .getOrNull()
        } else null
        return (label ?: r.title ?: pkg ?: "未知应用") to (pkg ?: "")
    }

    /** 从 Launcher 的 intent URI 中解析出包名 */
    fun parseComponentPackage(intentUri: String?): String? {
        if (intentUri.isNullOrBlank()) return null
        return runCatching {
            val intent = Intent.parseUri(intentUri, 0)
            intent.component?.packageName
        }.getOrNull()
    }
}
