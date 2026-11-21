#!/system/bin/sh
# Single-owner native CarLife USB AOA supervisor for CPC200-Tbox (msm8953 / Android 9)
#
# Boot-time USB identity (why it matters): the Venza head unit's CarLife only auto-starts
# from a one-shot scan when it boots (ConnectService.handleBootReconnect); a device that
# appears later is ignored until the user opens CarLife. A phone is enumerated instantly,
# so the box must look like that phone as early as possible:
#   - Stock init would bind the Qualcomm debug composite (diag,serial_cdev,rmnet,dpl,adb)
#     at ~10 s (init.msm.usb.configfs.rc, requires sys.usb.configfs=1). We claim USB
#     (sys.usb.configfs=2) as soon as init has created the gadget functions (~9.2 s) and
#     keep it claimed, so that composite never appears in CarLife mode.
#   - The CarLife MTP identity (18d1:4ee1, ro.serialno) is published immediately and stays
#     up; MdMain starts in parallel instead of gating the USB identity.
# USB ADB mode and CarPlay mode never run this script (sd-reverse.sh branches earlier).
umask 077
PATH=/system/bin:/system/xbin:/vendor/bin
export PATH

# First thing, before any (slow, boot-contended) setup: disarm the stock configfs=1 triggers.
# init.msm.usb.configfs.rc binds the debug composite <1 s after the gadget functions exist.
setprop sys.usb.configfs 2
setprop sys.usb.config carlife

BASE=/data/adb/carlife
JAR=$BASE/carlife-md-api28.jar
RUN=/cache/carlife
LOGS=$RUN/logs
G=/config/usb_gadget/g1
UDC=7000000.dwc3
UDC_STATE=/sys/class/udc/$UDC/state
BUSYBOX=/data/adb/magisk/busybox
CARLIFE_VID=0x18d1
GUARD_UNTIL_UPTIME=120          # keep re-asserting USB ownership during boot (seconds)
AOA_WAIT_MD_MS=4000             # HU retries accessory re-enumeration 10 x 500 ms after START

mkdir -p "$RUN" "$LOGS"
chmod 700 "$RUN" "$LOGS"

LOGF=$LOGS/daemon.log
# Shell builtins only on hot paths: every fork is slow while Android is booting.
uptime_s() { read _up _idle < /proc/uptime; echo "$_up"; }
LOG_LINES=0
log() {
    LOG_LINES=$((LOG_LINES + 1))
    if [ $((LOG_LINES % 64)) -eq 0 ] && [ "$(stat -c %s "$LOGF" 2>/dev/null || echo 0)" -gt 262144 ]; then
        mv "$LOGF" "$LOGS/daemon.1.log" 2>/dev/null || true
    fi
    read _up _idle < /proc/uptime
    if [ "${_up%%.*}" -lt 60 ]; then
        printf 'boot up=%s %s\n' "$_up" "$*" >> "$LOGF"
    else
        printf '%s up=%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$_up" "$*" >> "$LOGF"
    fi
}

set_phase() {
    printf 'phase=%s\nupdated=%s\n' "$1" "$(uptime_s)" > "$RUN/status.tmp"
    chmod 600 "$RUN/status.tmp"
    mv "$RUN/status.tmp" "$RUN/status"
    log "PHASE=$1 ${2-}"
}

MD_PID=""
OBS_PID=""
KMSG_PID=""
GUARD_PID=""
BTW_PID=""
BALL_STARTED=0

stop_pid() {
    p=$1
    [ -n "$p" ] || return 0
    if [ -d "/proc/$p" ]; then
        kill -TERM "$p" 2>/dev/null || true
        i=0
        while [ -d "/proc/$p" ] && [ "$i" -lt 12 ]; do
            sleep 0.25
            i=$((i + 1))
        done
        if [ -d "/proc/$p" ]; then
            kill -KILL "$p" 2>/dev/null || true
            sleep 0.2
        fi
        wait "$p" 2>/dev/null || true
    fi
}

cleanup_all() {
    log "CLEANUP_ALL"
    unshield_strings
    stop_pid "$GUARD_PID"; GUARD_PID=""
    stop_observer
    stop_pid "$BTW_PID"; BTW_PID=""
    stop_pid "$MD_PID"; MD_PID=""
}
trap 'cleanup_all; exit 0' TERM INT

