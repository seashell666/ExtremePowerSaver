#!/system/bin/sh
# ============================================================
#  ExtremePowerSaver · 充电自动退出超级省电
#  由 App 安装到 /data/adb/service.d/ ，开机由 Magisk 以 root 启动
#  也可被 App 立即手动拉起
#
#  两条关键设计：
#  1) 启动后立刻 setsid 自我脱离到新 session，然后父进程退出。
#     Magisk 的 boot stage 会 wait 脚本结束，若一直阻塞会被连带回收，
#     所以必须「脱壳」成独立会话进程。
#  2) 稳态零额外开销：
#     - 检测充电只看 /sys/class/power_supply/*/online（内核 sysfs）
#     - 用 shell 内建 read 读数，不 fork 任何进程
#     - 只有「刚插上电」或「充电中每 10 秒」才调用 settings / content
#     - 不持有 wakelock，设备睡眠时循环随 CPU 一起挂起，不产生唤醒
# ============================================================

export PATH=/system/bin:/system/xbin:/sbin:/data/adb/magisk:$PATH

# 脚本版本：App 侧会比对，不一致就自动重装覆盖（否则老脚本永远不升级）
EPS_SCRIPT_VERSION=3

PIDFILE=/data/local/tmp/eps_charge_watch.pid
LOG=/data/local/tmp/eps_charge_watch.log
URI=content://com.miui.powercenter.powersaver

log() {
    echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"
}

# ---- 脱壳：由 Magisk / App 直接执行时，转入独立 session 后立即返回 ----
if [ "${EPS_DETACHED:-0}" != "1" ]; then
    EPS_DETACHED=1 setsid sh "$0" "$@" </dev/null >/dev/null 2>&1 &
    exit 0
fi

# ---- 单实例保护 ----
# 必须校验 pid 确实是本脚本，否则重启后 pid 被复用会导致
# 误判「已在运行」而不启动，甚至被 App 误杀无关进程
if [ -f "$PIDFILE" ]; then
    old=""
    read old < "$PIDFILE" 2>/dev/null
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
        if tr '\0' ' ' < "/proc/$old/cmdline" 2>/dev/null | grep -q eps_charge_watch; then
            exit 0
        fi
    fi
fi
echo $$ > "$PIDFILE"

# 只监听真实终止信号，不要用 EXIT。
# 原因：命令替换 $(date ...) / $(settings get ...) 会 fork 子 shell，
# 子 shell 继承 EXIT trap，退出时也会打一条「daemon exiting」，实测造成
# 「守护进程已死」的假象，严重误导排障。
trap 'log "daemon terminating (pid $$)"; exit 0' TERM INT HUP

log "watcher started (pid $$, cgroup=$(cat /proc/self/cgroup 2>/dev/null | tr "\n" " "))"

# 控制日志大小，避免长期写满 /data
trim_log() {
    if [ -f "$LOG" ]; then
        sz=$(wc -c < "$LOG" 2>/dev/null)
        if [ -n "$sz" ] && [ "$sz" -gt 65536 ]; then
            tail -n 200 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
        fi
    fi
}

# 是否正在充电：任何一个电源 online=1 即视为插电
is_charging() {
    for f in \
        /sys/class/power_supply/usb/online \
        /sys/class/power_supply/ac/online \
        /sys/class/power_supply/wireless/online \
        /sys/class/power_supply/dc/online \
        /sys/class/power_supply/pc_port/online
    do
        [ -r "$f" ] || continue
        v=""
        read v < "$f" 2>/dev/null
        [ "$v" = "1" ] && return 0
    done
    # 兜底：battery/status 为 Charging 也算（部分机型 ac 节点名不同）
    if [ -r /sys/class/power_supply/battery/status ]; then
        st=""
        read st < /sys/class/power_supply/battery/status 2>/dev/null
        [ "$st" = "Charging" ] && return 0
    fi
    return 1
}

leave_super_power() {
    /system/bin/content call \
        --uri "$URI" \
        --method changeSuperPowerMode \
        --extra POWER_SUPERSAVE_MODE_OPEN:b:false \
        --extra POWER_SUPERSAVE_MODE_FROMUSER:b:true \
        >/dev/null 2>&1
}

# 只在确实处于超级省电时才调用，避免无意义的状态错误日志。
#
# 注意：这里的键名是 MIUI 的实现细节，不同机型/版本可能不一致
# （小米11 MIUI14 上是 POWER_SAVE_MODE_OPEN）。所以这里同时查多个候选键，
# 并且插电瞬间还会无条件兜底调用一次 leave_super_power()，
# 保证键名对不上时功能依然生效。
# 实测：不在超级省电时调用退出命令是无害空操作（仅日志 state error）。
try_leave() {
    for k in POWER_SAVE_MODE_OPEN EXTREME_POWER_SAVE_MODE_OPEN POWER_SUPERSAVE_MODE_OPEN; do
        sp=$(/system/bin/settings get system "$k" 2>/dev/null)
        if [ "$sp" = "1" ]; then
            log "charging + super power active ($k=1) -> leaving"
            leave_super_power
            return 0
        fi
    done
    return 1
}

prev=""
tick=0
while true; do
    if is_charging; then
        online=1
    else
        online=0
    fi

    if [ "$online" = "1" ]; then
        if [ "$prev" != "1" ]; then
            # 插电瞬间：不查任何设置键，直接尝试退出一次。
            # 这是跨机型/跨 MIUI 版本最可靠的路径——不依赖 POWER_SAVE_MODE_OPEN
            # 这类实现细节；不在省电状态时调用是无害空操作。
            log "power connected -> try leave (unconditional)"
            leave_super_power
            prev=1
            tick=0
            # 等 MIUI 切换动画结束，避免紧接着的复查误判
            sleep 12
            trim_log
            continue
        elif [ "$tick" -ge 5 ]; then
            # 充电中每 10 秒兜底复查：此时已确认在充电，
            # 只在读键确认处于省电时才再调，避免无谓 fork
            tick=0
            if try_leave; then
                sleep 12
                trim_log
                continue
            fi
        fi
        tick=$((tick + 1))
    else
        tick=0
        prev=0
    fi

    sleep 2
done
