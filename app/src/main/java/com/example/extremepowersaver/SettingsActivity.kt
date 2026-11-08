package com.example.extremepowersaver

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * 命令设置页：只在用户主动进入时使用（长按图标 → 设置）。
 */
class SettingsActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var editCommand: EditText
    private lateinit var tvResult: TextView
    private lateinit var cbToast: CheckBox
    private lateinit var cbAutoExit: CheckBox
    private lateinit var tvWatchStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        editCommand = findViewById(R.id.editCommand)
        tvResult = findViewById(R.id.tvResult)
        cbToast = findViewById(R.id.cbToast)
        cbAutoExit = findViewById(R.id.cbAutoExit)
        tvWatchStatus = findViewById(R.id.tvWatchStatus)

        editCommand.setText(Prefs.command(this))
        cbToast.isChecked = Prefs.showToast(this)

        // 先设初始状态再挂监听，避免恢复状态时误触发安装
        cbAutoExit.isChecked = Prefs.autoExitOnCharge(this)
        cbAutoExit.setOnCheckedChangeListener { _, checked -> onAutoExitToggled(checked) }
        refreshWatchStatus()

        findViewById<Button>(R.id.btnPresetEnter).setOnClickListener {
            editCommand.setText(Prefs.DEFAULT_ENTER)
        }
        findViewById<Button>(R.id.btnPresetExit).setOnClickListener {
            editCommand.setText(Prefs.DEFAULT_EXIT)
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            save()
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            test()
        }
    }

    /** 用于抑制「程序回滚勾选状态」时再次触发监听 */
    private var updatingUi = false

    private fun onAutoExitToggled(checked: Boolean) {
        if (updatingUi) return
        cbAutoExit.isEnabled = false
        tvWatchStatus.text = if (checked) "正在安装 root 守护进程…" else "正在关闭…"

        executor.execute {
            val (ok, msg) = if (checked) ChargeWatch.enable(this) else ChargeWatch.disable(this)
            main.post {
                cbAutoExit.isEnabled = true
                if (!ok) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

                // 勾选状态以实际生效结果为准
                val real = Prefs.autoExitOnCharge(this)
                if (cbAutoExit.isChecked != real) {
                    updatingUi = true
                    cbAutoExit.isChecked = real
                    updatingUi = false
                }
                refreshWatchStatus()
            }
        }
    }

    private fun refreshWatchStatus() {
        tvWatchStatus.text = "状态：查询中…"
        executor.execute {
            val st = ChargeWatch.status()
            val tail = if (st.running) ChargeWatch.recentLog(3) else null
            main.post {
                tvWatchStatus.text = buildString {
                    append("状态：")
                    append(
                        when {
                            st.running ->
                                "✅ 守护进程运行中（pid ${st.pid}，脚本 v${st.version ?: "?"}）"
                            st.installed && st.outdated ->
                                "⚠️ 脚本 v${st.version ?: "?"} → v${ChargeWatch.SCRIPT_VERSION}，重新打开 App 即自动升级"
                            st.installed ->
                                "⚠️ 脚本已安装但进程未运行（重新打开 App 会自动拉起）"
                            else ->
                                "未安装（打开 App 时会自动安装；需先在 Magisk 中授权）"
                        }
                    )
                    if (tail != null) append("\n最近日志：\n").append(tail)
                }
            }
        }
    }

    private fun save() {
        val cmd = editCommand.text.toString().trim()
        if (cmd.isEmpty()) {
            Toast.makeText(this, "命令不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setCommand(this, cmd)
        Prefs.setShowToast(this, cbToast.isChecked)
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }

    private fun test() {
        val cmd = editCommand.text.toString().trim()
        if (cmd.isEmpty()) return
        tvResult.visibility = View.VISIBLE
        tvResult.text = "执行中…"
        executor.execute {
            val root = RootShell.isRootGranted()
            if (!root) {
                main.post { tvResult.text = "❌ 未获得 root 权限，请在 Magisk 中授权" }
                return@execute
            }
            val r = RootShell.run(cmd)
            main.post {
                tvResult.text = buildString {
                    append("exit = ${r.exitCode}\n")
                    append("成功 = ${r.ok}\n\n")
                    append(r.output.take(800))
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
