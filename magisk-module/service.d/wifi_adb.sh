#!/system/bin/sh
# Always enable Wi-Fi ADB on TCP port 5555 on every boot (coexists with USB ADB & CarPlay)
LOG=/cache/wifi_adb.log
(
  i=0
  while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 60 ]; do
    sleep 1
    i=$((i + 1))
  done
  # Let sd-reverse.sh / USB gadget settle first
  sleep 5
  setprop service.adb.tcp.port 5555
  stop adbd
  sleep 1
  start adbd
  echo "$(date): wifi_adb enabled on TCP 5555 (reverse.disable=$(getprop persist.sdcarplay.reverse.disable) usb=$(getprop sys.usb.config) adbd=$(getprop init.svc.adbd))" >> "$LOG"
) &
