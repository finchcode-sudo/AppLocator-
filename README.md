# 桌面应用定位器 (AppLocator)

定位安卓桌面每个应用图标位置的工具。**Root 优先**：直接读取桌面数据库 `launcher.db`，精确到"第几页/第几行/第几列/Dock栏/文件夹"；同时保留**无障碍模式**作为免 Root 兜底方案。

编译产物为单个 APK，可直接安装使用。

## 功能

- **Root 模式（首选，精确到格）**：通过 `su` 复制并解析桌面数据库 `launcher.db`，输出每个图标的 页码/行列/Dock栏/所在文件夹
- **无障碍模式（免 Root 兜底）**：读取桌面视图层级 + 模拟左滑自动翻页扫描
- 与系统已安装应用比对，标记「未在桌面」的应用（被隐藏/停用/卸载）
- 按应用名 / 包名搜索；点击列表项直接启动应用；一键导出清单
- 所有数据仅保存在本机（SharedPreferences），**不上传**

## 原理

### Root 模式（database 方案）
绝大多数桌面（Launcher3 系：AOSP / Pixel / Trebuchet / EMUI / 三星 OneUI / MIUI 等）把桌面布局存在 SQLite 数据库：

```
/data/user/0/<桌面包名>/databases/launcher.db
（部分 MIUI 为 app_icons.db）
```

关键表 `workspace`：

| 字段 | 含义 |
| --- | --- |
| screen | 页码（第几页，0 起） |
| cellX / cellY | 列 / 行（0 起） |
| container | -100=桌面 / -101=Dock栏 / 正数=所在文件夹 id |
| itemType | 0=应用图标 / 1=文件夹 / 2=小部件 / 4=快捷方式 |
| title / intent | 名称 / 启动 Intent（含包名 component） |

流程：`su -c "cat ..."` 把数据库（连同 `-wal`/`-shm`）流式复制到应用缓存 → `SQLiteDatabase` 本地只读解析 → 得到精确坐标。兼容性最好、结果 100% 精确。

### 无障碍模式（视图层级方案）
无障碍服务读取桌面窗口的 View Hierarchy，桌面图标通常表现为 `ImageView(contentDescription=应用名)` 或 `TextView(text=应用名)`；通过节点屏幕坐标聚类出网格行列，通过工作区（Workspace）子页面估算页码，通过祖先容器识别文件夹，并用手势模拟左滑逐页扫描。

```
┌─────────────────────────────┐
│ Launcher (桌面 Activity)     │
│  └─ Workspace (工作区)       │
│      ├─ CellLayout (第1页)   │  ← 页码
│      │   ├─ icon(微信)       │  ← contentDescription=应用名
│      │   ├─ icon(QQ)  (行,列)│  ← 屏幕坐标 → 网格行列
│      │   └─ 文件夹「工具」     │  ← 含多个应用的容器
│      └─ CellLayout (第2页)   │
└─────────────────────────────┘
```

## 构建 APK

### 方式一：Android Studio（推荐）
1. 克隆本项目，用 Android Studio 打开根目录
2. 等待 Gradle 同步完成（需 JDK 17+）
3. `Build → Build APK(s)`，或直接 Run 到手机
   产物在 `app/build/outputs/apk/debug/`

### 方式二：命令行
```bash
# 需要本机装有 JDK 17+ 与 Gradle 8.7+
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```

## 使用步骤

1. 安装 APK，打开应用
2. 点击「**Root扫描**」→ 在 su 授权弹窗中允许（Magisk / KernelSU 授予）
   - 若提示失败（未授权或桌面数据库特殊），可用兜底方案：
3. 兜底：点击「去开启无障碍服务」→ 开启「桌面应用定位服务」→ 回到桌面任意页 → 点「无障碍扫描」，期间请勿操作手机
4. 扫描完成后回到应用查看 / 搜索 / 导出

## 兼容性

| 桌面 | Root 模式 | 无障碍模式 |
| --- | --- | --- |
| AOSP Launcher3 / Pixel / Trebuchet | ✅ | ✅ |
| MIUI / HyperOS 桌面 | ✅ | ✅ |
| EMUI / HarmonyOS 桌面 | ✅ | ✅ |
| 三星 OneUI | ✅ | 基本支持 |
| OPPO / vivo / 一加 等 | ✅ | 基本支持 |
| 第三方桌面（Nova 等） | 视其数据库而定 | 可能部分识别 |

## 目录结构

```
app/src/main/java/com/example/applocator/
├── MainActivity.kt                        # 主界面：Root/无障碍扫描、搜索、导出
├── AppListAdapter.kt                      # 应用列表适配器
├── data/
│   ├── AppLocation.kt                     # 位置数据模型 + JSON 序列化
│   ├── AppRepository.kt                   # 本地存储 + 已安装应用查询
│   └── db/
│       ├── RootShell.kt                   # su 命令执行器（检测/复制数据库）
│       └── LauncherDbReader.kt            # Root 核心：定位并解析 launcher.db
└── service/
    ├── LauncherAccessibilityService.kt    # 无障碍服务：事件监听 + 自动翻页扫描
    └── GridMapper.kt                      # 无障碍核心：视图树解析、行列/页码/文件夹识别
```

## 适配新桌面（Root 模式）

若某桌面数据库字段命名不同，只需在 `LauncherDbReader.kt` 的 `col()` 映射里补充字段别名即可（如 `cellX / x`、`title / label / name`、`folderId / parent`）。

## 隐私声明

本项目完全离线，扫描结果仅保存在本机。Root 模式只复制桌面数据库到应用缓存解析；无障碍模式仅在桌面窗口可见时读取视图层级。不采集任何网络数据。

## License

MIT