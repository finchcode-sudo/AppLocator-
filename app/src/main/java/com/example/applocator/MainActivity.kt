package com.example.applocator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.applocator.data.AppLocation
import com.example.applocator.data.AppRepository
import com.example.applocator.data.db.LauncherDbReader
import com.example.applocator.data.db.RootShell
import com.example.applocator.databinding.ActivityMainBinding
import com.example.applocator.service.LauncherAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AppListAdapter
    private val repository by lazy { AppRepository(this) }
    private var allApps: List<AppLocation> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = AppListAdapter { app -> openApp(app.packageName) }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnRootScan.setOnClickListener { rootScan() }
        binding.btnAccessScan.setOnClickListener { triggerAccessibilityScan() }
        binding.btnEnableAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnShare.setOnClickListener { shareResult() }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })

        refreshData()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
        LauncherAccessibilityService.onScanFinished = { locations, _ ->
            repository.save(locations)
            Toast.makeText(this, "扫描完成：共定位 ${locations.count { it.onDesktop }} 个应用", Toast.LENGTH_LONG).show()
            refreshData()
        }
    }

    override fun onPause() {
        super.onPause()
        LauncherAccessibilityService.onScanFinished = null
    }

    // ---------- Root 扫描（最精确） ----------
    private fun rootScan() {
        binding.statusText.text = "状态：正在通过 Root 读取桌面数据库…"
        Thread {
            val result = LauncherDbReader.scan(this)
            runOnUiThread {
                if (result == null) {
                    binding.statusText.text = "状态：Root 读取失败（su 是否已授权？）可改用无障碍扫描"
                    Toast.makeText(
                        this,
                        "Root 扫描失败（root=${RootShell.isRooted()}）",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    repository.save(result.items)
                    binding.statusText.text = "状态：Root 扫描完成（${result.dbPath}）"
                    Toast.makeText(
                        this,
                        "Root 扫描完成，定位 ${result.items.count { it.onDesktop }} 个应用",
                        Toast.LENGTH_LONG
                    ).show()
                    refreshData()
                }
            }
        }.start()
    }

    // ---------- 无障碍扫描（免 Root 兜底） ----------
    private fun triggerAccessibilityScan() {
        if (!isServiceEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val svc = LauncherAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, "无障碍服务未连接，请检查设置", Toast.LENGTH_SHORT).show()
            return
        }
        if (!LauncherAccessibilityService.launcherVisible) {
            Toast.makeText(this, "请先回到桌面再扫描", Toast.LENGTH_SHORT).show()
            binding.statusText.text = "状态：请回到桌面后点击扫描"
            return
        }
        svc.startAutoScan()
        binding.statusText.text = "状态：正在自动翻页扫描桌面…（请勿操作手机）"
    }

    private fun isServiceEnabled(): Boolean = runCatching {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(AccessibilityManager.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }.getOrDefault(false)

    // ---------- 数据刷新 ----------
    private fun refreshData() {
        if (!repository.hasScanned()) {
            allApps = emptyList()
            adapter.submitList(emptyList())
            binding.emptyHint.visibility = View.VISIBLE
            binding.statusText.text = "尚未扫描。Root：直接点「Root扫描」；免 Root：开启无障碍 → 回桌面 → 点「无障碍扫描」"
            return
        }
        binding.emptyHint.visibility = View.GONE

        val located = repository.load()
        val byPkg = located.associateBy { it.packageName }
        val missing = AppRepository.installedApps(this)
            .filter { it.packageName !in byPkg }
            .map { AppLocation(it.label, it.packageName, -1, -1, -1, null, false) }
        allApps = (located + missing).sortedBy { it.label.lowercase() }
        adapter.submitList(allApps)
        updateStatus()
    }

    private fun updateStatus() {
        binding.statusText.text = buildString {
            append("无障碍：${if (isServiceEnabled()) "开" else "关"} · ")
            append("桌面可见：${if (LauncherAccessibilityService.launcherVisible) "是" else "否"} · ")
            append("已定位 ${allApps.count { it.onDesktop }} / ${allApps.size} 个应用")
        }
    }

    private fun filter(q: String) {
        if (q.isBlank()) { adapter.submitList(allApps); return }
        adapter.submitList(
            allApps.filter {
                it.label.contains(q, true) || it.packageName.contains(q, true)
            }
        )
    }

    private fun shareResult() {
        val sb = StringBuilder("桌面应用位置清单（${repository.load().count { it.onDesktop }} 个在桌面）\n")
        allApps.forEach { sb.append("${it.label}\t${it.packageName}\t${it.positionText()}\n") }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "桌面应用位置")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(intent, "导出扫描结果"))
    }

    private fun openApp(pkg: String) {
        if (pkg.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开 $pkg", Toast.LENGTH_SHORT).show()
        }
    }
}