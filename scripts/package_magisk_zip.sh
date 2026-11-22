#!/usr/bin/env bash
# Package all built CarLife + Cockpit Launcher artifacts into a single standard Magisk module ZIP
# (build/tbox_carlife-magisk.zip) suitable for GitHub Releases or one-click install in Magisk App.
#
# Usage:
#   bash scripts/package_magisk_zip.sh            # builds missing artifacts, then packages ZIP
#   bash scripts/package_magisk_zip.sh --rebuild  # forces full build_all.sh before packaging
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
FORCE_REBUILD=0
OUT_ZIP="$REPO/build/tbox_carlife-magisk.zip"

while [ $# -gt 0 ]; do
  case "$1" in
    --rebuild) FORCE_REBUILD=1; shift ;;
    -o|--output) OUT_ZIP="$2"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

JAR="$REPO/carlife-md-api28/build/carlife-md-api28.jar"
BT_OVL="$REPO/bt-phone-overlay/build/TboxBtPhoneOverlay.apk"
SWITCH_APK="$REPO/tbox-usb-switch/build/TboxUsbSwitch.apk"
LAUNCHER_APK="$REPO/tbox-car-launcher/build/TboxCarLauncher.apk"
NAV_OVL="$REPO/tbox-car-launcher/build/TboxNoNavBarOverlay.apk"

if [ "$FORCE_REBUILD" = "1" ] || [ ! -f "$JAR" ] || [ ! -f "$BT_OVL" ] || [ ! -f "$SWITCH_APK" ] || [ ! -f "$LAUNCHER_APK" ] || [ ! -f "$NAV_OVL" ]; then
  echo "=== Building all artifacts first ==="
  bash "$REPO/scripts/build_all.sh"
fi

STAGE="$REPO/build/magisk-zip-stage"
rm -rf "$STAGE"
mkdir -p \
  "$STAGE/META-INF/com/google/android" \
  "$STAGE/system/bin" \
  "$STAGE/system/etc/bluetooth" \
  "$STAGE/system/etc/permissions" \
  "$STAGE/system/vendor/overlay" \
  "$STAGE/system/priv-app/TboxCarLauncher" \
  "$STAGE/carlife" \
  "$STAGE/service.d" \
  "$STAGE/apps"

