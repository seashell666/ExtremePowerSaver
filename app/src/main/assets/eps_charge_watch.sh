#!/system/bin/sh
# ============================================================
#  ExtremePowerSaver · 守护进程
#
#  功能一 · 插电自动退出超级省电
#  功能二 · 闲置息屏：连续 N 秒没有任何触屏 → 回桌面 + 息屏
#           （超级省电模式下不生效，由本脚本主动跳过）
#
#  由 App 安装到 /data/adb/service.d/ ，开机由 Magisk 以 root 启动
#  也可被 App 立即手动拉起
#
#  关键设计：
#  1) 启动后立刻 setsid 自我脱离到新 session，然后父进程退出。
#     Magisk 的 boot stage 会 wait 脚本结束，若一直阻塞会被连带回收，
#     所以必须「脱壳」成独立会话进程。
#  2) 稳态零额外开销：
#     - 充电检测只看 /sys/class/power_supply/*/online（内核 sysfs）
#     - 用 shell 内建 read 读数，不 fork 任何进程
#     - 只有「刚插上电」或「充电中每 10 秒」才调用 settings / content
#     - 不持有 wakelock，设备睡眠时循环随 CPU 一起挂起，不产生唤醒
#  3) 触屏检测用「阻塞读」而不是轮询：
#     - dd 阻塞在 /dev/input/eventN 上，有事件立刻返回，无事件一直睡在内核里
#     - 外层套 timeout N，N 秒内一次事件都没有才判定为空闲
#     - 全程零轮询、零 CPU 占用，比定时读 /proc/interrupts 靠谱得多
#       （后者依赖中断名，跨机型基本对不上）
# ============================================================

export PATH=/system/bin:/system/xbin:/sbin:/data/adb/magisk:$PATH

# 脚本版本：App 侧会比对，不一致就自动重装覆盖（否则老脚本永远不升级）
EPS_SCRIPT_VERSION=7

PIDFILE=/data/local/tmp/eps_charge_watch.pid
LOG=/data/local/tmp/eps_charge_watch.log
CFG=/data/local/tmp/eps_charge_watch.cfg
URI=content://com.miui.powercenter.powersaver

# ---- 默认值：两个功能都开（App 会写 cfg 覆盖）----
ENABLE_CHARGE=1
ENABLE_IDLE=1
IDLE_TIMEOUT=180

if [ -r "$CFG" ]; then
    v=""
    v=$(sed -n 's/^charge=//p' "$CFG" 2>/dev/null | head -n1)
    [ -n "$v" ] && ENABLE_CHARGE=$v
    v=$(sed -n 's/^idle=//p' "$CFG" 2>/dev/null | head -n1)
    [ -n "$v" ] && ENABLE_IDLE=$v
    v=$(sed -n 's/^idle_timeout=//p' "$CFG" 2>/dev/null | head -n1)
    if [ -n "$v" ] && [ "$v" -ge 30 ] 2>/dev/null; then
        IDLE_TIMEOUT=$v
    fi
fi

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

IDLE_PID=""

# 只监听真实终止信号，不要用 EXIT。
# 原因：命令替换 $(date ...) / $(settings get ...) 会 fork 子 shell，
# 子 shell 继承 EXIT trap，退出时也会打一条「daemon exiting」，实测造成
# 「守护进程已死」的假象，严重误导排障。
cleanup() {
    log "daemon terminating (pid $$)"
    [ -n "$IDLE_PID" ] && kill "$IDLE_PID" 2>/dev/null
    exit 0
}
trap cleanup TERM INT HUP

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

# 是否正处于超级省电模式。
#
# 这里踩过一个坑：不能只看设置键。
# EXTREME_POWER_SAVE_MODE_OPEN 在小米11上恒为 1（就是那个没人监听的废弃键），
# 拿它判断会把「普通使用中」误判成「省电模式」——闲置息屏会被整段跳过、
# 充电逻辑每 10 秒白调一次退出命令。
#
# 实测小米11 MIUI14：
#   普通模式 → POWER_SAVE_MODE_OPEN=0，Activity 栈里 superpower 出现 0 次
#   省电模式 → POWER_SAVE_MODE_OPEN=1，Activity 栈里 superpower 出现 19~28 次
# 所以先用轻量的设置键，不中再查 Activity 栈兜底（跨机型不依赖键名）。
in_super_power() {
    v=$(/system/bin/settings get system POWER_SAVE_MODE_OPEN 2>/dev/null)
    [ "$v" = "1" ] && return 0
    dumpsys activity activities 2>/dev/null | grep -q "com.miui.superpower" && return 0
    return 1
}