# Remove any /dev/null bind shields placed on g1/strings/0x409/* by post-fs-data.sh.
unshield_strings() {
    [ -x "$BUSYBOX" ] || return 0
    for s in serialnumber manufacturer product; do
        "$BUSYBOX" umount "$G/strings/0x409/$s" 2>/dev/null || true
    done
}

claim_usb_props() {
    setprop sys.usb.configfs 2
    # not "none": "sys.usb.config=none && sys.usb.configfs=1" unbinds the gadget if init flips
    # configfs back to 1 during boot; "carlife" matches no trigger
    setprop sys.usb.config carlife
    setprop sys.usb.state carlife
}

init_usb_hw() {
    # Ensure USB port is in peripheral mode (same GPIO38 + ssusb/mode as stock)
    if [ ! -d /sys/class/gpio/gpio38 ]; then
        echo 38 > /sys/class/gpio/export 2>/dev/null || true
        sleep 0.05
    fi
    echo out > /sys/class/gpio/gpio38/direction 2>/dev/null || true
    echo 1 > /sys/class/gpio/gpio38/value 2>/dev/null || true
    echo peripheral > /sys/devices/platform/soc/7000000.ssusb/mode 2>/dev/null || true

    # Wait for configfs gadget g1 + functions (created by init.qcom.usb.rc "on boot") and the UDC.
    # Poll fast: the stock debug composite binds <1 s after the functions appear.
    w=0
    while [ ! -d "$G/functions/mtp.gs0" ] || [ ! -d "$G/functions/accessory.gs2" ] || [ ! -e "$UDC_STATE" ]; do
        sleep 0.05
        w=$((w + 1))
        if [ $((w % 400)) -eq 0 ]; then
            log "WARN waiting for configfs gadget/UDC ($w)"
        fi
    done
    # Take exclusive control from stock init.usb.configfs.rc / init.msm.usb.configfs.rc (configfs=1 triggers)
    claim_usb_props
    : > "$RUN/usb.daemon"   # post-fs-data.sh keeper stops touching the gadget
    unshield_strings
    log "USB_CLAIMED configfs=$(getprop sys.usb.configfs) udc_state=$(cat "$UDC_STATE" 2>/dev/null)"
}

# Boot-time guard: init's "on boot" may flip sys.usb.configfs back to 1 after we claimed it,
# which would let the stock debug composite bind once adbd sets sys.usb.ffs.ready=1.
start_guard() {
    (
        while read _gup _gidle < /proc/uptime && [ "${_gup%%.*}" -lt "$GUARD_UNTIL_UPTIME" ]; do
            cfs=$(getprop sys.usb.configfs)
            if [ "$cfs" != "2" ]; then
                claim_usb_props
                log "USB_GUARD_REASSERT configfs_was=$cfs usb_config=$(getprop sys.usb.config)"
            fi
            sleep 0.1
        done
    ) &
    GUARD_PID=$!
}

clear_gadget() {
    echo "" > "$G/UDC" 2>/dev/null || true
    for cfg in b.1 b.2 b.3 b.4; do
        if [ -d "$G/configs/$cfg" ]; then
            for f in "$G/configs/$cfg"/f*; do
                [ -L "$f" ] && rm -f "$f" 2>/dev/null || true
            done
        fi
    done
}

publish_mtp() {
    claim_usb_props
    unshield_strings
    serial=$(getprop ro.serialno 2>/dev/null)
    [ -n "$serial" ] || serial="12345678"
    # Gadget already published by post-fs-data.sh at ~6.5 s: keep it bound (a rebind would make the
    # head unit see a disconnect right around its boot-time scan); only re-assert the strings.
    if [ "$(cat "$G/idVendor" 2>/dev/null)" = "0x18d1" ] && [ "$(cat "$G/idProduct" 2>/dev/null)" = "0x4ee1" ] &&
       [ "$(basename "$(readlink "$G/configs/b.1/f1" 2>/dev/null)")" = "mtp.gs0" ] && [ -n "$(cat "$G/UDC" 2>/dev/null)" ]; then
        printf '%s' "$serial" > "$G/strings/0x409/serialnumber"
        printf 'CarLife' > "$G/strings/0x409/manufacturer"
        printf 'AndroidBox' > "$G/strings/0x409/product"
        set_phase USB_DISCOVERY "vid=18d1 pid=4ee1 fn=mtp.gs0 serial=$serial kept=early_gadget"
        return 0
    fi
    clear_gadget
    echo 0x18d1 > "$G/idVendor"
    echo 0x4ee1 > "$G/idProduct"
    echo 0x1001 > "$G/bcdDevice"
    echo 0x00 > "$G/bDeviceClass"
    echo 0x00 > "$G/bDeviceSubClass"
    echo 0x00 > "$G/bDeviceProtocol"
    printf '%s' "$serial" > "$G/strings/0x409/serialnumber"
    printf 'CarLife' > "$G/strings/0x409/manufacturer"
    printf 'AndroidBox' > "$G/strings/0x409/product"
    echo 0xc0 > "$G/configs/b.1/bmAttributes"
    printf 'mtp' > "$G/configs/b.1/strings/0x409/configuration"
    ln -s "$G/functions/mtp.gs0" "$G/configs/b.1/f1"
    echo "$UDC" > "$G/UDC"
    set_phase USB_DISCOVERY "vid=18d1 pid=4ee1 fn=mtp.gs0 serial=$serial"
}

