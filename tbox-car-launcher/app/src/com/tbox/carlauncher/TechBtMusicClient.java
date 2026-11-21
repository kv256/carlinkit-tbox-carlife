package com.tbox.carlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

/**
 * Connects to Tbox's Bluetooth music metadata & controls via two public, sanctioned channels:
 * 1) Standard Android MediaBrowser -> com.carsyso.bluetooth / BtMediaBrowserService
 *    (triggers BtMediaBrowserService to create its MediaSession fed directly by BTMusicManager,
 *    giving real-time title, artist, album, duration, position, play state & TransportControls!)
 * 2) com.carsyso.main.core.MainService ("media_report" -> IMainSdkMediaReportServer)
 *    as a secondary live source for BT music info (deviceType == 7) + mock media keys ("key").
 */
public final class TechBtMusicClient {
    private static final String TAG = "TboxTechBtMusic";

    private static final String BT_PKG = "com.carsyso.bluetooth";
    private static final String BT_BROWSER_CLS = "com.carsyso.bluetoothcall.BtMediaBrowserService";

    private static final String MAIN_PKG = "com.carsyso.main";
    private static final String MAIN_ACTION = "com.carsyso.main.core.MainService";
    private static final String DESC_MAIN_SDK = "com.carsyso.main.aidl.sdk.IMainSdkAidlInterface";
    private static final String DESC_MEDIA_SRV = "com.carsyso.main.aidl.sdk.IMainSdkMediaReportServer";
    private static final String DESC_MEDIA_CB = "com.carsyso.main.aidl.sdk.IMainSdkMediaReportCallback";
    private static final String DESC_KEY_SRV = "com.carsyso.main.aidl.sdk.IMainSdkKeyServer";

    public static final class BtMusicSnapshot {
        public boolean a2dpConnected;
        public boolean playing;
        public String title = "";
        public String artist = "";
        public String album = "";
        public long positionMs;
        public long durationMs;
        public long lastUpdateMs;
    }

    public interface Listener {
        void onBtMusicChanged(BtMusicSnapshot snapshot);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BtMusicSnapshot snap = new BtMusicSnapshot();

    private MediaBrowser mediaBrowser;
    private MediaController btController;

    private IBinder mediaReportServer;
    private IBinder keyServer;
    private final Binder mediaReportCb = new MediaReportCallbackBinder();
    private boolean mainBound = false;
    private boolean started = false;

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updateFromBtController();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateFromBtController();
        }

