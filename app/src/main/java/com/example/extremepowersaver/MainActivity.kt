package com.example.extremepowersaver

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * 打开即执行：无需任何点击，进入页面立刻用 root 执行配置好的命令。
 * 默认命令 = 进入 MIUI 超级省电模式。
 */
class MainActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 长按图标进入设置（shortcut 也会走这里）
        if (intent?.action == ACTION_OPEN_SETTINGS) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.status)
        detailView = findViewById(R.id.detail)
        progress = findViewById(R.id.progress)

        execute()
    }

    private fun execute() {
        executor.execute {
            Log.i("EPS", "execute() start, cmd=" + Prefs.command(this))
            // 1. 检查 root
            if (!RootShell.isRootGranted()) {
                Log.e("EPS", "root not granted")
                finishWith(false, "未获得 root 权限", "请在 Magisk 中允许本应用（首次运行会弹窗）")
                return@execute
            }

            // 2. 自愈：守护脚本丢了（全新安装 / 重装 / 被手动删除）就补装回来。
            //    默认即启用，所以全新安装的设备第一次打开就会把脚本落到 service.d，
            //    不会再出现「能进省电但插电不退出」的情况。
            ChargeWatch.ensureInstalled(this)

            val cmd = Prefs.command(this)

            // 3. 一致性守卫：已开启「充电自动退出」且正在充电时，进入超级省电没有意义
            //    （否则会在 10 秒内被守护进程踢回普通模式）
            if (Prefs.autoExitOnCharge(this) && cmd.contains("POWER_SUPERSAVE_MODE_OPEN:b:true") && isPluggedIn()) {
                Log.i("EPS", "charging detected, skip entering super power save")
                finishWith(true, "正在充电，已跳过进入", "已开启「充电自动退出超级省电」，插着电源时不进入。拔掉电源后再打开即可。")
                return@execute
            }

            val result = RootShell.run(cmd)
            Log.i("EPS", "result ok=${result.ok} exit=${result.exitCode} out=${result.output}")

            // 4. 判断结果
            // content call 成功会返回 "Result: null"，失败会带异常信息
            val output = result.output
            val looksFailed = output.contains("Error", true) ||
                output.contains("Exception", true) ||
                output.contains("Permission Denial", true) ||
                output.contains("SecurityException", true)

            val ok = result.ok && !looksFailed
            val detail = if (ok) {
                "命令已下发，系统正在切换"
            } else {
                "exit=${result.exitCode}  ${output.take(180)}"
            }
            finishWith(ok, if (ok) "已进入超级省电模式" else "执行失败", detail)
        }
    }

    /**
     * 是否插着电源（有线/无线/AC 都算）。
     *
     * 注意：不能用 BatteryManager.isCharging() —— 实测在 MIUI 14 上，
     * 插着 USB 时 dumpsys 显示 "USB powered: true / status: 2"，
     * 但 isCharging() 返回 false（充电已满或限流时会这样）。
     * EXTRA_PLUGGED 与内核 sysfs 的 online 同源，才是「是否插电」的真值，
     * 也和守护脚本的判断口径一致。
     */
    private fun isPluggedIn(): Boolean = try {
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = try {
            (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging
        } catch (t: Throwable) {
            false
        }
        Log.i("EPS", "plugged=$plugged isCharging=$charging")
        plugged != 0 || charging
    } catch (t: Throwable) {
        Log.w("EPS", "isPluggedIn failed", t)
        false
    }

    private fun finishWith(ok: Boolean, statusText: String, detail: String) {
        main.post {
            statusView.text = statusText
            detailView.text = detail
            progress.visibility = ProgressBar.GONE

            if (Prefs.showToast(this)) {
                Toast.makeText(this, if (ok) "✅ $statusText" else "❌ $statusText", Toast.LENGTH_LONG).show()
            }

            // 成功时留一点时间让系统播放切换动画；失败时留久一点方便看错误
            main.postDelayed({ finish() }, if (ok) 600L else 1800L)
        }
    }

    override fun onDestroy() {
        // 不要 shutdownNow()：进入超级省电时本 Activity 会被系统切走，
        // 强行中断会让已下发的命令被判为失败。
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN_SETTINGS = "com.example.extremepowersaver.OPEN_SETTINGS"
    }
}
