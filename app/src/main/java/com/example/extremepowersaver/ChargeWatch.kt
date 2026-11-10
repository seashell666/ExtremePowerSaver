package com.example.extremepowersaver

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 「插上充电 → 自动退出超级省电」的守护进程管理。
 *
 * 为什么不用 App 内的 BroadcastReceiver / JobScheduler：
 * 超级省电模式会把第三方 App 冻结并禁止其后台运行，广播根本收不到；
 * 而 -Scheduler 的充电约束最小粒度是分钟级，做不到「一插上就退」。
 *
 * 因此这里把一个 root shell 守护脚本安装到 Magisk 的 service.d 目录：
 *   - 开机由 Magisk 以 root 自动启动，不依赖 App 存活
 *   - 进程位于 root cgroup（实测 Magisk su 会把子进程移出 uid cgroup），
 *     不会被系统的 app freezer 冻结
 *   - 死循环只用 shell 内建 read 读 sysfs，稳态零 fork、零 wakelock
 */
object ChargeWatch {

    private const val TAG = "EPS"
    const val SCRIPT_NAME = "eps_charge_watch.sh"
    const val INSTALLED_PATH = "/data/adb/service.d/$SCRIPT_NAME"

    /**
     * 与 assets/eps_charge_watch.sh 里的 EPS_SCRIPT_VERSION 保持一致。
     * 不一致时 App 会自动覆盖重装并重启守护进程——否则已安装的旧脚本永远不升级，
     * 会出现「APK 更新了但行为还是老样子」的诡异故障。
     */
    const val SCRIPT_VERSION = "12"

    private const val PIDFILE = "/data/local/tmp/eps_charge_watch.pid"

    /**
     * 闲置子进程的 pid。必须单独记：
     * 它常年阻塞在 `timeout 447 dd` 上，mksh 的 trap 要等前台命令结束才执行，
     * 所以 TERM 杀不掉它，最长要等满一个超时周期（447 秒）。
     * 这期间它还能执行「回桌面+息屏」——表现就是「开关关了/换了配置还会黑屏」。
     * 停进程时必须单独对它 kill -9。
     */
    private const val IDLEPIDFILE = "/data/local/tmp/eps_charge_watch.idle.pid"

    private const val LOGFILE = "/data/local/tmp/eps_charge_watch.log"

    /**
     * 开关状态通过这个配置文件下发给守护脚本。
     * 脚本启动时读一次，所以改完开关必须重启进程才生效——install() 里已经包含重启。
     */
    private const val CFG = "/data/local/tmp/eps_charge_watch.cfg"

    /**
     * 校验 pidfile 里的 pid 确实是本守护脚本。
     * 少了这一步，重启后 pid 被复用就会误判「运行中」，甚至 kill 掉无关进程。
     */
    private const val IS_OURS =
        "tr '\\0' ' ' < /proc/\$p/cmdline 2>/dev/null | grep -q eps_charge_watch"

    data class Status(val installed: Boolean, val pid: String?, val version: String? = null) {
        val running: Boolean get() = !pid.isNullOrBlank()
        /** 已安装但版本落后，需要覆盖升级 */
        val outdated: Boolean get() = installed && version != SCRIPT_VERSION
    }

    /** 查询安装与运行状态。需要 root。 */
    fun status(): Status {
        val cmd = "if [ -f $INSTALLED_PATH ]; then echo __INSTALLED__; " +
            "sed -n 's/^EPS_SCRIPT_VERSION=//p' $INSTALLED_PATH 2>/dev/null | head -n1 | sed 's/^/__VER__/'; fi; " +
            "if [ -f $PIDFILE ]; then p=\$(cat $PIDFILE 2>/dev/null); " +
            "if [ -n \"\$p\" ] && kill -0 \$p 2>/dev/null && $IS_OURS; then echo __PID__\$p; fi; fi; " +
            "echo __END__"

        val r = RootShell.run(cmd, 20)
        Log.i(TAG, "status => exit=${r.exitCode} out=${r.output}")

        var installed = false
        var pid: String? = null
        var version: String? = null
        r.output.lineSequence().forEach { line ->
            val t = line.trim()
            if (t == "__INSTALLED__") installed = true
            if (t.startsWith("__PID__")) pid = t.removePrefix("__PID__").trim()
            if (t.startsWith("__VER__")) version = t.removePrefix("__VER__").trim().ifBlank { null }
        }
        return Status(installed, pid, version)
    }

