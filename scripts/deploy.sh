#!/usr/bin/env bash
# Atomic non-destructive deploy to CPC200-Tbox over Wi-Fi ADB.
# Usage:
#   bash scripts/deploy.sh [--serial HOST:PORT] [--all | --jar | --module | --app | --launcher]
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB_BIN:-$REPO/../tools/linux/platform-tools/adb}"
DEFAULT_IPS=()

SERIAL=""
DO_JAR=1
DO_DAEMON=1
DO_MODULE=0
DO_APP=0
DO_LAUNCHER=0

while [ $# -gt 0 ]; do
  case "$1" in
    --serial|-s) SERIAL="$2"; shift 2 ;;
    --all) DO_JAR=1; DO_DAEMON=1; DO_MODULE=1; DO_APP=1; DO_LAUNCHER=1; shift ;;
    --jar) DO_JAR=1; DO_DAEMON=0; DO_MODULE=0; DO_APP=0; DO_LAUNCHER=0; shift ;;
    --module) DO_MODULE=1; shift ;;
    --app) DO_APP=1; shift ;;
    --launcher) DO_JAR=0; DO_DAEMON=0; DO_LAUNCHER=1; shift ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

pick_device() {
  if [ -n "$SERIAL" ]; then
    "$ADB" connect "$SERIAL" >/dev/null 2>&1 || true
    echo "$SERIAL"
    return
  fi
  local cur
  cur=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
  if [ -n "$cur" ]; then
    echo "$cur"
    return
  fi
  for ip in ${DEFAULT_IPS[@]+"${DEFAULT_IPS[@]}"}; do
    if "$ADB" connect "$ip" 2>/dev/null | grep -q "connected to"; then
      echo "$ip"
      return
    fi
  done
  echo "ERROR: No Tbox ADB device reachable." >&2
  exit 1
}

SERIAL="$(pick_device)"
echo "Target Tbox: $SERIAL"

if [ "$DO_JAR" = "1" ]; then
  JAR="$REPO/carlife-md-api28/build/carlife-md-api28.jar"
  [ -f "$JAR" ] || bash "$REPO/carlife-md-api28/build.sh"
  "$ADB" -s "$SERIAL" push "$JAR" /data/local/tmp/carlife-md-api28.jar.new
  "$ADB" -s "$SERIAL" shell "su -c 'set -e; mkdir -p /data/adb/carlife; cp /data/local/tmp/carlife-md-api28.jar.new /data/adb/carlife/carlife-md-api28.jar.tmp; chmod 0644 /data/adb/carlife/carlife-md-api28.jar.tmp; chown root:root /data/adb/carlife/carlife-md-api28.jar.tmp; mv /data/adb/carlife/carlife-md-api28.jar.tmp /data/adb/carlife/carlife-md-api28.jar; rm -rf /data/adb/carlife/oat /data/local/tmp/carlife-md-api28.jar.new; sha256sum /data/adb/carlife/carlife-md-api28.jar'"
fi

if [ "$DO_DAEMON" = "1" ]; then
  "$ADB" -s "$SERIAL" push "$REPO/magisk-module/carlife-daemon.sh" /data/local/tmp/carlife-daemon.sh.new
  "$ADB" -s "$SERIAL" shell "su -c 'set -e; cp /data/local/tmp/carlife-daemon.sh.new /data/adb/carlife/carlife-daemon.sh.tmp; chmod 0755 /data/adb/carlife/carlife-daemon.sh.tmp; chown root:root /data/adb/carlife/carlife-daemon.sh.tmp; mv /data/adb/carlife/carlife-daemon.sh.tmp /data/adb/carlife/carlife-daemon.sh; rm -f /data/local/tmp/carlife-daemon.sh.new; sha256sum /data/adb/carlife/carlife-daemon.sh'"
fi

