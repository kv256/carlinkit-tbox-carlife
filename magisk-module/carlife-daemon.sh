#!/system/bin/sh
# Single-owner native CarLife USB AOA supervisor for CPC200-Tbox (msm8953 / Android 9)
umask 077
PATH=/system/bin:/system/xbin:/vendor/bin
export PATH

BASE=/data/adb/carlife
JAR=$BASE/carlife-md-api28.jar
RUN=/cache/carlife
LOGS=$RUN/logs
G=/config/usb_gadget/g1
UDC=7000000.dwc3
UDC_STATE=/sys/class/udc/$UDC/state

mkdir -p "$RUN" "$LOGS"
chmod 700 "$RUN" "$LOGS"

LOGF=$LOGS/daemon.log
log() {
    if [ -f "$LOGF" ] && [ "$(stat -c %s "$LOGF" 2>/dev/null || echo 0)" -gt 262144 ]; then
        mv "$LOGF" "$LOGS/daemon.1.log" 2>/dev/null || true
    fi
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || cut -d' ' -f1 /proc/uptime)" "$*" >> "$LOGF"
}

set_phase() {
    printf 'phase=%s\nupdated=%s\n' "$1" "$(cut -d' ' -f1 /proc/uptime)" > "$RUN/status.tmp"
    chmod 600 "$RUN/status.tmp"
    mv "$RUN/status.tmp" "$RUN/status"
    log "PHASE=$1 ${2-}"
}

MD_PID=""
OBS_PID=""
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
    stop_pid "$OBS_PID"; OBS_PID=""
    stop_pid "$MD_PID"; MD_PID=""
}
trap 'cleanup_all; exit 0' TERM INT

init_usb_hw() {
    # Ensure USB port is in peripheral mode (same GPIO38 + ssusb/mode as stock)
    if [ ! -d /sys/class/gpio/gpio38 ]; then
        echo 38 > /sys/class/gpio/export 2>/dev/null || true
        sleep 0.05
    fi
    echo out > /sys/class/gpio/gpio38/direction 2>/dev/null || true
    echo 1 > /sys/class/gpio/gpio38/value 2>/dev/null || true
    echo peripheral > /sys/devices/platform/soc/7000000.ssusb/mode 2>/dev/null || true

    # Wait for configfs gadget g1 and UDC
    w=0
    while [ ! -d "$G/functions/mtp.gs0" ] || [ ! -d "$G/functions/accessory.gs2" ] || [ ! -e "$UDC_STATE" ]; do
        sleep 0.5
        w=$((w + 1))
        if [ "$w" -ge 60 ]; then
            log "WARN waiting for configfs gadget/UDC ($w)"
            w=0
        fi
    done

    # Take exclusive control from stock init.usb.configfs.rc (which matches configfs=1)
    setprop sys.usb.configfs 2
    setprop sys.usb.config none
    setprop sys.usb.state carlife
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
    setprop sys.usb.configfs 2
    setprop sys.usb.config none
    setprop sys.usb.state carlife
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
    set_phase USB_DISCOVERY "vid=18d1 pid=4ee1 fn=mtp.gs0"
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
    log "MD_STARTED pid=$MD_PID nonce=$MD_NONCE"
}

wait_md_ready() {
    set_phase MEDIA_WAIT
    i=0
    while true; do
        if [ ! -d "/proc/$MD_PID" ]; then
            log "MD_EXITED_DURING_WAIT pid=$MD_PID"
            return 1
        fi
        if [ -f "$RUN/md.boot-state" ]; then
            if grep -q '^command_ready=true$' "$RUN/md.boot-state" && grep -q '^media_ready=true$' "$RUN/md.boot-state"; then
                log "MD_READY pid=$MD_PID"
                return 0
            fi
        fi
        sleep 0.25
        i=$((i + 1))
        if [ $((i % 40)) -eq 0 ]; then
            log "WAIT_MD_READY ticks=$i"
        fi
    done
}

start_observer() {
    stop_pid "$OBS_PID"; OBS_PID=""
    rm -f "$RUN/aoa.marker" "$RUN/.aoa.marker.tmp"
    : > "$LOGS/uevents.log"
    : > "$LOGS/uevents.err"
    chmod 600 "$LOGS/uevents.log" "$LOGS/uevents.err"
    CLASSPATH="$JAR" /system/bin/app_process /system/bin org.carlife.hulab.UEventWatch \
        "$RUN/aoa.marker" 0 > "$LOGS/uevents.log" 2> "$LOGS/uevents.err" &
    OBS_PID=$!
    i=0
    while [ "$i" -lt 40 ]; do
        if [ ! -d "/proc/$OBS_PID" ]; then
            log "OBSERVER_DIED_EARLY pid=$OBS_PID"
            return 1
        fi
        if grep -q '^READY ' "$LOGS/uevents.log" 2>/dev/null; then
            log "OBSERVER_READY pid=$OBS_PID"
            return 0
        fi
        sleep 0.1
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

log "=== CARLIFE DAEMON BOOT pid=$$ ==="
set_phase INIT
init_usb_hw

GEN=0
while true; do
    GEN=$((GEN + 1))
    log "=== CYCLE gen=$GEN ==="
    start_md
    if ! wait_md_ready; then
        sleep 2
        continue
    fi
    sync_bt_a2dp_priority
    if ! start_observer; then
        stop_pid "$MD_PID"; MD_PID=""
        sleep 2
        continue
    fi

    publish_mtp

    # Wait for genuine kernel ACCESSORY=START uevent from CarLife head unit
    got_aoa=0
    while true; do
        if [ -f "$RUN/aoa.marker" ]; then
            log "AOA_START_MATCHED $(cat "$RUN/aoa.marker" 2>/dev/null)"
            got_aoa=1
            break
        fi
        if [ ! -d "/proc/$MD_PID" ]; then
            log "MD_DIED_IN_DISCOVERY"
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
        sleep 0.1
    done

    stop_pid "$OBS_PID"; OBS_PID=""
    if [ "$got_aoa" != "1" ]; then
        stop_pid "$MD_PID"; MD_PID=""
        clear_gadget
        sleep 1
        continue
    fi

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
