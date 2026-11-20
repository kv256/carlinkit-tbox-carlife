package org.carlife.hulab;

import android.content.Context;
import android.content.pm.PackageManager;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.TreeSet;

/**
 * Dynamic per-category audio routing for Android 9 + Toyota Venza.
 *
 * Reads persistent system properties (default "usb"; set to "bt" to route via
 * Android Bluetooth / QCOM-BTD instead of CarLife USB):
 *   - persist.tbox.carlife.audio.media : music, video, games, alarms, notifications, assistant
 *   - persist.tbox.carlife.audio.navi  : navigation guidance (CarLife TTS 16 kHz mono)
 *                                        + UID-isolated Chinese navigation apps (Amap/Baidu/Tencent)
 *   - persist.tbox.carlife.audio.call  : voice communication / ringtone downlink + call mic (uid=0 & preset 7)
 *
 * All reads return an immutable Config snapshot so concurrent AudioPolicy /
 * PlaybackActivity / Api28MicBridge threads remain lock-free and consistent.
 */
final class AudioUsage {
    static final String PROP_MEDIA = "persist.tbox.carlife.audio.media";
    static final String PROP_NAVI = "persist.tbox.carlife.audio.navi";
    static final String PROP_CALL = "persist.tbox.carlife.audio.call";
    static final String PROP_EXTRA_NAVI_PKGS = "persist.tbox.carlife.navi.pkgs";

    private static final int[] PURE_MEDIA_USAGES = {0, 1, 4, 5, 7, 8, 9, 10, 11, 13, 14, 16};
    private static final int[] CALL_USAGES = {2, 3, 6};
    private static final int[] NAVI_USAGES = {12};
    private static final int[] EMPTY = {};

    private static final int[] MIC_PRESETS_WITH_CALL = {1, 6, 7, 9};
    private static final int[] MIC_PRESETS_NO_CALL = {1, 6, 9};

    private static final String[] NAVI_PACKAGES = {
        "com.autonavi.amapauto",
        "com.autonavi.minimap",
        "com.baidu.BaiduMap",
        "com.baidu.navi.hd",
        "com.baidu.naviauto",
        "com.tencent.map",
        "com.tencent.wecarnavi"
    };

    private static volatile Method sysPropGet;
    private static volatile boolean sysPropResolved;

    static final class Config {
        final boolean mediaUsb, naviUsb, callUsb;
        final int[] mediaUsages, ttsUsages, micPresets, naviUids;
        final String signature;

        Config(boolean mediaUsb, boolean naviUsb, boolean callUsb, int[] naviUids) {
            this.mediaUsb = mediaUsb;
            this.naviUsb = naviUsb;
            this.callUsb = callUsb;
            this.naviUids = naviUids == null ? EMPTY : naviUids.clone();
            if (mediaUsb && callUsb) {
                int[] merged = new int[PURE_MEDIA_USAGES.length + CALL_USAGES.length];
                System.arraycopy(PURE_MEDIA_USAGES, 0, merged, 0, PURE_MEDIA_USAGES.length);
                System.arraycopy(CALL_USAGES, 0, merged, PURE_MEDIA_USAGES.length, CALL_USAGES.length);
                Arrays.sort(merged);
                this.mediaUsages = merged;
            } else if (mediaUsb) {
                this.mediaUsages = PURE_MEDIA_USAGES.clone();
            } else if (callUsb) {
                this.mediaUsages = CALL_USAGES.clone();
            } else {
                this.mediaUsages = EMPTY;
            }
            this.ttsUsages = naviUsb ? NAVI_USAGES.clone() : EMPTY;
            this.micPresets = callUsb ? MIC_PRESETS_WITH_CALL.clone() : MIC_PRESETS_NO_CALL.clone();
            this.signature = (mediaUsb ? "usb" : "bt") + ":" + (naviUsb ? "usb" : "bt") + ":" + (callUsb ? "usb" : "bt");
        }
    }

    private static volatile Config current = new Config(true, true, true, EMPTY);

    static Config current() {
        return current;
    }

    static String readDesiredSignature() {
        boolean m = isUsbProp(PROP_MEDIA);
        boolean n = isUsbProp(PROP_NAVI);
        boolean c = isUsbProp(PROP_CALL);
        return (m ? "usb" : "bt") + ":" + (n ? "usb" : "bt") + ":" + (c ? "usb" : "bt");
    }

    static Config reload() {
        Config next = new Config(isUsbProp(PROP_MEDIA), isUsbProp(PROP_NAVI), isUsbProp(PROP_CALL), resolveNaviUids());
        current = next;
        return next;
    }

    private static int[] resolveNaviUids() {
        TreeSet<Integer> uids = new TreeSet<Integer>();
        try {
            Context ctx = AndroidRuntimeContext.get();
            PackageManager pm = ctx != null ? ctx.getPackageManager() : null;
            if (pm != null) {
                for (String pkg : NAVI_PACKAGES) {
                    addPackageUid(pm, pkg, uids);
                }
                String extra = getSystemProperty(PROP_EXTRA_NAVI_PKGS, "");
                if (extra != null && extra.trim().length() > 0) {
                    for (String pkg : extra.split(",")) {
                        String p = pkg.trim();
                        if (p.length() > 0) addPackageUid(pm, p, uids);
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (uids.isEmpty()) return EMPTY;
        int[] out = new int[uids.size()];
        int i = 0;
        for (Integer u : uids) out[i++] = u.intValue();
        return out;
    }

    private static void addPackageUid(PackageManager pm, String pkg, TreeSet<Integer> uids) {
        try {
            int uid = pm.getApplicationInfo(pkg, 0).uid;
            if (uid > 1000) uids.add(Integer.valueOf(uid));
        } catch (Throwable ignored) {}
    }

    private static boolean isUsbProp(String key) {
        String val = getSystemProperty(key, "usb");
        return !"bt".equalsIgnoreCase(val == null ? "" : val.trim());
    }

    static String prop(String key, String def) {
        return getSystemProperty(key, def);
    }

    private static String getSystemProperty(String key, String def) {
        try {
            if (!sysPropResolved) {
                sysPropResolved = true;
                Class<?> sp = Class.forName("android.os.SystemProperties");
                sysPropGet = sp.getMethod("get", String.class, String.class);
            }
            Method m = sysPropGet;
            if (m != null) {
                Object v = m.invoke(null, key, def);
                return v == null ? def : String.valueOf(v);
            }
        } catch (Throwable ignored) {}
        return def;
    }

    static boolean hasMediaLane() {
        return current.mediaUsages.length > 0;
    }

    static boolean hasSpeechLane() {
        return current.ttsUsages.length > 0;
    }

    static boolean isCallUsb() {
        return current.callUsb;
    }

    static int[] naviUids() {
        return current.naviUids.clone();
    }

    static boolean isNaviUid(int uid) {
        if (uid <= 0) return false;
        for (int u : current.naviUids) if (u == uid) return true;
        return false;
    }

    static int[] micPresets() {
        return current.micPresets.clone();
    }

    static boolean isMedia(int usage) {
        for (int u : current.mediaUsages) if (u == usage) return true;
        return false;
    }

    static boolean isSpeech(int usage) {
        for (int u : current.ttsUsages) if (u == usage) return true;
        return false;
    }

    static boolean isAssistant(int usage) {
        return false;
    }

    static int[] forLane(CarLifeAudio.Lane lane) {
        Config c = current;
        switch (lane) {
            case MEDIA: return c.mediaUsages;
            case TTS: return c.ttsUsages;
            case VR: return EMPTY;
            default: throw new IllegalArgumentException("unknown audio lane");
        }
    }
}
