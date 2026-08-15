package com.example.applocator.data.db

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 基于 su 的 Root 命令执行器。
 */
object RootShell {

    /** 检测是否已 root 且 su 可用 */
    fun isRooted(): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(5, TimeUnit.SECONDS)
        out.contains("uid=0")
    }.getOrDefault(false)

    /** 执行一条 su 命令，返回 stdout（失败时返回 stderr） */
    fun exec(cmd: String): String? = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor(15, TimeUnit.SECONDS)
        out.ifBlank { err }
    }.getOrNull()

    /** 以二进制方式把需 root 权限的远程文件流式复制到本地文件 */
    fun catToFile(remotePath: String, localFile: File): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat \"$remotePath\""))
        localFile.outputStream().use { os -> p.inputStream.copyTo(os) }
        p.waitFor(20, TimeUnit.SECONDS)
        localFile.length() > 0
    }.getOrDefault(false)

    /** 文件是否存在（root 视角） */
    fun exists(path: String): Boolean =
        exec("test -e \"$path\" && echo YES || echo NO")?.trim() == "YES"
}