switch_to_accessory() {
    clear_gadget
    echo 0x18d1 > "$G/idVendor"
    echo 0x2d00 > "$G/idProduct"
    echo 0xc0 > "$G/configs/b.1/bmAttributes"
    printf 'accessory' > "$G/configs/b.1/strings/0x409/configuration"
    ln -s "$G/functions/accessory.gs2" "$G/configs/b.1/f1"
    echo "$UDC" > "$G/UDC"
    set_phase ACCESSORY_CONNECTED "vid=18d1 pid=2d00 fn=accessory.gs2"
}

start_md() {
    stop_pid "$MD_PID"; MD_PID=""
    rm -f "$RUN/md.boot-state" "$RUN"/.md.boot-state.*
    MD_NONCE=$(tr -d '-' < /proc/sys/kernel/random/uuid)
    : > "$LOGS/md.stderr"
    chmod 600 "$LOGS/md.stderr"
    video_fps=$(getprop persist.tbox.carlife.fps 2>/dev/null)
    case "$video_fps" in
        15|30|60)
            settings put global carlife_video_config "v1:auto:$video_fps" >/dev/null 2>&1 || true
            ;;
        *)
            video_fps=30
            ;;
    esac
    CLASSPATH="$JAR" /system/bin/app_process /system/bin org.carlife.hulab.MdMain \
        --transport native-usb \
        --seconds 0 \
        --display-width 0 \
        --display-height 0 \
        --source-display 0 \
        --video-source mediacodec \
        --video-path egl \
        --encoder OMX.qcom.video.encoder.avc \
        --csd-source buffer \
        --video-mode auto \
        --video-fps "$video_fps" \
        --audio-source split \
        --native-services on \
        --touch-profile recognized \
        --log-dir "$LOGS" \
        --boot-native true \
        --boot-state-file "$RUN/md.boot-state" \
        --boot-nonce "$MD_NONCE" \
        > /dev/null 2>> "$LOGS/md.stderr" &
    MD_PID=$!
    MD_READY_LOGGED=0
    MD_CMD_LOGGED=0
    log "MD_STARTED pid=$MD_PID fps=$video_fps nonce=$MD_NONCE"
}

md_flag() {
    [ -f "$RUN/md.boot-state" ] && grep -q "^$1=true$" "$RUN/md.boot-state"
}

# Non-blocking progress notes so the boot timeline shows when MdMain became usable.
note_md_progress() {
    if [ "$MD_CMD_LOGGED" = "0" ] && md_flag command_ready; then
        MD_CMD_LOGGED=1
        log "MD_COMMAND_READY pid=$MD_PID"
    fi
    if [ "$MD_READY_LOGGED" = "0" ] && md_flag media_ready; then
        MD_READY_LOGGED=1
        log "MD_MEDIA_READY pid=$MD_PID"
    fi
}

BUSYBOX=/data/adb/magisk/busybox

