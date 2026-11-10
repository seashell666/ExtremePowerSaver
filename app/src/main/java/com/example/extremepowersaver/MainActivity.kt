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

            // 3. 一致性守卫：已开启充电自动化、正在充电、且电量已超过退出门槛时，
            //    进入超级省电没有意义——进去也会被守护进程退出来。
            //    电量还没到门槛则照常进入：v1.5 起守护进程会留着它，
            //    等电量涨过门槛再自动退出并息屏。
            var chargeNote = ""
            if (Prefs.autoExitOnCharge(this) && cmd.contains("POWER_SUPERSAVE_MODE_OPEN:b:true") && isPluggedIn()) {
                val pct = batteryPercent()
                if (pct == null) {
                    Log.i("EPS", "charging detected, battery level unknown, skip entering")
                    finishWith(true, "正在充电，已跳过进入", "已开启「充电自动退出超级省电」，插着电源时不进入。拔掉电源后再打开即可。")
                    return@execute
                }
                if (pct > Prefs.EXIT_BATTERY_PCT) {
                    Log.i("EPS", "charging + ${pct}% > ${Prefs.EXIT_BATTERY_PCT}%, skip entering")
                    finishWith(
                        true,
                        "电量 ${pct}%，无需进入",
                        "充电中电量已超过 ${Prefs.EXIT_BATTERY_PCT}%（当前 ${pct}%），进入超级省电意义不大。" +
                            "守护进程在这种状态下会自动退出超级省电并息屏。"
                    )
                    return@execute
                }
                Log.i("EPS", "charging but ${pct}% <= ${Prefs.EXIT_BATTERY_PCT}%, allow entering")
                chargeNote = "充电中（电量 ${pct}%），涨到 ${Prefs.EXIT_BATTERY_PCT + 1}% 后会自动退出并息屏"
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
                if (chargeNote.isEmpty()) "命令已下发，系统正在切换" else "命令已下发，系统正在切换\n$chargeNote"
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
    /**
     * 当前电量百分比（0~100），读不到返回 null。
     *
     * 和守护脚本读的是同一个真值来源（内核 capacity），只是 App 侧走
     * sticky 广播，不需要 root、也不会多 fork 进程。
     */
    private fun batteryPercent(): Int? = try {
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) null else level * 100 / scale
    } catch (t: Throwable) {
        Log.w("EPS", "batteryPercent failed", t)
        null
    }

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