        @Override
        public void onSessionDestroyed() {
            btController = null;
            scheduleReconnect();
        }
    };

    private final MediaBrowser.ConnectionCallback browserCallback = new MediaBrowser.ConnectionCallback() {
        @Override
        public void onConnected() {
            try {
                MediaSession.Token token = mediaBrowser.getSessionToken();
                Log.i(TAG, "BtMediaBrowserService connected! token=" + (token != null));
                if (token != null) {
                    if (btController != null) {
                        try { btController.unregisterCallback(controllerCallback); } catch (Throwable ignored) {}
                    }
                    btController = new MediaController(ctx, token);
                    btController.registerCallback(controllerCallback, handler);
                    updateFromBtController();
                }
            } catch (Throwable t) {
                Log.w(TAG, "BtMediaBrowserService onConnected error", t);
            }
        }

        @Override
        public void onConnectionSuspended() {
            Log.w(TAG, "BtMediaBrowserService connection suspended");
            btController = null;
            scheduleReconnect();
        }

        @Override
        public void onConnectionFailed() {
            Log.w(TAG, "BtMediaBrowserService connection failed");
            btController = null;
            scheduleReconnect();
        }
    };

    private final ServiceConnection mainConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "Connected to com.carsyso.main.core.MainService");
            mediaReportServer = getMainBinder(service, "media_report");
            keyServer = getMainBinder(service, "key");
            if (mediaReportServer != null) {
                registerMediaReportCb();
                inquireMediaInfo();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mediaReportServer = null;
            keyServer = null;
        }
    };

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            connectMediaBrowser();
        }
    };

    public TechBtMusicClient(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (started) return;
        started = true;
        connectMediaBrowser();
        bindMainService();
    }

    public void stop() {
        if (!started) return;
        started = false;
        handler.removeCallbacks(reconnectRunnable);
        if (btController != null) {
            try { btController.unregisterCallback(controllerCallback); } catch (Throwable ignored) {}
            btController = null;
        }
        if (mediaBrowser != null) {
            try { mediaBrowser.disconnect(); } catch (Throwable ignored) {}
            mediaBrowser = null;
        }
        if (mainBound) {
            try { ctx.unbindService(mainConn); } catch (Throwable ignored) {}
            mainBound = false;
        }
        mediaReportServer = null;
        keyServer = null;
    }

    public BtMusicSnapshot getSnapshot() {
        return snap;
    }

    private void connectMediaBrowser() {
        try {
            if (mediaBrowser != null) {
                try { mediaBrowser.disconnect(); } catch (Throwable ignored) {}
            }
            mediaBrowser = new MediaBrowser(
                ctx,
                new ComponentName(BT_PKG, BT_BROWSER_CLS),
                browserCallback,
                null
            );
            mediaBrowser.connect();
            Log.i(TAG, "Connecting MediaBrowser -> " + BT_PKG + "/" + BT_BROWSER_CLS);
        } catch (Throwable t) {
            Log.w(TAG, "MediaBrowser connect failed", t);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!started) return;
        handler.removeCallbacks(reconnectRunnable);
        handler.postDelayed(reconnectRunnable, 4000L);
    }

    private void bindMainService() {
        try {
            Intent i = new Intent(MAIN_ACTION);
            i.setPackage(MAIN_PKG);
            mainBound = ctx.bindService(i, mainConn, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindService(MainService) result=" + mainBound);
        } catch (Throwable t) {
            Log.w(TAG, "bindService(MainService) failed", t);
        }
    }

    private void updateFromBtController() {
        MediaController mc = btController;
        if (mc == null) return;
        MediaMetadata mm = mc.getMetadata();
        PlaybackState pb = mc.getPlaybackState();

        String title = mm != null ? cleanStr(mm.getString(MediaMetadata.METADATA_KEY_TITLE)) : "";
        String artist = mm != null ? cleanStr(mm.getString(MediaMetadata.METADATA_KEY_ARTIST)) : "";
        String album = mm != null ? cleanStr(mm.getString(MediaMetadata.METADATA_KEY_ALBUM)) : "";
        if (TextUtils.isEmpty(artist)) artist = album;
        long dur = mm != null ? Math.max(0L, mm.getLong(MediaMetadata.METADATA_KEY_DURATION)) : 0L;

        boolean playing = (pb != null && pb.getState() == PlaybackState.STATE_PLAYING);
        long pos = (pb != null && pb.getPosition() > 0) ? pb.getPosition() : snap.positionMs;

        if (!TextUtils.isEmpty(title)) snap.title = title;
        if (!TextUtils.isEmpty(artist)) snap.artist = artist;
        if (!TextUtils.isEmpty(album)) snap.album = album;
        if (dur > 0) snap.durationMs = dur;
        snap.positionMs = pos;
        snap.playing = playing;
        if (playing || !TextUtils.isEmpty(snap.title)) {
            snap.a2dpConnected = true;
        }
        snap.lastUpdateMs = System.currentTimeMillis();
        Log.i(TAG, "BT MediaController update: playing=" + playing + " title=" + snap.title
            + " artist=" + snap.artist + " pos=" + snap.positionMs + "/" + snap.durationMs);
        notifyListener();
    }

    public boolean play() {
        if (btController != null) {
            try { btController.getTransportControls().play(); return true; } catch (Throwable ignored) {}
        }
        return sendMockMediaKey(126) || sendMockMediaKey(85);
    }

    public boolean pause() {
        if (btController != null) {
            try { btController.getTransportControls().pause(); return true; } catch (Throwable ignored) {}
        }
        return sendMockMediaKey(127) || sendMockMediaKey(85);
    }

    public boolean next() {
        if (btController != null) {
            try { btController.getTransportControls().skipToNext(); return true; } catch (Throwable ignored) {}
        }
        return sendMockMediaKey(87);
    }

    public boolean previous() {
        if (btController != null) {
            try { btController.getTransportControls().skipToPrevious(); return true; } catch (Throwable ignored) {}
        }
        return sendMockMediaKey(88);
    }

    private IBinder getMainBinder(IBinder mainSdk, String name) {
        if (mainSdk == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_MAIN_SDK);
            data.writeString(name);
            mainSdk.transact(1, data, reply, 0);
            reply.readException();
            return reply.readStrongBinder();
        } catch (Throwable t) {
            Log.w(TAG, "getMainBinder(" + name + ") failed", t);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void registerMediaReportCb() {
        IBinder srv = mediaReportServer;
        if (srv == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_MEDIA_SRV);
            data.writeStrongBinder(mediaReportCb);
            srv.transact(1, data, reply, 0);
            reply.readException();
            Log.i(TAG, "Registered MainService media_report callback");
        } catch (Throwable t) {
            Log.w(TAG, "registerMediaReportCb failed", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void inquireMediaInfo() {
        IBinder srv = mediaReportServer;
        if (srv == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_MEDIA_SRV);
            srv.transact(4, data, reply, 0);
            reply.readException();
        } catch (Throwable ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean sendMockMediaKey(int keyCode) {
        IBinder srv = keyServer;
        if (srv == null || !srv.isBinderAlive()) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_KEY_SRV);
            data.writeString(ctx.getPackageName());
            data.writeInt(keyCode);
            data.writeInt(1); // ACTION_UP
            srv.transact(5, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "sendMockMediaKey(" + keyCode + ") failed", t);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private final class MediaReportCallbackBinder extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 5) { // onCurrentMediaSourceChange(int mediaType, int deviceType, String[] info)
                data.enforceInterface(DESC_MEDIA_CB);
                int mediaType = data.readInt();
                int deviceType = data.readInt();
                String[] info = data.createStringArray();
                if (reply != null) reply.writeNoException();
                Log.i(TAG, "MainService media_report: mediaType=" + mediaType + " deviceType=" + deviceType
                    + " info=" + (info != null ? java.util.Arrays.toString(info) : "null"));
                if (deviceType == 7 && info != null && info.length > 0) {
                    String title = cleanStr(info[0]);
                    String artist = info.length > 1 ? cleanStr(info[1]) : "";
                    String album = info.length > 2 ? cleanStr(info[2]) : "";
                    if (!TextUtils.isEmpty(title)) {
                        snap.title = title;
                        snap.artist = !TextUtils.isEmpty(artist) ? artist : album;
                        snap.album = album;
                        snap.a2dpConnected = true;
                        snap.playing = true;
                        snap.lastUpdateMs = System.currentTimeMillis();
                        notifyListener();
                    }
                }
                return true;
            } else if (code >= 1 && code <= 4) {
                data.enforceInterface(DESC_MEDIA_CB);
                if (code == 3) data.readString();
                else if (code == 4) { data.readString(); data.readString(); }
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private void notifyListener() {
        if (listener != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onBtMusicChanged(snap);
                }
            });
        }
    }

    private static String cleanStr(String s) {
        if (s == null) return "";
        s = s.trim();
        if ("null".equalsIgnoreCase(s) || "unknown".equalsIgnoreCase(s)
            || "未知".equals(s) || "未知歌手".equals(s) || "未知专辑".equals(s)
            || "无".equals(s)) {
            return "";
        }
        return s;
    }
}
