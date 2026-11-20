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
setprop sys.usb.config none

BASE=/data/adb/carlife
JAR=$BASE/carlife-md-api28.jar
RUN=/cache/carlife
LOGS=$RUN/logs
G=/config/usb_gadget/g1
UDC=7000000.dwc3
UDC_STATE=/sys/class/udc/$UDC/state
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
GUARD_PID=""
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
    stop_pid "$GUARD_PID"; GUARD_PID=""
    stop_pid "$OBS_PID"; OBS_PID=""
    stop_pid "$MD_PID"; MD_PID=""
}
trap 'cleanup_all; exit 0' TERM INT

claim_usb_props() {
    setprop sys.usb.configfs 2
    setprop sys.usb.config none
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
    clear_gadget
    serial=$(getprop ro.serialno 2>/dev/null)
    [ -n "$serial" ] || serial="12345678"
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
        --video-fps 30 \
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
    log "MD_STARTED pid=$MD_PID nonce=$MD_NONCE"
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
    stop_pid "$OBS_PID"; OBS_PID=""
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
            log "OBSERVER_READY kind=busybox pid=$OBS_PID"
            return 0
        fi
        if grep -q '^READY ' "$LOGS/uevents.log" 2>/dev/null; then
            log "OBSERVER_READY kind=java pid=$OBS_PID"
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

start_guard
# Order matters for boot latency: claim USB -> native AOA watcher -> CarLife identity -> MdMain.
start_observer
init_usb_hw
log "=== CARLIFE DAEMON BOOT pid=$$ ==="
sync_bt_a2dp_priority_when_booted

GEN=0
while true; do
    GEN=$((GEN + 1))
    log "=== CYCLE gen=$GEN ==="
    [ -n "$OBS_PID" ] && [ -d "/proc/$OBS_PID" ] || start_observer
    if ! wait_observer_ready; then
        stop_pid "$OBS_PID"; OBS_PID=""
        sleep 1
        continue
    fi

    publish_mtp
    [ -n "$MD_PID" ] && [ -d "/proc/$MD_PID" ] || start_md

    # Wait for the genuine kernel ACCESSORY=START uevent from the CarLife head unit.
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

    stop_pid "$OBS_PID"; OBS_PID=""
    if [ "$got_aoa" != "1" ]; then
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
