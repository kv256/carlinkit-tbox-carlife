#!/system/bin/sh
# tbox_carlife: publish the CarLife USB identity at post-fs-data (~6.1 s after kernel start).
#
# The Venza head unit auto-starts CarLife only from a one-shot USB scan at its own start-up, and
# that scan happens around box uptime 10.6-11 s: the one car boot where it found the box, the box
# had enumerated at 10.63 s; in four boots where it enumerated at 10.8-11.3 s the HU never tried.
# carlife-daemon.sh (started by init via sd-reverse at ~9.2 s) cannot be earlier, so the gadget
# is bound here, ~4.5 s sooner, and handed over to the daemon unchanged.
#
# Stock init would otherwise disturb it during "on boot" (~8.0-8.6 s):
#   - init.qcom.usb.rc rewrites the g1 strings (serial 12345678, manufacturer, product);
#   - "sys.usb.config=none && sys.usb.configfs=1" (init.usb.configfs.rc, init.msm.usb.configfs.rc)
#     unbinds the UDC and removes configs/b.1/f*. sd-reverse.sh therefore stores
#     persist.vendor.usb.config=carlife (a value no trigger matches) in CarLife mode; on the first
#     boot after switching from "none" the keeper below simply rebinds.
# The keeper restores strings/link/UDC until the daemon takes over ($RUN/usb.daemon) or 30 s.
# Only CarLife mode: persistent props are not loaded yet at post-fs-data, so they are read from
# storage with resetprop -p. USB ADB / CarPlay modes and fastboot are untouched.