if [ "$DO_MODULE" = "1" ]; then
  OVL="$REPO/bt-phone-overlay/build/TboxBtPhoneOverlay.apk"
  [ -f "$OVL" ] || bash "$REPO/bt-phone-overlay/build.sh"
  "$ADB" -s "$SERIAL" push "$REPO/magisk-module/module.prop" /data/local/tmp/tbox_module.prop
  "$ADB" -s "$SERIAL" push "$REPO/magisk-module/system.prop" /data/local/tmp/tbox_system.prop
  "$ADB" -s "$SERIAL" push "$REPO/magisk-module/post-fs-data.sh" /data/local/tmp/tbox_post_fs_data.sh
  "$ADB" -s "$SERIAL" push "$REPO/magisk-module/system/bin/sd-reverse.sh" /data/local/tmp/tbox_sd_reverse.sh
  "$ADB" -s "$SERIAL" push "$REPO/magisk-module/service.d/wifi_adb.sh" /data/local/tmp/tbox_wifi_adb.sh
  "$ADB" -s "$SERIAL" push "$OVL" /data/local/tmp/TboxBtPhoneOverlay.apk
  "$ADB" -s "$SERIAL" push "$REPO/magisk-module/system/etc/bluetooth/interop_database.conf" /data/local/tmp/tbox_interop_database.conf
  "$ADB" -s "$SERIAL" shell "su -c 'set -e
    mkdir -p /data/adb/modules/tbox_carlife/system/bin /data/adb/modules/tbox_carlife/system/vendor/overlay /data/adb/modules/tbox_carlife/system/etc/bluetooth /data/adb/service.d
    install_atomic() {
      src=\"\$1\"; dst=\"\$2\"; mode=\"\$3\"; owner=\"\$4\"; ctx=\"\$5\"
      cp \"\$src\" \"\$dst.tmp\"
      chmod \"\$mode\" \"\$dst.tmp\"
      chown \"\$owner\" \"\$dst.tmp\"
      [ -n \"\$ctx\" ] && chcon \"\$ctx\" \"\$dst.tmp\" 2>/dev/null || true
      mv -f \"\$dst.tmp\" \"\$dst\"
    }
    install_atomic /data/local/tmp/tbox_module.prop /data/adb/modules/tbox_carlife/module.prop 0644 root:root \"\"
    install_atomic /data/local/tmp/tbox_system.prop /data/adb/modules/tbox_carlife/system.prop 0644 root:root \"\"
    install_atomic /data/local/tmp/tbox_post_fs_data.sh /data/adb/modules/tbox_carlife/post-fs-data.sh 0755 root:root \"\"
    install_atomic /data/local/tmp/tbox_sd_reverse.sh /data/adb/modules/tbox_carlife/system/bin/sd-reverse.sh 0755 root:shell u:object_r:system_file:s0
    install_atomic /data/local/tmp/TboxBtPhoneOverlay.apk /data/adb/modules/tbox_carlife/system/vendor/overlay/TboxBtPhoneOverlay.apk 0644 root:root u:object_r:vendor_overlay_file:s0
    install_atomic /data/local/tmp/tbox_wifi_adb.sh /data/adb/service.d/wifi_adb.sh 0755 root:root \"\"
    install_atomic /data/local/tmp/tbox_interop_database.conf /data/adb/modules/tbox_carlife/system/etc/bluetooth/interop_database.conf 0644 root:root u:object_r:system_file:s0
    # Clear Android 9 static RRO idmap cache so updated vendor overlays never mismatch overlays.list CRC
    rm -f /data/resource-cache/overlays.list /data/resource-cache/vendor@overlay@*@idmap /data/resource-cache/.tbox_ovl.sha
    rm -f /data/local/tmp/tbox_module.prop /data/local/tmp/tbox_system.prop /data/local/tmp/tbox_post_fs_data.sh /data/local/tmp/tbox_sd_reverse.sh /data/local/tmp/tbox_wifi_adb.sh /data/local/tmp/TboxBtPhoneOverlay.apk /data/local/tmp/tbox_interop_database.conf
    sync
  '"
  echo "NOTE: Magisk module / RRO overlay changes take effect after reboot."
fi

if [ "$DO_APP" = "1" ]; then
  APK="$REPO/tbox-usb-switch/build/TboxUsbSwitch.apk"
  [ -f "$APK" ] || bash "$REPO/tbox-usb-switch/build.sh"
  "$ADB" -s "$SERIAL" install -r "$APK"
  "$ADB" -s "$SERIAL" shell "sync" || true
fi

if [ "$DO_LAUNCHER" = "1" ]; then
  LAPK="$REPO/tbox-car-launcher/build/TboxCarLauncher.apk"
  NAVOVL="$REPO/tbox-car-launcher/build/TboxNoNavBarOverlay.apk"
  if [ ! -f "$LAPK" ] || [ ! -f "$NAVOVL" ]; then
    bash "$REPO/tbox-car-launcher/build.sh"
  fi
  "$ADB" -s "$SERIAL" push "$LAPK" /data/local/tmp/TboxCarLauncher.apk
  "$ADB" -s "$SERIAL" push "$NAVOVL" /data/local/tmp/TboxNoNavBarOverlay.apk
  "$ADB" -s "$SERIAL" push "$REPO/tbox-car-launcher/privapp-permissions-tboxcarlauncher.xml" /data/local/tmp/privapp-permissions-tboxcarlauncher.xml
  "$ADB" -s "$SERIAL" push "$REPO/magisk-module/service.d/tbox_vd_input.sh" /data/local/tmp/tbox_vd_input.sh
  "$ADB" -s "$SERIAL" push "$REPO/magisk-module/system.prop" /data/local/tmp/tbox_system.prop
  "$ADB" -s "$SERIAL" shell "su -c 'set -e
    mkdir -p /data/adb/modules/tbox_carlife/system/priv-app/TboxCarLauncher /data/adb/modules/tbox_carlife/system/etc/permissions /data/adb/modules/tbox_carlife/system/vendor/overlay /data/adb/service.d
    chmod 0755 /data/adb/modules/tbox_carlife/system/priv-app /data/adb/modules/tbox_carlife/system/priv-app/TboxCarLauncher /data/adb/modules/tbox_carlife/system/etc /data/adb/modules/tbox_carlife/system/etc/permissions /data/adb/modules/tbox_carlife/system/vendor /data/adb/modules/tbox_carlife/system/vendor/overlay
    chown root:root /data/adb/modules/tbox_carlife/system/priv-app /data/adb/modules/tbox_carlife/system/priv-app/TboxCarLauncher /data/adb/modules/tbox_carlife/system/etc /data/adb/modules/tbox_carlife/system/etc/permissions /data/adb/modules/tbox_carlife/system/vendor /data/adb/modules/tbox_carlife/system/vendor/overlay
    install_atomic() {
      src=\"\$1\"; dst=\"\$2\"; mode=\"\$3\"; owner=\"\$4\"; ctx=\"\$5\"
      cp \"\$src\" \"\$dst.tmp\"
      chmod \"\$mode\" \"\$dst.tmp\"
      chown \"\$owner\" \"\$dst.tmp\"
      [ -n \"\$ctx\" ] && chcon \"\$ctx\" \"\$dst.tmp\" 2>/dev/null || true
      mv -f \"\$dst.tmp\" \"\$dst\"
    }
    install_atomic /data/local/tmp/TboxCarLauncher.apk /data/adb/modules/tbox_carlife/system/priv-app/TboxCarLauncher/TboxCarLauncher.apk 0644 root:root u:object_r:system_file:s0
    install_atomic /data/local/tmp/privapp-permissions-tboxcarlauncher.xml /data/adb/modules/tbox_carlife/system/etc/permissions/privapp-permissions-tboxcarlauncher.xml 0644 root:root u:object_r:system_file:s0
    install_atomic /data/local/tmp/tbox_vd_input.sh /data/adb/service.d/tbox_vd_input.sh 0755 root:root \"\"
    install_atomic /data/local/tmp/tbox_system.prop /data/adb/modules/tbox_carlife/system.prop 0644 root:root \"\"
    OVL_DST=/data/adb/modules/tbox_carlife/system/vendor/overlay/TboxNoNavBarOverlay.apk
    if ! cmp -s /data/local/tmp/TboxNoNavBarOverlay.apk \"\$OVL_DST\" 2>/dev/null; then
      install_atomic /data/local/tmp/TboxNoNavBarOverlay.apk \"\$OVL_DST\" 0644 root:root u:object_r:vendor_overlay_file:s0
    fi
    # Always clear Android 9 static RRO idmap cache so AssetManager never hits stale @idmap CRC
    rm -f /data/resource-cache/overlays.list /data/resource-cache/vendor@overlay@*@idmap /data/resource-cache/.tbox_ovl.sha
    setprop persist.suding.hasnavibar false || true
    settings put global force_resizable_activities 1
    settings put global hidden_api_policy_p_apps 1
    settings put global hidden_api_policy_pre_p_apps 1
    cmd package set-home-activity com.tbox.carlauncher/.LauncherActivity || true
    pm disable-user --user 0 com.android.launcher3 || true
    CUR_NLS=\$(settings get secure enabled_notification_listeners)
    NLS_ENTRY=\"com.tbox.carlauncher/com.tbox.carlauncher.MediaNotificationListener\"
    case \"\$CUR_NLS\" in
      *\"\$NLS_ENTRY\"*) ;;
      \"\"|null) settings put secure enabled_notification_listeners \"\$NLS_ENTRY\" ;;
      *) settings put secure enabled_notification_listeners \"\$CUR_NLS:\$NLS_ENTRY\" ;;
    esac
    if pm path com.tbox.carlauncher >/dev/null 2>&1; then
      pm install -r /data/local/tmp/TboxCarLauncher.apk
    fi
    rm -f /data/local/tmp/TboxCarLauncher.apk /data/local/tmp/TboxNoNavBarOverlay.apk /data/local/tmp/privapp-permissions-tboxcarlauncher.xml /data/local/tmp/tbox_vd_input.sh /data/local/tmp/tbox_system.prop
    sync
  '"
  echo "TboxCarLauncher + TboxNoNavBarOverlay deployed. Reboot once to activate RRO overlay / priv-app mount."
fi

"$ADB" -s "$SERIAL" shell "sync" >/dev/null 2>&1 || true

# Cleanly terminate current init-managed carlife-daemon + children so init respawns ONE fresh instance
if [ "$DO_JAR" = "1" ] || [ "$DO_DAEMON" = "1" ]; then
  echo "Respawning init-managed carlife-daemon..."
  PIDS=$("$ADB" -s "$SERIAL" shell "su -c 'ps -ef'" | awk '(/carlife-daemon\.sh/ || /org\.carlife\.hulab\./) && !/su -c/ && !/awk/ {print $2}' | tr '\n' ' ')
  if [ -n "${PIDS// /}" ]; then
    "$ADB" -s "$SERIAL" shell "su -c 'kill -TERM $PIDS 2>/dev/null || true'"
  fi
  sleep 4
  "$ADB" -s "$SERIAL" shell "su -c 'ps -ef | grep -E \"carlife-daemon|org\\.carlife\\.hulab\" | grep -v grep; echo ---; cat /cache/carlife/status'"
fi
