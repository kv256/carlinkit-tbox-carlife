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

import java.lang.reflect.Method;

/**
 * Connects to Tbox's Bluetooth music metadata & controls:
 * 1) Metadata (title, artist, album, duration, position):
 *    - Standard Android MediaBrowser -> com.carsyso.bluetooth / BtMediaBrowserService
 *    - Secondary: com.carsyso.main.core.MainService ("media_report")
 * 2) Authoritative A2DP connection/play state + transport controls (Play/Pause/Next/Prev):
 *    - Via tbox_vd_bridge (VdInputBridge) monitoring sdsdk816 AT state ([MU0..4]/[MY]/[MA]/[MB])
 *      and sending AT#MS/AT#MB/AT#MD/AT#ME to /dev/goc_serial.
 *    - This solves BOTH OEM bugs in A2DP-only (HFP=0) phone-music mode:
 *      (a) TechBTMusicManager refuses play/pause/next/prev when getHfpConnectState()!=1;
 *      (b) BtMediaBrowserService never clears stale track metadata on phone disconnect and
 *          unconditionally fakes STATE_PLAYING when TransportControls.play() is called.
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
    private IBinder vdBridge;
    private final Binder mediaReportCb = new MediaReportCallbackBinder();
    private boolean mainBound = false;
    private boolean started = false;

    // Authoritative A2DP state from VdInputBridge (-1=unknown, 0=disconnected, 1=paused, 2=playing)
    private int bridgeBtState = VdInputBridge.BT_STATE_UNKNOWN;
    private String lastTrackKey = "";

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            pollBridgeBtState();
            updateFromBtController();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            pollBridgeBtState();
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
                    pollBridgeBtState();
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
            if (mediaReportServer != null) {
                registerMediaReportCb();
                inquireMediaInfo();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mediaReportServer = null;
        }
    };

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            connectMediaBrowser();
        }
    };

    private final Runnable btStatePoller = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            pollBridgeBtState();
            handler.postDelayed(this, 1200L);
        }
    };

    public TechBtMusicClient(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (started) return;
        started = true;
        pollBridgeBtState();
        connectMediaBrowser();
        bindMainService();
        handler.postDelayed(btStatePoller, 1200L);
    }

    public void stop() {
        if (!started) return;
        started = false;
        handler.removeCallbacks(reconnectRunnable);
        handler.removeCallbacks(btStatePoller);
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
        vdBridge = null;
    }

    public BtMusicSnapshot getSnapshot() {
        return snap;
    }

    private IBinder getVdBridge() {
        if (vdBridge != null && vdBridge.isBinderAlive()) return vdBridge;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method get = sm.getDeclaredMethod("getService", String.class);
            vdBridge = (IBinder) get.invoke(null, VdInputBridge.SERVICE_NAME);
        } catch (Throwable ignored) {
            vdBridge = null;
        }
        return vdBridge;
    }

    private void pollBridgeBtState() {
        IBinder b = getVdBridge();
        if (b == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            b.transact(VdInputBridge.TX_BT_STATE, data, reply, 0);
            reply.readException();
            int st = reply.readInt();
            reply.readLong(); // lastEventMs
            String title = cleanStr(reply.readString());
            String artist = cleanStr(reply.readString());
            String album = cleanStr(reply.readString());
            long posMs = reply.readLong();
            long durMs = reply.readLong();
            applyBridgeBtState(st, title, artist, album, posMs, durMs);
        } catch (Throwable ignored) {
            vdBridge = null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void applyBridgeBtState(int st, String bTitle, String bArtist, String bAlbum, long bPosMs, long bDurMs) {
        int prev = bridgeBtState;
        bridgeBtState = st;
        if (st == VdInputBridge.BT_STATE_DISCONNECTED) {
            boolean hadAnything = snap.a2dpConnected || snap.playing
                || !TextUtils.isEmpty(snap.title) || snap.durationMs > 0 || snap.positionMs > 0;
            clearSnapshot();
            if (hadAnything || prev != st) {
                Log.i(TAG, "BT A2DP disconnected -> cleared stale track info");
                notifyListener();
            }
        } else if (st == VdInputBridge.BT_STATE_PAUSED || st == VdInputBridge.BT_STATE_PLAYING) {
            boolean newPlaying = (st == VdInputBridge.BT_STATE_PLAYING);
            boolean changed = (!snap.a2dpConnected) || (snap.playing != newPlaying);
            snap.a2dpConnected = true;
            snap.playing = newPlaying;

            if (!TextUtils.isEmpty(bTitle) && !bTitle.equals(snap.title)) {
                snap.title = bTitle;
                snap.artist = bArtist;
                snap.album = bAlbum;
                lastTrackKey = bTitle + "|" + bArtist;
                changed = true;
            } else if (TextUtils.isEmpty(snap.artist) && !TextUtils.isEmpty(bArtist)) {
                snap.artist = bArtist;
                changed = true;
            }
            if (bDurMs > 0 && bDurMs != snap.durationMs) {
                snap.durationMs = bDurMs;
                changed = true;
            }
            if (bPosMs >= 0 && (newPlaying || snap.positionMs == 0L || Math.abs(bPosMs - snap.positionMs) > 2500L)) {
                if (bPosMs != snap.positionMs) {
                    snap.positionMs = bPosMs;
                    changed = true;
                }
            }

            if (prev <= VdInputBridge.BT_STATE_DISCONNECTED) {
                snap.lastUpdateMs = System.currentTimeMillis();
                updateFromBtController();
                inquireMediaInfo();
                notifyListener();
            } else if (changed) {
                snap.lastUpdateMs = System.currentTimeMillis();
                notifyListener();
            }
        }
    }

    private void clearSnapshot() {
        snap.a2dpConnected = false;
        snap.playing = false;
        snap.title = "";
        snap.artist = "";
        snap.album = "";
        snap.positionMs = 0L;
        snap.durationMs = 0L;
        snap.lastUpdateMs = System.currentTimeMillis();
        lastTrackKey = "";
    }

    private boolean sendBridgeBtCmd(int cmd) {
        IBinder b = getVdBridge();
        if (b == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            data.writeInt(cmd);
            b.transact(VdInputBridge.TX_BT_CMD, data, reply, 0);
            reply.readException();
            return reply.readInt() == 1;
        } catch (Throwable t) {
            Log.w(TAG, "sendBridgeBtCmd(" + cmd + ") failed", t);
            vdBridge = null;
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
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
        // Ignore stale BtMediaBrowserService metadata/state whenever phone A2DP is disconnected!
        if (bridgeBtState == VdInputBridge.BT_STATE_DISCONNECTED) {
            if (snap.a2dpConnected || snap.playing || !TextUtils.isEmpty(snap.title)) {
                clearSnapshot();
                notifyListener();
            }
            return;
        }

        MediaController mc = btController;
        if (mc == null) return;
        MediaMetadata mm = mc.getMetadata();
        PlaybackState pb = mc.getPlaybackState();

        String title = mm != null ? cleanStr(mm.getString(MediaMetadata.METADATA_KEY_TITLE)) : "";
        String artist = mm != null ? cleanStr(mm.getString(MediaMetadata.METADATA_KEY_ARTIST)) : "";
        String album = mm != null ? cleanStr(mm.getString(MediaMetadata.METADATA_KEY_ALBUM)) : "";
        if (TextUtils.isEmpty(artist)) artist = album;
        long dur = mm != null ? Math.max(0L, mm.getLong(MediaMetadata.METADATA_KEY_DURATION)) : 0L;

        // If bridge hasn't reported A2DP state yet (unknown), don't assume connected from stale paused metadata alone
        boolean controllerPlaying = (pb != null && pb.getState() == PlaybackState.STATE_PLAYING);
        if (bridgeBtState == VdInputBridge.BT_STATE_UNKNOWN) {
            if (!controllerPlaying) {
                return;
            }
            snap.a2dpConnected = true;
            snap.playing = true;
        } else {
            snap.a2dpConnected = true;
            snap.playing = (bridgeBtState == VdInputBridge.BT_STATE_PLAYING);
        }

        String trackKey = title + "|" + artist;
        boolean trackChanged = !TextUtils.isEmpty(title) && !trackKey.equals(lastTrackKey);
        if (!TextUtils.isEmpty(title)) {
            snap.title = title;
            lastTrackKey = trackKey;
        }
        if (!TextUtils.isEmpty(artist)) {
            snap.artist = artist;
        } else if (trackChanged) {
            snap.artist = "";
        }
        if (!TextUtils.isEmpty(album)) {
            snap.album = album;
        } else if (trackChanged) {
            snap.album = "";
        }
        if (dur > 0) {
            snap.durationMs = dur;
        } else if (trackChanged) {
            snap.durationMs = 0L;
        }

        if (pb != null && pb.getPosition() >= 0) {
            if (snap.playing || trackChanged || snap.positionMs == 0L) {
                snap.positionMs = pb.getPosition();
            }
        }
        snap.lastUpdateMs = System.currentTimeMillis();
        Log.i(TAG, "BT update: a2dp=" + snap.a2dpConnected + " playing=" + snap.playing
            + " title=" + snap.title + " artist=" + snap.artist
            + " pos=" + snap.positionMs + "/" + snap.durationMs);
        notifyListener();
    }

    public boolean play() {
        pollBridgeBtState();
        if (!snap.a2dpConnected || bridgeBtState == VdInputBridge.BT_STATE_DISCONNECTED) {
            return false;
        }
        return sendBridgeBtCmd(VdInputBridge.BT_CMD_PLAY);
    }

    public boolean pause() {
        pollBridgeBtState();
        if (!snap.a2dpConnected || bridgeBtState == VdInputBridge.BT_STATE_DISCONNECTED) {
            return false;
        }
        return sendBridgeBtCmd(VdInputBridge.BT_CMD_PAUSE);
    }

    public boolean next() {
        pollBridgeBtState();
        if (!snap.a2dpConnected || bridgeBtState == VdInputBridge.BT_STATE_DISCONNECTED) {
            return false;
        }
        return sendBridgeBtCmd(VdInputBridge.BT_CMD_NEXT);
    }

    public boolean previous() {
        pollBridgeBtState();
        if (!snap.a2dpConnected || bridgeBtState == VdInputBridge.BT_STATE_DISCONNECTED) {
            return false;
        }
        return sendBridgeBtCmd(VdInputBridge.BT_CMD_PREV);
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
                if (bridgeBtState == VdInputBridge.BT_STATE_DISCONNECTED) {
                    return true;
                }
                if (deviceType == 7 && info != null && info.length > 0 && snap.a2dpConnected) {
                    String title = cleanStr(info[0]);
                    String artist = info.length > 1 ? cleanStr(info[1]) : "";
                    String album = info.length > 2 ? cleanStr(info[2]) : "";
                    if (!TextUtils.isEmpty(title)) {
                        snap.title = title;
                        snap.artist = !TextUtils.isEmpty(artist) ? artist : album;
                        snap.album = album;
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