    /** 按当前开关拼出配置文件应有的内容，用于比对是否「跑偏」。 */
    private fun configText(ctx: Context): String {
        val charge = if (Prefs.autoExitOnCharge(ctx)) 1 else 0
        val idle = if (Prefs.idleScreenOff(ctx)) 1 else 0
        return "charge=$charge\nidle=$idle\nidle_timeout=${Prefs.IDLE_TIMEOUT_SEC}"
    }

    /**
     * 把两个开关的状态写成脚本能读的配置。
     * 用 printf 直接落盘，避免多一次文件读写。
     */
    private fun writeConfig(ctx: Context): Boolean {
        val r = RootShell.run(
            "printf '${configText(ctx).replace("\n", "\\n")}\\n' > $CFG; " +
                "chmod 644 $CFG; cat $CFG",
            20
        )
        Log.i(TAG, "writeConfig => ${r.output}")
        return r.ok && r.output.contains("charge=")
    }

    /**
     * 安装脚本并立即启动（无需重启）。
     * 返回 (成功, 说明)。
     */
    fun install(ctx: Context): Pair<Boolean, String> {
        // 0. 先把开关状态下发，脚本启动时会读
        if (!writeConfig(ctx)) {
            return false to "配置写入失败，请确认已授予 root 权限"
        }

        // 1. 把 assets 里的脚本落到 App 私有目录（root 可读）
        val src = File(ctx.filesDir, SCRIPT_NAME)
        try {
            ctx.assets.open(SCRIPT_NAME).use { input ->
                src.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "copy asset failed", t)
            return false to "脚本提取失败: ${t.message}"
        }

        // 2. root 安装到 service.d 并做语法检查。
        //    用 tr 去掉可能的 \r：Windows 上编辑过的脚本会带 CRLF，
        //    而 sh -n 对 CRLF 是「语法通过」（\r 被当成一个不存在的命令），
        //    装到手机上才发现每行都报 inaccessible or not found。
        val install = RootShell.run(
            "mkdir -p /data/adb/service.d; " +
                "tr -d '\\r' < '${src.absolutePath}' > $INSTALLED_PATH; " +
                "chmod 755 $INSTALLED_PATH; " +
                "if sh -n $INSTALLED_PATH 2>/dev/null; then echo __SYNTAX_OK__; else echo __SYNTAX_FAIL__; fi",
            40
        )
        if (!install.ok || !install.output.contains("__SYNTAX_OK__")) {
            Log.e(TAG, "install failed: ${install.output}")
            return false to "安装失败: ${install.output.take(200)}"
        }

        // 3. 先停旧实例，再立即拉起
        stop()

        val start = RootShell.run(
            "nohup sh $INSTALLED_PATH >/dev/null 2>&1 & " +
                "sleep 1; " +
                "if [ -f $PIDFILE ]; then p=\$(cat $PIDFILE 2>/dev/null); " +
                "if [ -n \"\$p\" ] && kill -0 \$p 2>/dev/null && $IS_OURS; then echo __RUNNING__\$p; fi; fi; " +
                "echo __END__",
            30
        )
        Log.i(TAG, "start => ${start.output}")

        val pid = start.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("__RUNNING__") }
            ?.removePrefix("__RUNNING__")

        if (pid.isNullOrBlank()) {
            // 脚本已装好，最多下个开机由 Magisk 拉起；这里如实告警
            return false to "脚本已安装，但守护进程未启动成功（重启手机后会自动生效）"
        }