# AOA ACCESSORY=START watcher. Native busybox uevent listens within milliseconds (the Java
# UEventWatch needs ~1.5 s of app_process start-up at boot); Java stays as the fallback.
start_observer() {
    stop_observer
    rm -f "$RUN/aoa.marker" "$RUN/.aoa.marker.tmp"
    : > "$LOGS/uevents.log"
    : > "$LOGS/uevents.err"
    chmod 600 "$LOGS/uevents.log" "$LOGS/uevents.err"
    if [ -x "$BUSYBOX" ] && "$BUSYBOX" --list 2>/dev/null | grep -qx uevent; then
        AOA_MARKER="$RUN/aoa.marker" "$BUSYBOX" uevent /system/bin/sh -c '
            [ "$ACCESSORY" = "START" ] || exit 0
            printf "UEVENT uptime_s=%s DEVPATH=%s ACCESSORY=%s SEQNUM=%s\n" "$(cut -d" " -f1 /proc/uptime)" "$DEVPATH" "$ACCESSORY" "$SEQNUM" > "$AOA_MARKER.tmp"
            mv "$AOA_MARKER.tmp" "$AOA_MARKER"
        ' > "$LOGS/uevents.log" 2> "$LOGS/uevents.err" &
        OBS_PID=$!
        OBS_KIND=busybox
    else
        CLASSPATH="$JAR" /system/bin/app_process /system/bin org.carlife.hulab.UEventWatch \
            "$RUN/aoa.marker" 0 > "$LOGS/uevents.log" 2> "$LOGS/uevents.err" &
        OBS_PID=$!
        OBS_KIND=java
    fi
    start_kmsg_watch
}

# AOA START from the kernel log. On this kernel ACCESSORY=START is raised by deferred work that
# re-reads the USB state; the head unit resets the bus 10-16 ms after START, so when the work
# runs late (busy CPU at boot) it only sees "disconnected" and the uevent is never sent. Car
# boot log: all 10 HU boot-reconnect attempts (13.2-19.7 s) were lost this way. The setup
# request itself is printed synchronously ("40.35 v0000 i0000 l0" / "mtp_ctrlrequest:1421"),
# and /dev/kmsg keeps records until read, so this watcher cannot miss it.
# A tag written to the kernel log marks where new records start (kmsg is read from the oldest).
start_kmsg_watch() {
    stop_pid "$KMSG_PID"; KMSG_PID=""
    [ -x "$BUSYBOX" ] && [ -r /dev/kmsg ] || return 0
    # busybox awk cannot read /dev/kmsg itself (EINVAL); cat reads one record per read().
    # A FIFO (not a pipe) so both processes are known and killed on stop.
    fifo="$RUN/kmsg.fifo"
    rm -f "$fifo"
    mkfifo -m 600 "$fifo" 2>/dev/null || return 0
    # The first watcher of a boot also takes START requests logged before it started: the gadget is
    # up from ~6.5 s (post-fs-data.sh), so the HU may send AOA before this daemon runs (~9.3 s).
    # /dev/kmsg only holds this boot's records.
    kmsg_from_boot=0
    read _ku _ki < /proc/uptime
    if [ -z "$KMSG_BOOT_DONE" ] && [ "${_ku%%.*}" -lt 60 ]; then kmsg_from_boot=1; fi
    KMSG_BOOT_DONE=1
    (
        c=""; k=""
        trap 'kill $c $k 2>/dev/null; exit 0' TERM INT
        while :; do
            read _ku _ki < /proc/uptime
            tag="carlife-aoa-watch pid=$$ up=$_ku"
            echo "$tag" > /dev/kmsg 2>/dev/null
            "$BUSYBOX" awk -v tag="$tag" -v armed="$kmsg_from_boot" -v marker="$RUN/aoa.marker" '
                !armed { if (index($0, tag)) armed = 1; next }
                /40\.35 v[0-9a-f]+ i[0-9a-f]+ l[0-9]/ || index($0, "mtp_ctrlrequest:1421") {
                    split($0, h, ";"); split(h[1], f, ",")
                    tmp = marker ".kmsg.tmp"
                    printf "KMSG uptime_s=%.3f AOA_START_REQUEST\n", f[3] / 1000000 > tmp
                    close(tmp)
                    system("mv " tmp " " marker)
                }' < "$fifo" &
            c=$!
            cat /dev/kmsg > "$fifo" &
            k=$!
            wait "$c"
            # awk ends when cat hits a read error (EPIPE after ring-buffer overrun): reopen, fresh tag
            kill $k 2>/dev/null; wait "$k" 2>/dev/null
            c=""; k=""
            kmsg_from_boot=0
            sleep 0.2
        done
    ) &
    KMSG_PID=$!
}

stop_observer() {
    stop_pid "$OBS_PID"; OBS_PID=""
    stop_pid "$KMSG_PID"; KMSG_PID=""
}

