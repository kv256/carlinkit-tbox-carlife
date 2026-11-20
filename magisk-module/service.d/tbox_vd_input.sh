#!/system/bin/sh
# Keep TboxCarLauncher's VirtualDisplay & Input Bridge running as uid=1000 (system).
# Running as uid=1000 matches sysCtx package "android" for DisplayManager.createVirtualDisplay,
# owns the 1248x702 VirtualDisplay, and holds INJECT_EVENTS + INTERNAL_SYSTEM_WINDOW natively.
(
  while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
  done
  sleep 2

  settings put global force_resizable_activities 1 >/dev/null 2>&1 || true
  settings put global hidden_api_policy_p_apps 1 >/dev/null 2>&1 || true
  settings put global hidden_api_policy_pre_p_apps 1 >/dev/null 2>&1 || true

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