        return true to "已启用，守护进程 pid=$pid"
    }

    /**
     * 停掉正在运行的实例（不删除脚本）。
     *
     * 必须等进程真的消失再删 pidfile：否则紧接着 install() 拉起的新实例
     * 会读到还没清理的旧 pidfile，判定「已在运行」直接退出，
     * 表现为「开关切了但守护进程没起来」。
     */
    fun stop() {
        RootShell.run(
            // 先 kill -9 闲置子进程：它阻塞在 dd 上，TERM 会被延后到超时那一刻，
            // 留着它就会出现「已经停了/换了配置，还会莫名黑屏」。
            "if [ -f $IDLEPIDFILE ]; then c=\$(cat $IDLEPIDFILE 2>/dev/null); " +
                "if [ -n \"\$c\" ] && kill -0 \$c 2>/dev/null; then kill -9 \$c 2>/dev/null; fi; fi; " +
                "if [ -f $PIDFILE ]; then p=\$(cat $PIDFILE 2>/dev/null); " +
                "if [ -n \"\$p\" ] && kill -0 \$p 2>/dev/null; then " +
                "kill \$p 2>/dev/null; " +
                "i=0; while [ \$i -lt 20 ] && kill -0 \$p 2>/dev/null; do sleep 0.1; i=\$((i+1)); done; " +
                "kill -9 \$p 2>/dev/null; fi; fi; " +
                "rm -f $PIDFILE $IDLEPIDFILE; echo __STOPPED__",
            40
        )
    }

    /** 彻底关闭：停进程 + 删脚本。不改动任何开关状态。 */
    fun uninstall(): Pair<Boolean, String> {
        stop()
        RootShell.run("rm -f $INSTALLED_PATH", 20)
        val st = status()
        return if (!st.installed && !st.running) {
            true to "已关闭并移除守护脚本"
        } else {
            false to "关闭不完全，请手动检查 /data/adb/service.d/"
        }
    }

    /**
     * 按当前开关状态同步守护进程。
     *
     * 两个功能（插电退出、闲置息屏）共用同一个脚本，所以不能各自独立
     * 安装/卸载——只要还有任意一个开着，脚本就得留着；全关才移除。
     * 每次切换都会重写配置并重启进程，保证改完立即生效。
     */
    fun sync(ctx: Context): Pair<Boolean, String> =
        if (Prefs.anyWatchEnabled(ctx)) install(ctx) else uninstall()

    /** 读取最近若干行运行日志，供设置页排障。 */
    fun recentLog(lines: Int = 6): String {
        val r = RootShell.run(
            "if [ -f $LOGFILE ]; then tail -n $lines $LOGFILE; else echo '(暂无日志)'; fi",
            20
        )
        return r.output.trim().ifEmpty { "(暂无日志)" }
    }

    /**
     * App 启动时自愈。三种情况都会自动处理：
     *   1. 脚本不存在（全新安装 / 被删）→ 安装
     *   2. 脚本版本落后（升级 APK 后）→ 覆盖升级并重启
     *   3. 脚本在但进程没跑（刚开机 / 被 kill）→ 重新拉起
     */
    fun ensureInstalled(ctx: Context) {
        if (!Prefs.anyWatchEnabled(ctx)) {
            Log.i(TAG, "all watch features disabled by user, skip")
            return
        }
        val st = status()
        when {
            !st.installed -> {
                Log.w(TAG, "script missing, installing")
                install(ctx)
            }
            st.outdated -> {
                Log.w(TAG, "script outdated (v${st.version} -> v$SCRIPT_VERSION), upgrading")
                install(ctx)
            }
            !st.running -> {
                Log.w(TAG, "daemon not running, restarting")
                // 走 install() 而不是直接 nohup，顺手刷新配置
                install(ctx)
            }
            else -> {
                // 进程健康，但配置可能与开关对不上（手工改过 cfg、
                // 或者上一个版本留下的旧值）。对不上就重装一次纠正过来。
                val actual = RootShell.run("cat $CFG 2>/dev/null", 15).output.trim()
                if (actual != configText(ctx)) {
                    Log.w(TAG, "config drift, reapplying: [$actual] != [${configText(ctx)}]")
                    install(ctx)
                } else {
                    Log.i(TAG, "daemon healthy, pid=${st.pid} ver=${st.version}")
                }
            }
        }
    }
}