wait_observer_ready() {
    i=0
    while [ "$i" -lt 100 ]; do
        if [ ! -d "/proc/$OBS_PID" ]; then
            log "OBSERVER_DIED_EARLY pid=$OBS_PID"
            return 1
        fi
        if [ "$OBS_KIND" = "busybox" ] && [ "$i" -ge 1 ]; then
            # netlink socket is bound before busybox enters its receive loop
            log "OBSERVER_READY kind=busybox pid=$OBS_PID kmsg_pid=${KMSG_PID:-none}"
            return 0
        fi
        if grep -q '^READY ' "$LOGS/uevents.log" 2>/dev/null; then
            log "OBSERVER_READY kind=java pid=$OBS_PID kmsg_pid=${KMSG_PID:-none}"
            return 0
        fi
        sleep 0.05
        i=$((i + 1))
    done
    log "OBSERVER_READY_TIMEOUT"
    return 1
}

sync_bt_a2dp_priority() {
    m=$(getprop persist.tbox.carlife.audio.media 2>/dev/null)
    n=$(getprop persist.tbox.carlife.audio.navi 2>/dev/null)
    if [ "$m" = "bt" ] || [ "$n" = "bt" ]; then
        return 0
    fi
    # Keep QCOM-BTD HFP intact while preventing Venza A2DP 6-second reconnect loops when media+navi use USB.
    for kv in $(settings list global 2>/dev/null | grep '^bluetooth_a2dp_sink_priority_'); do
        k=${kv%%=*}
        v=${kv#*=}
        if [ -n "$k" ] && [ "$v" != "0" ]; then
            settings put global "$k" 0 >/dev/null 2>&1 || true
            log "BT_A2DP_PRIORITY_ZEROED key=$k prev=$v"
        fi
    done
}

# settings(1) needs system_server; run it once boot completes without blocking USB bring-up.
sync_bt_a2dp_priority_when_booted() {
    (
        i=0
        while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 1200 ]; do
            sleep 0.25
            i=$((i + 1))
        done
        sync_bt_a2dp_priority
    ) &
}

# ---------------------------------------------------------------------------------------------
# Phone <-> box Bluetooth profiles. sdsdk816 is the goc Bluetooth stack (own chip on /dev/ttyHS0,
# shown to phones as "AutoKit-TBox") and always reconnects the last phone with calls + music,
# which takes the phone's hands-free link away from the head unit. persist.tbox.carlife.bt.phone:
#   all   (default) calls + music, as stock
#   media music only, so the phone's calls stay on the head unit's own Bluetooth (an iPhone has
#         no per-device profile switch; the head unit is set to connect calls only)
# Commands are the documented AT# set of /system/config.ini, written to sdsdk816's command pty
# (com.carsyso.bluetooth owns its reading side, so this only writes):
#   SE  disconnect the phone (there is no calls-only disconnect: it drops everything)
#   DCn connect music only     SCn add calls to an existing link     CC<addr> calls + music
#   (n = 1-based record in /data/goc/bt_conf.ini; DC1 = addr0 verified on iPhone)
# State is read from sdsdk816's own logcat lines; nothing is done while a call is up (MG4/5/6).
PROP_BT_PHONE=persist.tbox.carlife.bt.phone
GOC_TTY=/dev/goc_serial
GOC_CONF=/data/goc/bt_conf.ini

goc_cmd() { printf 'AT#%s\r\n' "$1" > "$GOC_TTY" 2>/dev/null; }

goc_index() {
    grep -i -m 1 "^addr[0-9]*=$1\$" "$GOC_CONF" 2>/dev/null | sed 's/^addr\([0-9]*\)=.*/\1/' |
        while read -r n; do echo $((n + 1)); done
}

goc_last_addr() { sed -n '/^\[LASTDEV\]/,/^\[/s/^addr=//p' "$GOC_CONF" 2>/dev/null | head -n 1; }

bt_phone_mode() { [ "$(getprop "$PROP_BT_PHONE" 2>/dev/null)" = "media" ] && echo media || echo all; }