# Standard Magisk module installer shim (official template)
cat > "$STAGE/META-INF/com/google/android/update-binary" <<'EOF'
#!/sbin/sh
umask 022
ui_print() { echo "$1"; }
require_new_magisk() {
  ui_print "*******************************"
  ui_print " Please install Magisk v20.4+! "
  ui_print "*******************************"
  exit 1
}
OUTFD=$2
ZIPFILE=$3
mount /data 2>/dev/null
[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
. /data/adb/magisk/util_functions.sh
[ $MAGISK_VER_CODE -lt 20400 ] && require_new_magisk
install_module
exit 0
EOF

cat > "$STAGE/META-INF/com/google/android/updater-script" <<'EOF'
#MAGISK
EOF

# Core module files
cp "$REPO/magisk-module/module.prop" "$STAGE/module.prop"
cp "$REPO/magisk-module/system.prop" "$STAGE/system.prop"
cp "$REPO/magisk-module/post-fs-data.sh" "$STAGE/post-fs-data.sh"
cp "$REPO/magisk-module/system/bin/sd-reverse.sh" "$STAGE/system/bin/sd-reverse.sh"
cp "$REPO/magisk-module/system/etc/bluetooth/interop_database.conf" "$STAGE/system/etc/bluetooth/interop_database.conf"
cp "$REPO/tbox-car-launcher/privapp-permissions-tboxcarlauncher.xml" "$STAGE/system/etc/permissions/privapp-permissions-tboxcarlauncher.xml"

# Compiled artifacts
cp "$BT_OVL" "$STAGE/system/vendor/overlay/TboxBtPhoneOverlay.apk"
cp "$NAV_OVL" "$STAGE/system/vendor/overlay/TboxNoNavBarOverlay.apk"
cp "$LAUNCHER_APK" "$STAGE/system/priv-app/TboxCarLauncher/TboxCarLauncher.apk"
cp "$JAR" "$STAGE/carlife/carlife-md-api28.jar"
cp "$REPO/magisk-module/carlife-daemon.sh" "$STAGE/carlife/carlife-daemon.sh"
cp "$REPO/magisk-module/service.d/wifi_adb.sh" "$STAGE/service.d/wifi_adb.sh"
cp "$REPO/magisk-module/service.d/tbox_vd_input.sh" "$STAGE/service.d/tbox_vd_input.sh"
cp "$SWITCH_APK" "$STAGE/apps/TboxUsbSwitch.apk"

# Magisk customize.sh (runs at module install time)
cat > "$STAGE/customize.sh" <<'EOF'
#!/system/bin/sh
ui_print "- Installing carlinkit-tbox-carlife (CarLife AOA + Cockpit Launcher)"

if [ "${API:-0}" -ne 28 ]; then
  ui_print "! Warning: Built and verified for Android 9 (API 28); detected API=${API:-unknown}."
fi

ui_print "- Deploying /data/adb/carlife runtime..."
mkdir -p /data/adb/carlife
cp -f "$MODPATH/carlife/carlife-md-api28.jar" /data/adb/carlife/carlife-md-api28.jar
cp -f "$MODPATH/carlife/carlife-daemon.sh" /data/adb/carlife/carlife-daemon.sh
rm -rf /data/adb/carlife/oat
chmod 0644 /data/adb/carlife/carlife-md-api28.jar
chmod 0755 /data/adb/carlife/carlife-daemon.sh
chown root:root /data/adb/carlife/carlife-md-api28.jar /data/adb/carlife/carlife-daemon.sh

ui_print "- Deploying /data/adb/service.d boot services (Wi-Fi ADB + VdInputBridge)..."
mkdir -p /data/adb/service.d
cp -f "$MODPATH/service.d/wifi_adb.sh" /data/adb/service.d/wifi_adb.sh
cp -f "$MODPATH/service.d/tbox_vd_input.sh" /data/adb/service.d/tbox_vd_input.sh
chmod 0755 /data/adb/service.d/wifi_adb.sh /data/adb/service.d/tbox_vd_input.sh
chown root:root /data/adb/service.d/wifi_adb.sh /data/adb/service.d/tbox_vd_input.sh

# Clean staging copies already copied to /data/adb/
rm -rf "$MODPATH/carlife" "$MODPATH/service.d"

# Permissions for Magisk-mounted system files
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/system/bin/sd-reverse.sh" 0 2000 0755
set_perm "$MODPATH/system/etc/bluetooth/interop_database.conf" 0 0 0644 u:object_r:system_file:s0

ui_print "- Done! Reboot the Tbox to activate the CarPlay-style Launcher and CarLife AOA mode."
ui_print "- After reboot, open 'TboxUsbSwitch' to switch between CarLife / CarPlay / USB ADB."
EOF

# One-time post-boot initialization inside the Magisk module
cat > "$STAGE/service.sh" <<'EOF'
#!/system/bin/sh
MODDIR=${0%/*}
MARKER="$MODDIR/.setup_v110"

(
  i=0
  while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 120 ]; do
    sleep 1
    i=$((i + 1))
  done
  sleep 4

  [ -f "$MARKER" ] && exit 0

  # Install or upgrade TboxUsbSwitch user app
  if [ -f "$MODDIR/apps/TboxUsbSwitch.apk" ]; then
    pm install -r "$MODDIR/apps/TboxUsbSwitch.apk" >/dev/null 2>&1 || true
  fi

  # Configure system flags for 16:9 VirtualDisplay launcher & disable OEM Launcher3
  setprop persist.suding.hasnavibar false >/dev/null 2>&1 || true
  settings put global force_resizable_activities 1 >/dev/null 2>&1 || true
  settings put global hidden_api_policy_p_apps 1 >/dev/null 2>&1 || true
  settings put global hidden_api_policy_pre_p_apps 1 >/dev/null 2>&1 || true
  cmd package set-home-activity com.tbox.carlauncher/.LauncherActivity >/dev/null 2>&1 || true
  pm disable-user --user 0 com.android.launcher3 >/dev/null 2>&1 || true

  # Grant NotificationListenerService access for Music Widget metadata
  CUR_NLS=$(settings get secure enabled_notification_listeners 2>/dev/null)
  NLS_ENTRY="com.tbox.carlauncher/com.tbox.carlauncher.MediaNotificationListener"
  case "$CUR_NLS" in
    *"$NLS_ENTRY"*) ;;
    ""|null) settings put secure enabled_notification_listeners "$NLS_ENTRY" >/dev/null 2>&1 || true ;;
    *) settings put secure enabled_notification_listeners "$CUR_NLS:$NLS_ENTRY" >/dev/null 2>&1 || true ;;
  esac

  : > "$MARKER"
) &
EOF

# Clean uninstall hook when module is removed in Magisk
cat > "$STAGE/uninstall.sh" <<'EOF'
#!/system/bin/sh
rm -f /data/adb/service.d/tbox_vd_input.sh /data/adb/service.d/wifi_adb.sh
rm -rf /data/adb/carlife
setprop persist.tbox.carlife 0 >/dev/null 2>&1 || true
setprop persist.suding.hasnavibar true >/dev/null 2>&1 || true
(
  i=0
  while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 90 ]; do
    sleep 1
    i=$((i + 1))
  done
  pm enable com.android.launcher3 >/dev/null 2>&1 || true
  cmd package set-home-activity com.android.launcher3/.Launcher >/dev/null 2>&1 || true
) &
EOF

chmod 0755 \
  "$STAGE/META-INF/com/google/android/update-binary" \
  "$STAGE/post-fs-data.sh" \
  "$STAGE/customize.sh" \
  "$STAGE/service.sh" \
  "$STAGE/uninstall.sh" \
  "$STAGE/system/bin/sd-reverse.sh" \
  "$STAGE/carlife/carlife-daemon.sh" \
  "$STAGE/service.d/wifi_adb.sh" \
  "$STAGE/service.d/tbox_vd_input.sh"

# Validate shell syntax of all scripts before zipping (sd-reverse.sh uses mksh/bash 'function foo()')
for s in \
  "$STAGE/META-INF/com/google/android/update-binary" \
  "$STAGE/post-fs-data.sh" \
  "$STAGE/customize.sh" \
  "$STAGE/service.sh" \
  "$STAGE/uninstall.sh" \
  "$STAGE/carlife/carlife-daemon.sh" \
  "$STAGE/service.d/wifi_adb.sh" \
  "$STAGE/service.d/tbox_vd_input.sh"; do
  sh -n "$s"
done
bash -n "$STAGE/system/bin/sd-reverse.sh"

mkdir -p "$(dirname "$OUT_ZIP")"
rm -f "$OUT_ZIP"

python3 - "$STAGE" "$OUT_ZIP" <<'PY'
import os, sys, zipfile
stage, out_zip = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
    for root, dirs, files in os.walk(stage):
        dirs.sort()
        files.sort()
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, stage)
            zf.write(full, rel)
PY

rm -rf "$STAGE"
ls -lh "$OUT_ZIP"
sha256sum "$OUT_ZIP"
