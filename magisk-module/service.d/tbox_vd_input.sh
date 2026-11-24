#!/system/bin/sh
# Keep TboxCarLauncher's VirtualDisplay & Input Bridge running as uid=1000 (system),
# and suppress the OEM left-edge NavigationBar (SdNavigationBarView) cleanly on boot.
(
  while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
  done
  STATE=/cache/carlife
  SLOG=$STATE/logs/service.log
  mkdir -p "$STATE/logs" >/dev/null 2>&1 || true
  slog() { echo "$(date '+%m-%d %H:%M:%S' 2>/dev/null) $*" >> "$SLOG" 2>/dev/null; }
  MOD_DIR=/data/adb/modules/tbox_carlife
  MOD_DISABLE=$MOD_DIR/disable
  REENABLE_STAMP=$STATE/.reenable_tried
  # Whether Magisk mounted the module for THIS boot; decided before the re-enable below can
  # remove "disable" (that only takes effect on the next boot).
  MOD_MOUNTED=0
  [ -d "$MOD_DIR" ] && [ ! -f "$MOD_DISABLE" ] && [ ! -f "$MOD_DIR/remove" ] && MOD_MOUNTED=1

  # The boot completed: consume the marker from /data/adb/post-fs-data.d/tbox_rro_guard.sh.
  BOOT_FAILED=0
  [ -f "$STATE/.boot_failed" ] && BOOT_FAILED=1
  rm -f "$STATE/.boot_pending" "$STATE/.boot_failed" >/dev/null 2>&1 || true

  # Bounded one-shot self-healing: undo a "disable" only when the previous boot did not complete
  # (bootloop / safe mode), never one the user set from the Magisk app on a boot that completed.
  # The stamp is cleared on the next boot with the module active, so a module that keeps
  # bootlooping is re-enabled at most once.
  if [ ! -f "$MOD_DISABLE" ]; then
    rm -f "$REENABLE_STAMP" >/dev/null 2>&1 || true
  elif [ "$BOOT_FAILED" = "1" ] && [ "$(getprop persist.tbox.carlife 2>/dev/null)" = "1" ] && [ ! -f "$REENABLE_STAMP" ]; then
    rm -f /data/resource-cache/overlays.list /data/resource-cache/vendor@overlay@*@idmap /data/resource-cache/.tbox_ovl.sha /cache/carlife/.tbox_ovl.sha >/dev/null 2>&1 || true
    rm -f "$MOD_DISABLE" >/dev/null 2>&1 || true
    : > "$REENABLE_STAMP" 2>/dev/null || true
    sync
    slog "MODULE_REENABLED after an incomplete boot (one-shot)"
  fi

  # MainAiBox (com.carsyso.main, CarPlay-only middleware) is disabled -- not removed, so its data
  # survives -- while CarLife is the active mode, and enabled again otherwise (CarPlay / USB ADB
  # mode, module disabled). Mode switches always reboot, so checking once per boot is enough.
  # The stamp records that we disabled it, so a package disabled by someone else is left alone.
  MAIN_PKG=com.carsyso.main
  MAIN_STAMP=$STATE/.mainaibox_disabled
  CARLIFE_ACTIVE=0
  if [ "$(getprop persist.tbox.carlife 2>/dev/null)" = "1" ] &&
     [ "$(getprop persist.sdcarplay.reverse.disable 2>/dev/null)" != "1" ] &&
     [ "$MOD_MOUNTED" = "1" ]; then
    CARLIFE_ACTIVE=1
  fi
  if pm path "$MAIN_PKG" >/dev/null 2>&1; then
    if [ "$CARLIFE_ACTIVE" = "1" ]; then
      if ! pm list packages -d 2>/dev/null | grep -qx "package:$MAIN_PKG"; then
        pm disable-user --user 0 "$MAIN_PKG" >/dev/null 2>&1 && : > "$MAIN_STAMP" && slog "MAINAIBOX_DISABLED"
      fi
    elif [ -f "$MAIN_STAMP" ]; then
      pm enable "$MAIN_PKG" >/dev/null 2>&1 && rm -f "$MAIN_STAMP" && slog "MAINAIBOX_ENABLED"
    fi
  fi

  sleep 2

  settings put global force_resizable_activities 1 >/dev/null 2>&1 || true
  settings put global hidden_api_policy_p_apps 1 >/dev/null 2>&1 || true
  settings put global hidden_api_policy_pre_p_apps 1 >/dev/null 2>&1 || true

  # Reinforce OEM left-edge NavigationBar suppression (paired with dynamic TboxNoNavBarOverlay.apk)
  setprop persist.suding.hasnavibar false >/dev/null 2>&1 || true
  resetprop qemu.hw.mainkeys 1 >/dev/null 2>&1 || true
  cmd overlay enable --user 0 com.tbox.overlay.nonavbar >/dev/null 2>&1 || true

  # HOME fallback: deploy disables com.android.launcher3, so if TboxCarLauncher is missing,
  # disabled or crash-looping there would be no usable home screen without ADB. Watch it for 60 s
  # after boot; if unhealthy, enable launcher3 and make it HOME (a later deploy --launcher
  # restores TboxCarLauncher as HOME).
  (
    L3=com.android.launcher3
    CL=com.tbox.carlauncher
    # Observe the whole window before looking at launcher3: on a fresh ZIP install, service.sh
    # disables launcher3 only ~4 s after boot_completed, i.e. after this subshell has started.
    absent=0 restarts=0 last=""
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
      sleep 3
      pid=$(pidof "$CL" 2>/dev/null)
      if [ -z "$pid" ]; then
        absent=$((absent + 1))
      elif [ -n "$last" ] && [ "$pid" != "$last" ]; then
        restarts=$((restarts + 1))
      fi
      [ -n "$pid" ] && last=$pid
    done
    pm list packages -d 2>/dev/null | grep -qx "package:$L3" || exit 0
    reason=""
    if ! pm path "$CL" >/dev/null 2>&1; then
      reason="not_installed"
    elif pm list packages -d 2>/dev/null | grep -qx "package:$CL"; then
      reason="disabled"
    elif [ "$absent" -ge 10 ] || [ "$restarts" -ge 3 ]; then
      reason="unhealthy absent=$absent/20 restarts=$restarts"
    fi
    [ -n "$reason" ] || exit 0
    pm enable "$L3" >/dev/null 2>&1 || true
    cmd package set-home-activity "$L3/.Launcher" >/dev/null 2>&1 || true
    am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
    slog "HOME_FALLBACK launcher3 enabled: TboxCarLauncher $reason"
  ) &

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