start_bt_profile_watch() {
    stop_pid "$BTW_PID"; BTW_PID=""
    fifo="$RUN/btwatch.fifo"
    rm -f "$fifo"
    mkfifo -m 600 "$fifo" 2>/dev/null || return 0
    (
        k=""
        trap '[ -n "$k" ] && kill $k 2>/dev/null; exit 0' TERM INT
        read _bu _bi < /proc/uptime
        tag="carlife-btwatch pid=$$ up=$_bu"
        logcat -v brief -s sd:D carlife-btwatch:I > "$fifo" 2>/dev/null &
        k=$!
        # logcat replays its buffer first; this line marks where live events start
        /system/bin/log -t carlife-btwatch "$tag"
        armed=0 hfst=1 av=0 addr="" mode="" checked=-10 tries=0 tries_since=0 unmute_at=-10 unmute_log=-100
        while IFS= read -r line; do
            want_unmute=0
            case "$line" in
                *"$tag"*) armed=1 ;;
                *"send_ind::MX0"*) a=${line#*send_ind::MX0}; addr=$(echo "$a" | cut -c1-12) ;;
                *"hf connectted addr:["*) a=${line#*addr:[}; addr=$(echo "${a%%]*}" | tr -d ':') ;;
                *"send_ind::MG"*) a=${line#*send_ind::MG}; hfst=${a%%[!0-9]*} ;;
                *"send_ind::IA"*) hfst=1 ;;
                *"avrcp connected"*|*"a2dp_set_configuration: codec sbc"*) av=1 ;;
                *"gap connect event: 0"*) hfst=1; av=0 ;;
                # Music-only: com.carsyso.bluetooth (XQBluetooth) mutes Bluetooth music (AT#VA) when
                # the calls link drops and only unmutes for a phone it sees as connected (calls
                # link), so the phone's stream arrives but sdsdk816 drops it ("A2dp mute!!!!").
                *"a2dp_player_start(int, int, int): start succ"*|*"A2dp mute!!!!"*) want_unmute=1 ;;
            esac
            [ "$armed" = 1 ] || continue
            read _bu _bi < /proc/uptime
            now=${_bu%%.*}
            prev=$mode
            if [ $((now - checked)) -ge 2 ]; then
                checked=$now
                mode=$(bt_phone_mode)
                [ -n "$prev" ] && [ "$prev" != "$mode" ] && log "BT_PHONE_MODE $prev -> $mode"
            fi
            [ -n "$addr" ] || addr=$(goc_last_addr)
            if [ "$want_unmute" = 1 ] && [ "$mode" = "media" ] && [ "$hfst" != "3" ] && [ $((now - unmute_at)) -ge 4 ]; then
                unmute_at=$now
                goc_cmd VB
                if [ $((now - unmute_log)) -ge 60 ]; then
                    unmute_log=$now
                    log "BT_PHONE_MUSIC_UNMUTED (music-only; Bluetooth app had muted it)"
                fi
            fi
            if [ "$mode" = "media" ] && [ "$hfst" = "3" ]; then
                # at most 3 attempts per 10 min, in case a phone insists on reconnecting calls
                [ $((now - tries_since)) -gt 600 ] && { tries_since=$now; tries=0; }
                idx=$(goc_index "$addr")
                if [ "$tries" -lt 3 ] && [ -n "$idx" ]; then
                    tries=$((tries + 1))
                    log "BT_PHONE_MEDIA_ONLY addr=$addr idx=$idx try=$tries (dropping calls, reconnecting music)"
                    goc_cmd SE
                    sleep 3
                    goc_cmd "DC$idx"
                    hfst=1
                elif [ "$tries" = "3" ]; then
                    tries=4
                    log "BT_PHONE_MEDIA_ONLY_GAVE_UP addr=$addr (phone keeps reconnecting calls)"
                fi
            elif [ "$prev" = "media" ] && [ "$mode" = "all" ] && [ "$hfst" = "1" ] && [ -n "$addr" ]; then
                idx=$(goc_index "$addr")
                if [ "$av" = "1" ] && [ -n "$idx" ]; then
                    log "BT_PHONE_ALL addr=$addr idx=$idx (adding calls)"
                    goc_cmd "SC$idx"
                else
                    log "BT_PHONE_ALL addr=$addr (reconnecting calls + music)"
                    goc_cmd "CC$addr"
                fi
                tries=0
            fi
        done < "$fifo"
    ) &
    BTW_PID=$!
}

start_guard
# Order matters for boot latency: claim USB -> native AOA watcher -> CarLife identity -> MdMain.
start_observer
init_usb_hw
log "=== CARLIFE DAEMON BOOT pid=$$ ==="
sync_bt_a2dp_priority_when_booted
start_bt_profile_watch

