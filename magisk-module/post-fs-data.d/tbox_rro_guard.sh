#!/system/bin/sh
# Global out-of-module guard installed to /data/adb/post-fs-data.d/tbox_rro_guard.sh.
# Runs on every boot (even if tbox_carlife/disable exists) before zygote starts, preventing
# Android 9 AssetManager.loadStaticRuntimeOverlays() bootloops from stale overlays.list entries.

OVL_LIST=/data/resource-cache/overlays.list
MOD_DIR=/data/adb/modules/tbox_carlife

# Remove any legacy root-owned stamp inside system-owned /data/resource-cache
[ -e /data/resource-cache/.tbox_ovl.sha ] && rm -f /data/resource-cache/.tbox_ovl.sha 2>/dev/null

[ -f "$OVL_LIST" ] || exit 0

MOD_ACTIVE=0
if [ -d "$MOD_DIR" ] && [ ! -e "$MOD_DIR/disable" ] && [ ! -e "$MOD_DIR/remove" ]; then
    MOD_ACTIVE=1
fi

STALE=0
while IFS= read -r line; do
    set -- $line
    apk=$1
    [ -n "$apk" ] || continue
    case "$apk" in
        *TboxNoNavBarOverlay.apk)
            if [ "$MOD_ACTIVE" != "1" ] || [ ! -f "$MOD_DIR/system$apk" ]; then
                STALE=1
                break
            fi
            ;;
    esac
    if [ -f "$apk" ]; then
        continue
    fi
    if [ "$MOD_ACTIVE" = "1" ] && [ -f "$MOD_DIR/system$apk" ]; then
        continue
    fi
    STALE=1
    break
done < "$OVL_LIST"

if [ "$STALE" = "1" ]; then
    rm -f "$OVL_LIST" /data/resource-cache/vendor@overlay@*@idmap /data/resource-cache/.tbox_ovl.sha 2>/dev/null
fi

exit 0
