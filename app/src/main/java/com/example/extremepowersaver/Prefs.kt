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
    private const val KEY_RESET_HOME = "reset_home_on_screen_off"
    private const val KEY_RESET_SILENT = "reset_home_silent"

    /** 闲置多久判定为「没人在用」，单位秒。447 = 7 分 27 秒。改这里就能调整时长。 */
    const val IDLE_TIMEOUT_SEC = 447

    /**
     * 息屏后多久把桌面翻回主页面，单位秒。
     *
     * 7 秒：息屏后 7 秒内又亮屏，说明只是看一眼，保持原页面不动；
     * 超过 7 秒，就在黑屏状态下把桌面重建回第一页，下次亮屏直接看到主屏。
     */
    const val RESET_HOME_DELAY_SEC = 7

    /**
     * 充电中「电量大到没必要再省电」的门槛，单位百分比。
     *
     * 充电状态 + 仍处于超级省电 + 电量 **大于** 这个值 → 自动退出超级省电并立刻息屏。
     * 写 77 就是 78% 起才会动手（严格大于）。改这里就能调整门槛。
     */
    const val EXIT_BATTERY_PCT = 77

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

    /**
     * 是否启用「息屏归位主屏」：在桌面但不在第一页时息屏，
     * [RESET_HOME_DELAY_SEC] 秒后桌面自己翻回主页面（屏幕全程不亮）。
     *
     * 默认 true —— 纯属「下次亮屏更顺手」，没有任何打扰：
     * 全程黑屏完成，7 秒内亮屏还会自动取消。
     */
    fun resetHomeOnScreenOff(ctx: Context): Boolean =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESET_HOME, true)

    fun setResetHomeOnScreenOff(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RESET_HOME, v).apply()
    }

    /**
     * 归位方式：是否跳过亮屏时那段空白等待。
     *
     * true（默认）= 静默翻页：不重建桌面，在息屏期间短暂点亮显示并把背光压到 0，
     * 借这个焦点按 HOME 把桌面滑回第一页。桌面进程始终活着，
     * 所以亮屏第一帧就是完整主屏——没有那 0.6 秒「只有壁纸、图标还没出来」。
     *
     * false = 重建桌面：CLEAR_TASK 让 Activity 重建，屏幕全程不亮，
     * 但亮屏后桌面要重新加载一次工作区，会看到约 0.6 秒的空白页。
     */
    fun resetSilent(ctx: Context): Boolean =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESET_SILENT, true)

    fun setResetSilent(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RESET_SILENT, v).apply()
    }

    /** 是否有任意一个守护功能处于开启状态（决定脚本要不要装）。 */
    fun anyWatchEnabled(ctx: Context): Boolean =
        autoExitOnCharge(ctx) || idleScreenOff(ctx) || resetHomeOnScreenOff(ctx)
}
