package com.tbox.carlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class LauncherPrefs {
    private static final String PREFS_NAME = "tbox_car_launcher_prefs";
    private static final String KEY_DOCK_SLOT_PREFIX = "dock_slot_";
    private static final String KEY_AUTO_LAUNCH_PKG = "auto_launch_pkg";
    private static final String KEY_HIDDEN_PKGS = "hidden_pkgs";

    public static final int DOCK_SLOT_COUNT = 4;

    private final Context ctx;
    private final SharedPreferences sp;

    public LauncherPrefs(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = this.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String[] getDockPackages() {
        String[] defaults = pickDefaultDockPackages();
        String[] res = new String[DOCK_SLOT_COUNT];
        for (int i = 0; i < DOCK_SLOT_COUNT; i++) {
            String saved = sp.getString(KEY_DOCK_SLOT_PREFIX + i, null);
            if (saved != null && isLaunchable(saved)) {
                res[i] = saved;
            } else {
                res[i] = defaults[i];
            }
        }
        return res;
    }

    public void setDockPackage(int slot, String pkg) {
        if (slot < 0 || slot >= DOCK_SLOT_COUNT || pkg == null) return;
        sp.edit().putString(KEY_DOCK_SLOT_PREFIX + slot, pkg).apply();
    }

    public String getAutoLaunchPackage() {
        return sp.getString(KEY_AUTO_LAUNCH_PKG, "com.autonavi.amapauto");
    }

    public void setAutoLaunchPackage(String pkg) {
        sp.edit().putString(KEY_AUTO_LAUNCH_PKG, pkg == null ? "" : pkg).apply();
    }

    public Set<String> getHiddenPackages() {
        Set<String> defaults = new HashSet<String>(Arrays.asList(
            "com.tbox.carlauncher",
            "com.android.launcher3",
            "com.autolauncher.motorcar",
            "com.suding.factorytest"
        ));
        Set<String> saved = sp.getStringSet(KEY_HIDDEN_PKGS, null);
        if (saved != null) {
            defaults.addAll(saved);
        }
        return defaults;
    }

    private String[] pickDefaultDockPackages() {
        String slot0 = firstInstalled("com.autonavi.amapauto", "com.autonavi.minimap", "com.baidu.BaiduMap");
        String slot1 = firstInstalled("com.netease.cloudmusic.iot", "cn.aqzscn.stream_music", "cn.kuwo.kwmusiccar", "com.tencent.qqmusiccar");
        String slot2 = firstInstalled("com.bilibili.tv", "com.bilibili.bilithings", "dev.jdtech.jellyfin", "org.jellyfin.mobile", "com.mxtech.videoplayer.ad");
        String slot3 = firstInstalled("com.tbox.usbswitch", "com.android.settings");
        return new String[]{slot0, slot1, slot2, slot3};
    }

    private String firstInstalled(String... candidates) {
        for (String c : candidates) {
            if (isLaunchable(c)) return c;
        }
        return "com.android.settings";
    }

    private boolean isLaunchable(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getLaunchIntentForPackage(pkg) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
