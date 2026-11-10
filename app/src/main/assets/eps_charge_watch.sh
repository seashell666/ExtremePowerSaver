#!/system/bin/sh
# ============================================================
#  ExtremePowerSaver · 守护进程
#
#  功能一 · 插电自动退出超级省电
#           + 充电中处于超级省电且电量 > 77% → 退出超级省电并立刻息屏
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
EPS_SCRIPT_VERSION=14

PIDFILE=/data/local/tmp/eps_charge_watch.pid
IDLEPIDFILE=/data/local/tmp/eps_charge_watch.idle.pid
LOG=/data/local/tmp/eps_charge_watch.log
CFG=/data/local/tmp/eps_charge_watch.cfg
URI=content://com.miui.powercenter.powersaver

# ---- 默认值：两个功能都开（App 会写 cfg 覆盖）----
ENABLE_CHARGE=1
ENABLE_IDLE=1
IDLE_TIMEOUT=447

# 充电中「电量大到没必要再省电」的门槛，单位百分比。
# 大于这个值（严格大于）才自动退出超级省电，77 → 78% 起才会动手。
EXIT_BATTERY_PCT=77

if [ -r "$CFG" ]; then
    v=""
    v=$(sed -n 's/^charge=//p' "$CFG" 2>/dev/null | head -n1)
    [ -n "$v" ] && ENABLE_CHARGE=$v
    v=$(sed -n 's/^idle=//p' "$CFG" 2>/dev/null | head -n1)
    [ -n "$v" ] && ENABLE_IDLE=$v
    v=$(sed -n 's/^idle_timeout=//p' "$CFG" 2>/dev/null | head -n1)
    # 下限 5 秒：防止配置被误写成 0/1 导致瞬间反复息屏
    if [ -n "$v" ] && [ "$v" -ge 5 ] 2>/dev/null; then
        IDLE_TIMEOUT=$v
    fi
    v=$(sed -n 's/^exit_battery_pct=//p' "$CFG" 2>/dev/null | head -n1)
    # 卡在 1~99：写成 0 会「只要充电就退」，写成 100 等于功能失效
    if [ -n "$v" ] && [ "$v" -ge 1 ] 2>/dev/null && [ "$v" -le 99 ] 2>/dev/null; then
        EXIT_BATTERY_PCT=$v
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

# 清掉上一条命留下的 idle 子进程。
#
# 为什么必须单独记它的 pid：idle 子进程常年阻塞在 `timeout 447 dd` 里，
# 而 mksh 的 trap 要等当前前台命令结束才执行——给主进程发 TERM 时，
# 主进程 2 秒内就退了，子进程却会一直卡在这个 dd 上，最长要等满 447 秒
# 才看到 TERM。这期间它仍然有能力执行「回桌面+息屏」，
# 表现就是「已经关掉了/换过配置，还会莫名其妙黑屏」。
# 所以：子进程 pid 单独落盘，清理时一律 kill -9（SIGKILL 无法被延迟）。
kill_stale_idle() {
    [ -f "$IDLEPIDFILE" ] || return 0
    old=""
    read old < "$IDLEPIDFILE" 2>/dev/null
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
        if tr '\0' ' ' < "/proc/$old/cmdline" 2>/dev/null | grep -q eps_charge_watch; then
            kill -9 "$old" 2>/dev/null
            log "killed stale idle watcher (pid $old)"
        fi
    fi
    rm -f "$IDLEPIDFILE"
}
kill_stale_idle

# 只监听真实终止信号，不要用 EXIT。
# 原因：命令替换 $(date ...) / $(settings get ...) 会 fork 子 shell，
# 子 shell 继承 EXIT trap，退出时也会打一条「daemon exiting」，实测造成
# 「守护进程已死」的假象，严重误导排障。
cleanup() {
    log "daemon terminating (pid $$)"
    [ -n "$IDLE_PID" ] && kill -9 "$IDLE_PID" 2>/dev/null
    rm -f "$IDLEPIDFILE"
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

# 当前电量（0~100）。结果写进 BATT_PCT；返回 1 = 一个节点都读不到。
#
# 用 shell 内建 read 直读 sysfs，不 fork 任何进程，也不去碰 dumpsys
# （dumpsys battery 要拉起一个 app_process，约 60ms，30 秒一次也没必要）。
battery_pct() {
    BATT_PCT=""
    for f in \
        /sys/class/power_supply/battery/capacity \
        /sys/class/power_supply/bms/capacity \
        /sys/class/power_supply/maxfg/capacity \
        /sys/class/power_supply/mtk-battery/capacity
    do
        [ -r "$f" ] || continue
        bv=""
        read -r bv < "$f" 2>/dev/null
        # 只接受纯数字：部分机型这里会出现 "N/A" 或空行
        case "$bv" in
            ''|*[!0-9]*) continue ;;
        esac
        if [ "$bv" -ge 0 ] && [ "$bv" -le 100 ]; then
            BATT_PCT=$bv
            return 0
        fi
    done
    return 1
}

