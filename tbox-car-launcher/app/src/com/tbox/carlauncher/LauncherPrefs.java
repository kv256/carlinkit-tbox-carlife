package com.tbox.carlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.UserManager;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class LauncherPrefs {
    private static final String TAG = "TboxLauncherPrefs";
    private static final String PREFS_NAME = "tbox_car_launcher_prefs";
    private static final String KEY_DE_MIGRATED = "_de_migrated_v1";
    private static final String KEY_DOCK_SLOT_PREFIX = "dock_slot_";
    private static final String KEY_AUTO_LAUNCH_PKG = "auto_launch_pkg";
    private static final String KEY_HIDDEN_PKGS = "hidden_pkgs";
    private static final String KEY_APP_DPI_PREFIX = "app_dpi_";
    private static final String KEY_CPU_TEMP_MODE = "cpu_temp_mode";

    public static final int DOCK_SLOT_COUNT = 4;
    public static final int DEFAULT_DPI = 238;
    public static final int CPU_TEMP_OFF = 0;
    public static final int CPU_TEMP_DOCK = 1;
    public static final int CPU_TEMP_NAV = 2;

    private final Context ctx;
    private final SharedPreferences sp;

    public LauncherPrefs(Context ctx) {
        Context appCtx = ctx.getApplicationContext();
        this.ctx = appCtx;
        Context deCtx = appCtx.createDeviceProtectedStorageContext();
        // LauncherActivity is directBootAware="true"; always store prefs in Device Protected (DE)
        // storage so Direct Boot never throws IllegalStateException, while migrating legacy CE
        // prefs once after User 0 unlocks to preserve all user Dock/DPI/auto-launch settings.
        try {
            UserManager um = (UserManager) appCtx.getSystemService(Context.USER_SERVICE);
            if (um != null && um.isUserUnlocked()) {
                SharedPreferences checkDe = deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                if (!checkDe.getBoolean(KEY_DE_MIGRATED, false)) {
                    deCtx.moveSharedPreferencesFrom(appCtx, PREFS_NAME);
                    deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_DE_MIGRATED, true).apply();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "CE->DE prefs migration skipped", t);
        }
        this.sp = deCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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

    /**
     * Returns custom per-app DPI, or 0 for system default (238 dpi).
     * Bilibili Things defaults to 160 dpi (TV/car layout) unless user explicitly overrides it.
     */
    public int getAppDpiOverride(String pkg) {
        if (pkg == null || pkg.isEmpty()) return 0;
        String key = KEY_APP_DPI_PREFIX + pkg;
        if (sp.contains(key)) {
            return sp.getInt(key, 0);
        }
        if ("com.bilibili.bilithings".equals(pkg)) {
            return 160;
        }
        return 0;
    }

    public int getEffectiveAppDpi(String pkg, int defaultDpi) {
        int ov = getAppDpiOverride(pkg);
        if (ov >= 100 && ov <= 420) return ov;
        return defaultDpi > 0 ? defaultDpi : DEFAULT_DPI;
    }

    public void setAppDpiOverride(String pkg, int dpi) {
        if (pkg == null || pkg.isEmpty()) return;
        sp.edit().putInt(KEY_APP_DPI_PREFIX + pkg, dpi <= 0 ? 0 : dpi).apply();
    }

    public int getCpuTempMode() {
        int m = sp.getInt(KEY_CPU_TEMP_MODE, CPU_TEMP_NAV);
        if (m < CPU_TEMP_OFF || m > CPU_TEMP_NAV) return CPU_TEMP_NAV;
        return m;
    }

    public void setCpuTempMode(int mode) {
        if (mode < CPU_TEMP_OFF || mode > CPU_TEMP_NAV) mode = CPU_TEMP_OFF;
        sp.edit().putInt(KEY_CPU_TEMP_MODE, mode).apply();
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
