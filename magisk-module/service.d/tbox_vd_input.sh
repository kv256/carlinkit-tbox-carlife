#!/system/bin/sh
# Keep TboxCarLauncher's VirtualDisplay & Input Bridge running as uid=1000 (system),
# and suppress the OEM left-edge NavigationBar (SdNavigationBarView) cleanly on boot.
(
  while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
  done
  sleep 2

  settings put global force_resizable_activities 1 >/dev/null 2>&1 || true
  settings put global hidden_api_policy_p_apps 1 >/dev/null 2>&1 || true
  settings put global hidden_api_policy_pre_p_apps 1 >/dev/null 2>&1 || true

  # Reinforce OEM left-edge NavigationBar suppression (paired with TboxNoNavBarOverlay.apk)
  setprop persist.suding.hasnavibar false >/dev/null 2>&1 || true
  resetprop qemu.hw.mainkeys 1 >/dev/null 2>&1 || true

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