# 充电中、仍处于超级省电、且电量已经超过门槛 → 退出超级省电 + 立刻息屏。
#
# 这是「插电即退出」之外的补充规则：插电那一刻已经退过一次，但如果之后
# 又进了超级省电（从系统设置手动进、或者插着电时用 App 进），
# 只要电量涨过 77%，就没必要继续省电了——直接退出，并把还亮着的屏幕关掉。
leave_when_battery_high() {
    log "charging + super power + battery ${BATT_PCT}% > ${EXIT_BATTERY_PCT}% -> leave + screen off"
    leave_super_power
    # 等 MIUI 把超级省电的桌面切回普通桌面：这期间发 SLEEP 会被转场吞掉，
    # 表现成「发了息屏键但屏幕还亮着」。
    sleep 1.5
    screen_off_now
    # 给系统一点时间落地，避免紧接着的复查读到退出前的旧状态
    sleep 8
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

# 屏幕是否真的亮着。
# 只认 Awake：Dozing 是 AOD/息屏显示，屏幕已经黑了，不需要再动。
screen_is_on() {
    /system/bin/dumpsys power 2>/dev/null | grep -q 'mWakefulness=Awake'
}

# 只息屏，不动桌面。给「充电中电量够了就退出超级省电」用：
# 那种场景下不需要回桌面（本来就在普通模式/超级省电的桌面上），
# 只需要把屏幕关掉。
# 返回 0 = 屏幕已经黑掉（本来就黑也算）。
screen_off_now() {
    if ! screen_is_on; then
        log "screen already off"
        return 0
    fi
    /system/bin/input keyevent 223         # KEYCODE_SLEEP
    c=0
    while [ "$c" -lt 8 ]; do
        screen_is_on || { log "screen off done"; return 0; }
        c=$((c + 1))
        sleep 0.06
    done
    # SLEEP 键偶尔被系统吞掉，补发一次再判
    /system/bin/input keyevent 223
    sleep 0.12
    if screen_is_on; then
        log "screen off failed, give up this round"
        return 1
    fi
    log "screen off done"
    return 0
}

# 桌面包名：从系统里问出来，不写死（写死遇到换桌面就整段失效）
home_pkg() {
    p=$(/system/bin/cmd package resolve-activity --brief \
        -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -n1)
    echo "${p%%/*}"
}

# 非阻塞看一眼触屏上有没有新事件（我们自己发的按键不会产生触屏事件，
# 所以读到事件 = 用户在碰屏幕）。返回 0 表示「用户正在操作」。
#
# 为什么要这个：整套动作要跑 0.6~1.6 秒，这期间 dd 没在读触屏，
# 用户这时点亮屏幕、点开某个 App，事件全堆在内核 buffer 里没人处理，
# 动作照样一路执行到底 —— 表现就是「刚点开就啪一下黑屏」。
# 每一步之间插一次检查，一旦有人碰屏幕就整段放弃。
touch_seen() {
    timeout 0.01 dd if="$IDLE_DEV" bs=24 count=1 of=/dev/null 2>/dev/null
    [ $? -eq 0 ]
}

# 空闲到点后的动作：回桌面 → 回到默认主页面 → 息屏。
#
# 三个关键点（都是踩出来的）：
# 1) 不能一次把 HOME 和 SLEEP 一起发。键是发了，但桌面转场还没画完屏幕就黑了，
#    亮屏时看到的就是「卡一下」，而且落点还停在打开 App 的那一页。
# 2) MIUI 桌面第一次 HOME 只回「打开 App 的那一页」，要再按一次 HOME 才跳默认页。
# 3) 第二次 HOME 如果落在转场动画里会被直接吞掉（连发 keyevent 3 3 实测无效），
#    所以必须先确认前台已经切到桌面包名，再多留一点时间等窗口动画收尾。
#
# 实测耗时：约 0.7 秒（logcat: HOME 05.077 → 再 HOME 05.295 → goToSleep 05.659），远低于 2 秒要求。
# 返回 0 = 屏幕已经黑掉；返回 1 = 中途放弃或没黑成。
go_home_and_sleep() {
    # 超级省电模式下这个功能不生效（用户明确要求）
    if in_super_power; then
        log "idle ${IDLE_TIMEOUT}s but super power active -> skip"
        return 1
    fi
    if ! screen_is_on; then
        log "idle ${IDLE_TIMEOUT}s but screen already off -> skip"
        return 1
    fi

    # 桌面包名在 idle 循环启动时只解析一次，但那次可能是在超级省电模式下解析的：
    # 那时 HOME 会被解析成 com.miui.securityadd 的 SuperPowerLauncherActivity。
    # 退出超级省电后这个值就是错的，下面「等前台变成桌面」永远等不到，
    # 只能靠超时兜底，白白多花 0.6 秒。所以动作发生时重新解析一次（约 60ms，一次性开销）。
    HOME_PKG=$(home_pkg)
    [ -z "$HOME_PKG" ] && HOME_PKG=com.miui.home

    log "idle ${IDLE_TIMEOUT}s no touch -> home + sleep"

    # ① 回桌面
    /system/bin/input keyevent 3          # KEYCODE_HOME
    touch_seen && { log "abort: touch during action"; return 1; }

    # ② 等桌面真的接手（最多约 0.7 秒）
    n=0
    while [ "$n" -lt 12 ]; do
        if /system/bin/dumpsys activity activities 2>/dev/null \
            | grep -m1 'topResumedActivity' | grep -q "$HOME_PKG"; then
            break
        fi
        touch_seen && { log "abort: touch during action"; return 1; }
        n=$((n + 1))
        sleep 0.05
    done
    # 前台到位后再等窗口动画收尾，否则第二次 HOME 会被吞
    [ "$n" -ge 12 ] && log "home timeout: launcher not resumed in 0.7s, press home anyway"
    sleep 0.12
    touch_seen && { log "abort: touch during action"; return 1; }

    # ③ 再按一次 HOME → 跳回默认主页面
    /system/bin/input keyevent 3
    sleep 0.28
    touch_seen && { log "abort: touch during action"; return 1; }

    # ④ 息屏，并确认真的黑掉了（SLEEP 键偶尔会被系统吞掉，
    #    不确认的话下一轮超时又会再发一次，表现成反复黑屏）
    if screen_is_on; then
        /system/bin/input keyevent 223     # KEYCODE_SLEEP
        c=0
        while [ "$c" -lt 8 ]; do
            screen_is_on || { log "home + sleep done"; return 0; }
            c=$((c + 1))
            sleep 0.06
        done
        log "sleep key swallowed -> retry"
        /system/bin/input keyevent 223
        sleep 0.12
        if screen_is_on; then
            log "sleep failed, give up this round"
            return 1
        fi
    fi
    log "home + sleep done"
    return 0
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
    IDLE_DEV=$dev
    HOME_PKG=$(home_pkg)
    [ -z "$HOME_PKG" ] && HOME_PKG=com.miui.home
    log "idle watcher started (dev=$dev, home=$HOME_PKG, timeout=${IDLE_TIMEOUT}s)"

    # 上一轮结束时屏幕是不是黑的。
    # 用来识别「刚刚才亮屏」：那种情况下不能再补一刀息屏，
    # 否则表现成「按电源键亮屏，还没碰就又黑了」。
    screen_was_off=0

    while true; do
        # 阻塞读，无事件时会一直挂在这里，直到 timeout 把它杀掉（rc=124）。
        #
        # count 必须写 1：dd 会一直读到凑满 count 个 block 才返回，
        # 而一次轻触只产生 6~12 个事件，用 count=64 会读不满、
        # 继续阻塞到超时，等于把正常操作误判成空闲。
        # 只读 1 个事件（24 字节）才能做到「一碰就返回」。
        #
        # 计时起点天然紧跟「最后一次触摸」：每读到一个事件就重新起 timeout，
        # 所以不会出现「还没到点就黑屏」（除非动作期间用户才碰屏幕，
        # 那种情况由 touch_seen 兜住）。
        timeout "$IDLE_TIMEOUT" dd if="$dev" bs=24 count=1 of=/dev/null 2>/dev/null
        rc=$?

        # 子 shell 里的 $$ 仍是主进程 pid。主进程被强杀（kill -9 跳过 trap）时
        # 这里会变成孤儿，如果不自检，它会继续替已经不存在的守护进程执
        # 行息屏动作——实测切换脚本版本时真的遇到过两个实例抢着息屏。
        if ! kill -0 $$ 2>/dev/null; then
            log "idle: parent (pid $$) gone, exiting"
            rm -f "$IDLEPIDFILE"
            return
        fi

        if [ "$rc" = "124" ]; then
            if ! screen_is_on; then
                # 屏幕本来就黑着：什么都不做。
                # 少了这一步，息屏之后每一轮超时都会再发一次 HOME+SLEEP：
                # 白烧两次 app_process，还会在你刚按下电源键的那一瞬间把你黑掉。
                screen_was_off=1
                log "idle ${IDLE_TIMEOUT}s but screen already off -> skip"
                continue
            fi
            if [ "$screen_was_off" = "1" ]; then
                # 刚亮屏（上一轮还是黑的），这一轮只用来重置计时
                screen_was_off=0
                log "idle ${IDLE_TIMEOUT}s but screen just woke -> restart timer"
                continue
            fi
            # 动作成功（屏幕已确认黑掉）才算「黑屏轮」；
            # 中途放弃（用户碰了屏幕）就当什么都没发生。
            if go_home_and_sleep; then
                screen_was_off=1
            fi
        else
            # 读到事件（rc=0）或设备异常（rc=1）。
            # 轻微节流：滑动时事件很密集，不节流会疯狂 fork。
            # 事件留在内核 buffer 里不会丢，下次读立刻就能拿到。
            screen_was_off=0
            sleep 0.15
        fi
    done
}

# ---- 功能一：充电自动退出 ----
charge_loop() {
    prev=""
    tick=0
    # 连续多少次复查确认「还老老实实待在超级省电里」。
    # 用来避免「刚手动进了超级省电就被这条规则踢出来 + 立刻黑屏」。
    sp_seen=0

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
                # 注意这里不看电量：插电即退是原来的行为，保持不变。
                log "power connected -> try leave (unconditional)"
                leave_super_power
                prev=1
                tick=0
                sp_seen=0
                # 等 MIUI 切换动画结束，避免紧接着的复查误判
                sleep 12
                trim_log
                continue
            elif [ "$tick" -ge 15 ]; then
                # 充电中每 30 秒复查（tick 每 2 秒 +1）。
                # 先查是否在超级省电：设置键便宜，Activity 栈约 50ms，
                # 30 秒一次没必要更密。
                tick=0
                if in_super_power; then
                    sp_seen=$((sp_seen + 1))
                    if battery_pct && [ "$BATT_PCT" -gt "$EXIT_BATTERY_PCT" ]; then
                        # 至少连续两次复查都还在超级省电（≈60 秒）才动手。
                        # 少了这一步，插着电点开 App 刚进入超级省电，
                        # 下一秒就被踢出去并黑屏，像是坏了。
                        if [ "$sp_seen" -ge 2 ]; then
                            leave_when_battery_high
                            sp_seen=0
                            trim_log
                            continue
                        fi
                    elif [ "$sp_seen" = "1" ] && [ -n "$BATT_PCT" ]; then
                        # 只在首次确认时记一行，避免低电量充一整晚刷满日志
                        log "charging in super power, battery ${BATT_PCT}% <= ${EXIT_BATTERY_PCT}% -> keep staying"
                    fi
                else
                    sp_seen=0
                fi
            fi
            tick=$((tick + 1))
        else
            tick=0
            prev=0
            sp_seen=0
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

log "features: charge=$ENABLE_CHARGE idle=$ENABLE_IDLE timeout=${IDLE_TIMEOUT}s exit_pct=${EXIT_BATTERY_PCT}%"

if [ "$ENABLE_IDLE" = "1" ]; then
    idle_watch &
    IDLE_PID=$!
    echo $IDLE_PID > "$IDLEPIDFILE"
fi

if [ "$ENABLE_CHARGE" = "1" ]; then
    charge_loop
else
    # 只开闲置息屏：主进程不空转，直接等子进程
    [ -n "$IDLE_PID" ] && wait "$IDLE_PID"
fi