GEN=0
while true; do
    GEN=$((GEN + 1))
    log "=== CYCLE gen=$GEN ==="
    [ -n "$OBS_PID" ] && [ -d "/proc/$OBS_PID" ] || start_observer
    if ! wait_observer_ready; then
        stop_observer
        sleep 1
        continue
    fi

    publish_mtp
    [ -n "$MD_PID" ] && [ -d "/proc/$MD_PID" ] || start_md

    # Wait for AOA START from the CarLife head unit (ACCESSORY=START uevent or kernel log).
    # The USB identity stays up throughout: MdMain restarts never touch the gadget.
    got_aoa=0
    while true; do
        note_md_progress
        if [ -f "$RUN/aoa.marker" ]; then
            log "AOA_START_MATCHED $(cat "$RUN/aoa.marker" 2>/dev/null)"
            got_aoa=1
            break
        fi
        if [ ! -d "/proc/$OBS_PID" ]; then
            if [ -f "$RUN/aoa.marker" ]; then
                log "AOA_START_MATCHED_ON_EXIT $(cat "$RUN/aoa.marker" 2>/dev/null)"
                got_aoa=1
            else
                log "OBSERVER_EXITED_WITHOUT_MARKER"
            fi
            break
        fi
        if [ ! -d "/proc/$MD_PID" ]; then
            log "MD_DIED_IN_DISCOVERY pid=$MD_PID (restarting, USB identity kept)"
            start_md
        fi
        vid=$(cat "$G/idVendor" 2>/dev/null)
        if [ "$vid" != "$CARLIFE_VID" ]; then
            log "USB_IDENTITY_LOST idVendor=$vid (republishing)"
            publish_mtp
        fi
        sleep 0.1
    done

    if [ "$got_aoa" != "1" ]; then
        stop_observer
        clear_gadget
        sleep 1
        continue
    fi

    # MdMain must hold /dev/usb_accessory (command_ready) before the HU starts bulk I/O; the HU
    # waits ~5 s for the accessory re-enumeration, so wait for MdMain at most AOA_WAIT_MD_MS.
    waited=0
    while ! md_flag command_ready && [ "$waited" -lt "$AOA_WAIT_MD_MS" ]; do
        if [ ! -d "/proc/$MD_PID" ]; then
            log "MD_DIED_BEFORE_ACCESSORY pid=$MD_PID (restarting)"
            start_md
        fi
        sleep 0.05
        waited=$((waited + 50))
    done
    note_md_progress
    log "AOA_SWITCH md_command_ready=$(md_flag command_ready && echo true || echo false) md_media_ready=$(md_flag media_ready && echo true || echo false) waited_ms=$waited"
    switch_to_accessory
    # Stop the watchers only after switching: stop_pid needs >=0.25 s per process, and the HU
    # repeats the AOA handshake every ~0.7 s if the accessory has not appeared yet.
    stop_observer
    # Keep Bluetooth A2DP/HFP audio daemon (sdsdk816) at audio priority so app launches don't cause BT underruns.
    for sp in $(pidof sdsdk816 2>/dev/null); do
        renice -n -16 -p "$sp" 2>/dev/null || true
        for tid in /proc/"$sp"/task/*; do
            t="${tid##*/}"
            renice -n -16 -p "$t" 2>/dev/null || true
        done
    done

    # Monitor active accessory session for MD exit or physical USB detach
    detach_ticks=0
    while true; do
        note_md_progress
        if [ ! -d "/proc/$MD_PID" ]; then
            log "MD_SESSION_EXITED gen=$GEN"
            break
        fi
        st=$(cat "$UDC_STATE" 2>/dev/null || echo unknown)
        if [ "$st" = "configured" ] && [ "$BALL_STARTED" = "0" ]; then
            BALL_STARTED=1
            am startservice --user 0 -n com.sd.ball/.WindowService -a com.sd.intent.action.WindowService >/dev/null 2>&1 || true
        fi
        if [ "$st" = "not attached" ]; then
            detach_ticks=$((detach_ticks + 1))
            if [ "$detach_ticks" -ge 8 ]; then
                log "USB_PHYSICAL_DETACH gen=$GEN"
                break
            fi
        else
            detach_ticks=0
        fi
        sleep 0.25
    done

    stop_pid "$MD_PID"; MD_PID=""
    clear_gadget
    sleep 0.5
done
