#!/system/bin/sh
# Keep TboxCarLauncher's VirtualDisplay & Input Bridge running as uid=1000 (system),
# and suppress the OEM left-edge NavigationBar (SdNavigationBarView) cleanly on boot.
(
  while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
  done
  sleep 2

  # Bounded 1-shot self-healing if Magisk Safe Mode disabled tbox_carlife after a transient glitch:
  # - On a healthy boot with tbox_carlife active, clear the retry stamp.
  # - If disabled while CarLife mode is selected and not yet retried, clear stale RRO cache +
  #   remove disable + set stamp so next ignition restores CarLife without infinite bootloop risk.
  MOD_DISABLE=/data/adb/modules/tbox_carlife/disable
  REENABLE_STAMP=/cache/carlife/.reenable_tried
  if [ ! -f "$MOD_DISABLE" ]; then
    rm -f "$REENABLE_STAMP" >/dev/null 2>&1 || true
  elif [ "$(getprop persist.tbox.carlife 2>/dev/null)" = "1" ] && [ ! -f "$REENABLE_STAMP" ]; then
    rm -f /data/resource-cache/overlays.list /data/resource-cache/vendor@overlay@*@idmap /data/resource-cache/.tbox_ovl.sha /cache/carlife/.tbox_ovl.sha >/dev/null 2>&1 || true
    rm -f "$MOD_DISABLE" >/dev/null 2>&1 || true
    mkdir -p /cache/carlife >/dev/null 2>&1 || true
    : > "$REENABLE_STAMP" 2>/dev/null || true
    sync
  fi

  settings put global force_resizable_activities 1 >/dev/null 2>&1 || true
  settings put global hidden_api_policy_p_apps 1 >/dev/null 2>&1 || true
  settings put global hidden_api_policy_pre_p_apps 1 >/dev/null 2>&1 || true

  # Reinforce OEM left-edge NavigationBar suppression (paired with dynamic TboxNoNavBarOverlay.apk)
  setprop persist.suding.hasnavibar false >/dev/null 2>&1 || true
  resetprop qemu.hw.mainkeys 1 >/dev/null 2>&1 || true
  cmd overlay enable --user 0 com.tbox.overlay.nonavbar >/dev/null 2>&1 || true

  while true; do
    APK="$(pm path com.tbox.carlauncher 2>/dev/null | head -n 1 | sed 's/^package://')"
    if [ -z "$APK" ] || [ ! -f "$APK" ]; then
      APK="/system/priv-app/TboxCarLauncher/TboxCarLauncher.apk"
    fi
    if [ -f "$APK" ]; then
      if ! ps -ef | grep -q "[c]om.tbox.carlauncher.VdInputBridge"; then
        su 1000 -c "CLASSPATH=$APK /system/bin/app_process /system/bin com.tbox.carlauncher.VdInputBridge" >/dev/null 2>&1
      fi
    fi
    sleep 2
  done
) &