MODDIR=${0%/*}

# Defensive guard (all USB modes, RTC-independent): compare SHA-256 of vendor RRO APKs in this
# module against /cache/carlife/.tbox_ovl.sha (never writing root-owned files into system-owned
# /data/resource-cache). If any overlay APK changes while stale overlays.list / @idmap exist,
# purge them before zygote starts so Android 9 AssetManager never crashes system_server.
[ -e /data/resource-cache/.tbox_ovl.sha ] && rm -f /data/resource-cache/.tbox_ovl.sha 2>/dev/null
_ovl_stamp=/cache/carlife/.tbox_ovl.sha
if [ -d "$MODDIR/system/vendor/overlay" ] && [ -d /data/resource-cache ]; then
    if [ -x /data/adb/magisk/busybox ]; then
        _ovl_cur=$(/data/adb/magisk/busybox sha256sum "$MODDIR/system/vendor/overlay"/*.apk 2>/dev/null)
    else
        _ovl_cur=$(sha256sum "$MODDIR/system/vendor/overlay"/*.apk 2>/dev/null)
    fi
    if [ -n "$_ovl_cur" ]; then
        _ovl_prev=$(cat "$_ovl_stamp" 2>/dev/null)
        if [ "$_ovl_cur" != "$_ovl_prev" ]; then
            if [ -f /data/resource-cache/overlays.list ] || [ -f "$_ovl_stamp" ]; then
                rm -f /data/resource-cache/overlays.list /data/resource-cache/vendor@overlay@*@idmap
            fi
            mkdir -p /cache/carlife 2>/dev/null
            printf '%s\n' "$_ovl_cur" > "$_ovl_stamp" 2>/dev/null
        fi
    fi
fi

# Drop PackageManager's parse cache for every APK this module mounts, before system_server starts.
# The cache is reused while the APK's mtime is older than the cache entry, and files deployed
# before the box's clock is set are stamped 1970: a changed APK then keeps its old parse. That is
# how TboxNoNavBarOverlay stayed "static" after switching to isStatic=false, got no idmap from
# either path and left the OEM left dock visible. Re-parsing these few small APKs costs ~ms.
if [ -d /data/system/package_cache ]; then
    for _apk in $(find "$MODDIR/system" -name '*.apk' 2>/dev/null); do
        # keyed by the scanned path's name: the APK itself (vendor/overlay/X.apk) or its package
        # directory (priv-app/X/X.apk -> "X-<flags>")
        rm -f /data/system/package_cache/*/"${_apk##*/}"-* 2>/dev/null
        _pdir=${_apk%/*}; _pdir=${_pdir##*/}
        case "$_pdir" in overlay|app|priv-app) ;; *) rm -f /data/system/package_cache/*/"$_pdir"-* 2>/dev/null ;; esac
    done
fi

RP="/data/adb/magisk/magisk resetprop"
IS_CARLIFE=0
if [ "$($RP -p persist.tbox.carlife 2>/dev/null)" = "1" ] && [ "$($RP -p persist.sdcarplay.reverse.disable 2>/dev/null)" != "1" ]; then
    IS_CARLIFE=1
fi
WANT_FTRACE=0
if [ "$($RP -p persist.tbox.usb.ftrace 2>/dev/null)" = "1" ]; then
    WANT_FTRACE=1
fi

# MainAiBox (com.carsyso.main) is hidden in CarLife mode with "pm hide" by
# /data/adb/service.d/tbox_vd_input.sh. It used to be hidden here with a Magisk .replace, but a
# system APK that disappears at boot makes Android 9 wipe the package's data, so every
# CarLife -> CarPlay switch reset MainAiBox. Remove any .replace left by older versions.
[ -d "$MODDIR/system/app/MainAiBox" ] && rm -rf "$MODDIR/system/app/MainAiBox" 2>/dev/null

[ "$IS_CARLIFE" = "1" ] || exit 0
[ -x /data/adb/carlife/carlife-daemon.sh ] || exit 0

G=/config/usb_gadget/g1
UDC=7000000.dwc3
RUN=/cache/carlife
LOGF=$RUN/logs/daemon.log

(
    up() { read _u _i < /proc/uptime; echo "$_u"; }
    log() { echo "boot up=$(up) $*" >> "$LOGF"; }
    mkdir -p "$RUN/logs"
    rm -f "$RUN/usb.daemon"

    # /config is mounted, but init only creates g1 in "on boot" (~8.0 s); create it now (init's
    # later mkdir of existing directories succeeds).
    mkdir -p "$G" 2>/dev/null
    i=0
    while [ ! -e "/sys/class/udc/$UDC" ] && [ "$i" -lt 100 ]; do
        sleep 0.02
        i=$((i + 1))
    done
    if [ ! -d "$G" ] || [ ! -e "/sys/class/udc/$UDC" ]; then
        log "EARLY_USB_SKIPPED gadget_or_udc_missing"
        exit 0
    fi
    log "EARLY_USB_START"

    # Capture boot-time USB EP0 control packets (GET_DESCRIPTOR, MSFT100, AOA 51/52/53,
    # STALLs, resets) via dwc3 ftrace only when explicitly opted-in via persist.tbox.usb.ftrace=1.
    if [ "$WANT_FTRACE" = "1" ]; then
        TR=/sys/kernel/debug/tracing
        [ -d "$TR/events/dwc3" ] || mount -t debugfs none /sys/kernel/debug 2>/dev/null || true
        if [ -d "$TR/events/dwc3" ]; then
            echo 0 > "$TR/tracing_on" 2>/dev/null
            echo > "$TR/trace" 2>/dev/null
            for ev in dwc3/dwc3_ctrl_req dwc3/dwc3_ep0 dwc3/dwc3_gadget \
                      gadget/usb_gadget_connect gadget/usb_gadget_disconnect gadget/usb_ep_set_halt; do
                [ -e "$TR/events/$ev/enable" ] && echo 1 > "$TR/events/$ev/enable" 2>/dev/null
            done
            echo 1 > "$TR/tracing_on" 2>/dev/null
            log "EARLY_USB_FTRACE_ARMED"
        fi
    fi

    [ -d /sys/class/gpio/gpio38 ] || echo 38 > /sys/class/gpio/export 2>/dev/null
    echo out > /sys/class/gpio/gpio38/direction 2>/dev/null
    echo 1 > /sys/class/gpio/gpio38/value 2>/dev/null
    echo peripheral > /sys/devices/platform/soc/7000000.ssusb/mode 2>/dev/null

    serial=$(getprop ro.serialno)
    [ -n "$serial" ] || serial=12345678
    mkdir -p "$G/strings/0x409" "$G/configs/b.1/strings/0x409" "$G/functions/mtp.gs0" "$G/functions/accessory.gs2" 2>/dev/null

    BB=/data/adb/magisk/busybox
    SHIELDED=0
    set_strings() {
        [ "$(cat "$G/strings/0x409/serialnumber" 2>/dev/null)" = "$serial" ] || printf '%s' "$serial" > "$G/strings/0x409/serialnumber"
        [ "$(cat "$G/strings/0x409/manufacturer" 2>/dev/null)" = "CarLife" ] || printf 'CarLife' > "$G/strings/0x409/manufacturer"
        [ "$(cat "$G/strings/0x409/product" 2>/dev/null)" = "AndroidBox" ] || printf 'AndroidBox' > "$G/strings/0x409/product"
    }
    shield_strings() {
        [ -x "$BB" ] || return 0
        ok=1
        for s in serialnumber manufacturer product; do
            if ! "$BB" mount --bind /dev/null "$G/strings/0x409/$s" 2>/dev/null; then
                ok=0
            fi
        done
        if [ "$ok" = "1" ]; then
            SHIELDED=1
            log "EARLY_USB_SHIELDED"
        else
            for s in serialnumber manufacturer product; do
                "$BB" umount "$G/strings/0x409/$s" 2>/dev/null || true
            done
            SHIELDED=0
            log "EARLY_USB_SHIELD_FAILED"
        fi
    }
    unshield_strings() {
        [ "$SHIELDED" = "1" ] || return 0
        SHIELDED=0
        for s in serialnumber manufacturer product; do
            "$BB" umount "$G/strings/0x409/$s" 2>/dev/null || true
        done
        log "EARLY_USB_UNSHIELDED"
    }
    trap 'unshield_strings' EXIT
    bind() {
        [ -L "$G/configs/b.1/f1" ] || ln -s "$G/functions/mtp.gs0" "$G/configs/b.1/f1"
        [ -n "$(cat "$G/UDC" 2>/dev/null)" ] || echo "$UDC" > "$G/UDC"
    }

    echo 0x0200 > "$G/bcdUSB"
    echo 0x18d1 > "$G/idVendor"
    echo 0x4ee1 > "$G/idProduct"
    echo 0x1001 > "$G/bcdDevice"
    echo 0x00 > "$G/bDeviceClass"
    echo 0x00 > "$G/bDeviceSubClass"
    echo 0x00 > "$G/bDeviceProtocol"
    set_strings
    echo 0xc0 > "$G/configs/b.1/bmAttributes"
    printf 'mtp' > "$G/configs/b.1/strings/0x409/configuration"
    echo 1 > "$G/os_desc/use" 2>/dev/null
    echo 0x1 > "$G/os_desc/b_vendor_code" 2>/dev/null
    printf 'MSFT100' > "$G/os_desc/qw_sign" 2>/dev/null
    [ -L "$G/os_desc/b.1" ] || ln -s "$G/configs/b.1" "$G/os_desc/b.1" 2>/dev/null
    for f in "$G"/configs/b.1/f*; do [ -L "$f" ] && rm -f "$f"; done
    # Block init.qcom.usb.rc "on boot" (~8.1-8.8 s) from overwriting serialnumber to 12345678
    # (and manufacturer/product to ro.product.*) while the head unit is enumerating.
    shield_strings
    bind
    log "EARLY_USB_PUBLISHED vid=18d1 pid=4ee1 serial=$serial udc=$(cat "$G/UDC" 2>/dev/null) shielded=$SHIELDED"

    # - init sets sys.usb.configfs=1 in "on boot": take it back at once (configfs=2) and use
    #   sys.usb.config=carlife, which no trigger matches ("none" would unbind the gadget);
    # - the controller only starts a peripheral session when the port mode is written after
    #   VBUS/charger detection is up (~9-10 s), so re-write it while the port is not attached.
    rebinds=0 n=0 prev_st=""
    while [ ! -f "$RUN/usb.daemon" ]; do
        read _u _i < /proc/uptime
        [ "${_u%%.*}" -ge 30 ] && break
        n=$((n + 1))
        if [ "$(getprop sys.usb.configfs)" = "1" ]; then
            setprop sys.usb.configfs 2
            setprop sys.usb.config carlife
            log "EARLY_USB_CONFIGFS_CLAIMED"
        fi
        [ "$SHIELDED" = "1" ] || set_strings
        if [ ! -L "$G/configs/b.1/f1" ] || [ -z "$(cat "$G/UDC" 2>/dev/null)" ]; then
            bind
            rebinds=$((rebinds + 1))
            log "EARLY_USB_REBOUND n=$rebinds sys.usb.config=$(getprop sys.usb.config)"
        fi
        st=$(cat "/sys/class/udc/$UDC/state" 2>/dev/null)
        if [ "$st" = "not attached" ]; then
            [ $((n % 5)) -eq 0 ] && echo peripheral > /sys/devices/platform/soc/7000000.ssusb/mode 2>/dev/null
        fi
        if [ -n "$st" ] && [ "$st" != "$prev_st" ]; then
            log "EARLY_USB_STATE prev=${prev_st:-none} now=$st"
            prev_st="$st"
        fi
        sleep 0.05
    done
    unshield_strings
    log "EARLY_USB_HANDOVER rebinds=$rebinds daemon=$([ -f "$RUN/usb.daemon" ] && echo yes || echo timeout)"
) </dev/null >/dev/null 2>&1 &

exit 0
