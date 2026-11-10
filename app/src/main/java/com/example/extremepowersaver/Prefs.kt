package com.example.extremepowersaver

import android.content.Context

/**
 * 命令与配置存储。
 */
object Prefs {
    private const val SP = "eps_prefs"
    private const val KEY_COMMAND = "command"
    private const val KEY_SHOW_TOAST = "show_toast"
    private const val KEY_AUTO_EXIT_ON_CHARGE = "auto_exit_on_charge"
    private const val KEY_IDLE_SCREEN_OFF = "idle_screen_off"

    /** 闲置多久判定为「没人在用」，单位秒。改这里就能调整时长。 */
    const val IDLE_TIMEOUT_SEC = 7

    /** 默认命令：进入超级省电（已在小米11 / MIUI14 / Android13 实测可用） */
    const val DEFAULT_ENTER =
        "/system/bin/content call --uri content://com.miui.powercenter.powersaver " +
            "--method changeSuperPowerMode " +
            "--extra POWER_SUPERSAVE_MODE_OPEN:b:true " +
            "--extra POWER_SUPERSAVE_MODE_FROMUSER:b:true"

    /** 退出超级省电 */
    const val DEFAULT_EXIT =
        "/system/bin/content call --uri content://com.miui.powercenter.powersaver " +
            "--method changeSuperPowerMode " +
            "--extra POWER_SUPERSAVE_MODE_OPEN:b:false " +
            "--extra POWER_SUPERSAVE_MODE_FROMUSER:b:true"

    fun command(ctx: Context): String =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getString(KEY_COMMAND, DEFAULT_ENTER)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_ENTER

    fun setCommand(ctx: Context, cmd: String) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putString(KEY_COMMAND, cmd).apply()
    }

    fun showToast(ctx: Context): Boolean =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_TOAST, true)

    fun setShowToast(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_TOAST, v).apply()
    }

    /**
     * 是否启用「插上充电就自动退出超级省电」。
     *
     * 默认 true：这个 App 的用途本身就是「进入省电 + 插电自动退出」，
     * 若默认关闭，全新安装的设备会出现「能进省电但插电不退出」的假故障
     * （守护脚本压根没装）。用户可在设置页显式关闭，关闭后不再自动安装。
     */
    fun autoExitOnCharge(ctx: Context): Boolean =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_EXIT_ON_CHARGE, true)

    fun setAutoExitOnCharge(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_EXIT_ON_CHARGE, v).apply()
    }

    /**
     * 是否启用「闲置息屏」：连续 [IDLE_TIMEOUT_SEC] 秒没有任何触屏，
     * 就退回桌面并息屏。超级省电模式下不生效。
     *
     * 默认 true —— 这个功能本质是把系统自动锁屏的时长管起来，
     * 开着不会比系统默认更打扰（系统默认通常 30 秒~2 分钟就锁了）。
     */
    fun idleScreenOff(ctx: Context): Boolean =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getBoolean(KEY_IDLE_SCREEN_OFF, true)

    fun setIdleScreenOff(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_IDLE_SCREEN_OFF, v).apply()
    }

    /** 是否有任意一个守护功能处于开启状态（决定脚本要不要装）。 */
    fun anyWatchEnabled(ctx: Context): Boolean =
        autoExitOnCharge(ctx) || idleScreenOff(ctx)
}
