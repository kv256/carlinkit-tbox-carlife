package com.tbox.carlauncher;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

/**
 * Listens to media notifications, especially com.carsyso.bluetooth (Tbox A2DP/AVRCP music),
 * which still posts song metadata even when only A2DP is connected and HFP is disconnected.
 */
public class MediaNotificationListener extends NotificationListenerService {
    private static final String TAG = "TboxMediaNLS";
    private static volatile MediaNotificationListener instance;

    public interface Callback {
        void onNotificationMediaUpdate(String pkg, String title, String artist, Bitmap art, MediaSession.Token token);
        void onNotificationMediaRemoved(String pkg);
    }

    private static volatile Callback callback;

    public static void setCallback(Callback cb) {
        callback = cb;
        MediaNotificationListener cur = instance;
        if (cur != null && cb != null) {
            cur.scanActive();
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        Log.i(TAG, "NotificationListener connected");
        scanActive();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (instance == this) instance = null;
    }

    public void scanActive() {
        try {
            StatusBarNotification[] list = getActiveNotifications();
            if (list == null) return;
            for (StatusBarNotification sbn : list) {
                inspectSbn(sbn);
            }
        } catch (Throwable t) {
            Log.w(TAG, "scanActive failed", t);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        inspectSbn(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        Callback cb = callback;
        if (cb != null && isMediaPackage(sbn.getPackageName())) {
            cb.onNotificationMediaRemoved(sbn.getPackageName());
        }
    }

    private void inspectSbn(StatusBarNotification sbn) {
        if (sbn == null) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;

        String pkg = sbn.getPackageName();
        Bundle extras = n.extras;
        MediaSession.Token token = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
        boolean isBt = "com.carsyso.bluetooth".equals(pkg) || "com.android.bluetooth".equals(pkg);
        if (token == null && !isBt && !isMediaPackage(pkg)) {
            return;
        }

        CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence subCs = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        String title = titleCs != null ? titleCs.toString().trim() : "";
        String artist = textCs != null ? textCs.toString().trim() : "";
        if (TextUtils.isEmpty(artist) && subCs != null) {
            artist = subCs.toString().trim();
        }
        if (TextUtils.isEmpty(title) && n.tickerText != null) {
            title = n.tickerText.toString().trim();
        }
        if (TextUtils.isEmpty(title) && token == null) {
            return;
        }
        // Ignore generic non-music BT notifications
        if (isBt && (title.contains("Bluetooth") || title.contains("蓝牙连接") || title.contains("配对"))) {
            if (token == null && TextUtils.isEmpty(artist)) return;
        }

        Bitmap art = null;
        try {
            Object rawLarge = extras.get(Notification.EXTRA_LARGE_ICON);
            if (rawLarge instanceof Bitmap) {
                art = (Bitmap) rawLarge;
            } else if (rawLarge instanceof Icon) {
                Drawable d = ((Icon) rawLarge).loadDrawable(this);
                if (d instanceof BitmapDrawable) art = ((BitmapDrawable) d).getBitmap();
            }
            if (art == null) {
                Icon li = n.getLargeIcon();
                if (li != null) {
                    Drawable d = li.loadDrawable(this);
                    if (d instanceof BitmapDrawable) art = ((BitmapDrawable) d).getBitmap();
                }
            }
        } catch (Throwable ignored) {}

        Callback cb = callback;
        if (cb != null) {
            cb.onNotificationMediaUpdate(pkg, title, artist, art, token);
        }
    }

    private static boolean isMediaPackage(String pkg) {
        if (pkg == null) return false;
        return "com.carsyso.bluetooth".equals(pkg)
            || "com.android.bluetooth".equals(pkg)
            || "com.netease.cloudmusic.iot".equals(pkg)
            || "cn.kuwo.kwmusiccar".equals(pkg)
            || "cn.aqzscn.stream_music".equals(pkg)
            || "com.tencent.qqmusiccar".equals(pkg)
            || "com.tencent.qqmusic".equals(pkg)
            || "com.kugou.android.auto".equals(pkg)
            || pkg.contains("music")
            || pkg.contains("spotify")
            || pkg.contains("jellyfin");
    }
}