# 只在确实处于超级省电时才调用，避免无意义的状态错误日志。
# 插电瞬间还会无条件兜底调用一次 leave_super_power()，
# 保证键名对不上时功能依然生效。
# 实测：不在超级省电时调用退出命令是无害空操作（仅日志 state error）。
try_leave() {
    if in_super_power; then
        log "charging + super power active -> leaving"
        leave_super_power
        return 0
    fi
    return 1
}

# ---- 功能二：闲置息屏 ----

# 找出真实的触屏 event 节点。
# 必须排除 uinput-* 这类虚拟注入设备（input 命令、自动化脚本走的是它），
# 否则用 adb 注入事件都会重置计时，等于监控失效。
find_touch_dev() {
    awk '
        /^N: Name=/ {
            n = tolower($0)
            if (n ~ /uinput/) hit = 0
            else if (n ~ /touch|fts|goodix|synaptics|nt36|gt9|ilitek|himax|sec_/) hit = 1
            else hit = 0
        }
        /^H: Handlers=/ && hit {
            if (match($0, /event[0-9]+/)) {
                print "/dev/input/" substr($0, RSTART, RLENGTH)
                exit
            }
        }
    ' /proc/bus/input/devices 2>/dev/null
}

# 空闲到点后的动作：回桌面 + 息屏
go_home_and_sleep() {
    # 超级省电模式下这个功能不生效（用户明确要求）
    if in_super_power; then
        log "idle ${IDLE_TIMEOUT}s but super power active -> skip"
        return
    fi
    log "idle ${IDLE_TIMEOUT}s no touch -> home + sleep"
    /system/bin/input keyevent 3      # KEYCODE_HOME
    sleep 1
    /system/bin/input keyevent 223    # KEYCODE_SLEEP
}

idle_watch() {
    dev=$(find_touch_dev)
    if [ -z "$dev" ]; then
        log "idle: no touch device found, feature disabled"
        return
    fi
    if [ ! -r "$dev" ]; then
        log "idle: $dev not readable, feature disabled"
        return
    fi
    log "idle watcher started (dev=$dev, timeout=${IDLE_TIMEOUT}s)"

    while true; do
        # 阻塞读，无事件时会一直挂在这里，直到 timeout 把它杀掉（rc=124）。
        #
        # count 必须写 1：dd 会一直读到凑满 count 个 block 才返回，
        # 而一次轻触只产生 6~12 个事件，用 count=64 会读不满、
        # 继续阻塞到超时，等于把正常操作误判成空闲。
        # 只读 1 个事件（24 字节）才能做到「一碰就返回」。
        timeout "$IDLE_TIMEOUT" dd if="$dev" bs=24 count=1 of=/dev/null 2>/dev/null
        rc=$?

        # 子 shell 里的 $$ 仍是主进程 pid。主进程被强杀（kill -9 跳过 trap）时
        # 这里会变成孤儿，如果不自检，它会继续替已经不存在的守护进程执
        # 行息屏动作——实测切换脚本版本时真的遇到过两个实例抢着息屏。
        if ! kill -0 $$ 2>/dev/null; then
            log "idle: parent (pid $$) gone, exiting"
            return
        fi

        if [ "$rc" = "124" ]; then
            go_home_and_sleep
        else
            # 读到事件（rc=0）或设备异常（rc=1）。
            # 轻微节流：滑动时事件很密集，不节流会疯狂 fork。
            # 事件留在内核 buffer 里不会丢，下次读立刻就能拿到。
            sleep 0.15
        fi
    done
}

# ---- 功能一：充电自动退出 ----
charge_loop() {
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
            elif [ "$tick" -ge 15 ]; then
                # 充电中每 30 秒兜底复查（tick 每 2 秒 +1）。
                # 间隔从 10 秒放宽到 30 秒，是因为 in_super_power 在设置键
                # 不命中时还要查一次 Activity 栈（约 50ms），10 秒一次没必要。
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
}

# ---- 启动 ----
if [ "$ENABLE_CHARGE" != "1" ] && [ "$ENABLE_IDLE" != "1" ]; then
    log "both features disabled, exiting"
    rm -f "$PIDFILE"
    exit 0
fi

log "features: charge=$ENABLE_CHARGE idle=$ENABLE_IDLE timeout=${IDLE_TIMEOUT}s"

if [ "$ENABLE_IDLE" = "1" ]; then
    idle_watch &
    IDLE_PID=$!
fi

if [ "$ENABLE_CHARGE" = "1" ]; then
    charge_loop
else
    # 只开闲置息屏：主进程不空转，直接等子进程
    [ -n "$IDLE_PID" ] && wait "$IDLE_PID"
fi
