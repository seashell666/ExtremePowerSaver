package com.example.extremepowersaver

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 以 root（Magisk su）执行 shell 命令的封装。
 */
object RootShell {

    private const val TAG = "EPS"

    data class Result(val ok: Boolean, val output: String, val exitCode: Int)

    private val SU_CANDIDATES = listOf(
        "/debug_ramdisk/su",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/su/bin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su"
    )

    /** 找一个可用的 su 路径，找不到返回 null。 */
    fun findSu(): String? {
        for (path in SU_CANDIDATES) {
            if (File(path).exists()) {
                Log.i(TAG, "found su: $path")
                return path
            }
        }
        return try {
            val p = ProcessBuilder("sh", "-c", "command -v su").redirectErrorStream(true).start()
            p.waitFor(3, TimeUnit.SECONDS)
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            Log.i(TAG, "command -v su => '$out'")
            if (out.isNotEmpty()) out else null
        } catch (t: Throwable) {
            Log.e(TAG, "findSu failed", t)
            null
        }
    }

    fun isRootGranted(timeoutSec: Long = 30): Boolean {
        val su = findSu() ?: run {
            Log.e(TAG, "no su binary")
            return false
        }
        return try {
            val p = ProcessBuilder(su, "-c", "id").redirectErrorStream(true).start()
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                Log.e(TAG, "su -c id timeout after ${timeoutSec}s")
                p.destroyForcibly()
                return false
            }
            val out = p.inputStream.bufferedReader().use { it.readText() }
            Log.i(TAG, "su -c id => $out")
            out.contains("uid=0")
        } catch (t: Throwable) {
            Log.e(TAG, "isRootGranted failed", t)
            false
        }
    }

    /**
     * 执行一条 shell 命令（通过 su -c）。
     * 命令本身作为一个参数传给 su，避免本地再做一层转义。
     */
    fun run(command: String, timeoutSec: Long = 30): Result {
        val su = findSu() ?: return Result(false, "未找到 su，请确认 Magisk 已授权本应用", -1)
        Log.i(TAG, "run cmd: $command")
        return try {
            val p = ProcessBuilder(su, "-c", command)
                .redirectErrorStream(true)
                .start()

            // 轮询等待：Activity 被系统销毁时本线程可能被中断，
            // 此时命令其实已经下发，不能当作失败。
            val deadline = System.currentTimeMillis() + timeoutSec * 1000
            var finished = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (p.waitFor(200, TimeUnit.MILLISECONDS)) {
                        finished = true
                        break
                    }
                } catch (e: InterruptedException) {
                    Log.w(TAG, "wait interrupted, command already dispatched")
                    return Result(true, "命令已下发（进程被系统回收，无法回读结果）", 0)
                }
            }
            if (!finished) {
                Log.e(TAG, "cmd timeout")
                p.destroyForcibly()
                return Result(false, "命令超时（${timeoutSec}s）", -1)
            }
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = p.exitValue()
            Log.i(TAG, "cmd exit=$code out=$out")
            Result(code == 0, out.ifEmpty { "exit=$code" }, code)
        } catch (t: Throwable) {
            Log.e(TAG, "run failed", t)
            Result(false, "执行异常: ${t.message}", -1)
        }
    }
}
