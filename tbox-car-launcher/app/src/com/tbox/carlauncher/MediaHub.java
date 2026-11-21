package com.tbox.carlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified media metadata & transport hub:
 * 1) Local Tbox apps via privileged MediaSessionManager (NetEase, Kuwo, StreamMusic, Bilibili, etc.)
 * 2) Phone Bluetooth music & video via direct TechBtMusicClient AIDL to com.carsyso.bluetooth/TechBTService
 *    (works even when ONLY Bluetooth media A2DP/AVRCP is connected and HFP is disconnected!)
 * 3) MediaNotificationListener + AudioManager media-key fallbacks
 */
public final class MediaHub implements MediaNotificationListener.Callback, TechBtMusicClient.Listener {
    private static final String TAG = "TboxMediaHub";

    public static final class MediaState {
        public String packageName = "";
        public String sourceLabel = "蓝牙音乐";
        public String title = "";
        public String artist = "";
        public Bitmap albumArt;
        public boolean playing;
        public boolean isBluetoothSource;
        public boolean btA2dpConnected;
        public long durationMs;
        public long positionMs;
        public long positionUpdateTimeMs;
        public float playbackSpeed = 1f;
        public boolean hasSession;

        public long estimatedPositionMs() {
            if (!hasSession) return 0L;
            if (durationMs <= 0) return Math.max(0, positionMs);
            long pos = positionMs;
            if (playing && positionUpdateTimeMs > 0) {
                long delta = SystemClock.elapsedRealtime() - positionUpdateTimeMs;
                if (delta > 0) {
                    pos += (long) (delta * (playbackSpeed > 0f ? playbackSpeed : 1f));
                }
            }
            if (pos < 0) pos = 0;
            if (pos > durationMs) pos = durationMs;
            return pos;
        }
    }

    public interface Listener {
        void onMediaStateChanged(MediaState state);
    }

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final AudioManager audioManager;
    private final MediaSessionManager sessionManager;
    private final TechBtMusicClient btMusicClient;
    private final List<MediaController> tracked = new ArrayList<MediaController>();
    private MediaController activeController;

    private final MediaState state = new MediaState();
    private TechBtMusicClient.BtMusicSnapshot btSnap = new TechBtMusicClient.BtMusicSnapshot();
    private String notifPkg = "";
    private String notifTitle = "";
    private String notifArtist = "";
    private Bitmap notifArt = null;
    private long localLastUpdateMs = 0L;

    private boolean started = false;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
        new MediaSessionManager.OnActiveSessionsChangedListener() {
            @Override
            public void onActiveSessionsChanged(List<MediaController> controllers) {
                bindControllers(controllers);
            }
        };

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState pb) {
            localLastUpdateMs = System.currentTimeMillis();
            refreshCombinedState();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            localLastUpdateMs = System.currentTimeMillis();
            refreshCombinedState();
        }

        @Override
        public void onSessionDestroyed() {
            pollSessions();
        }
    };

    private final Runnable periodicPoll = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            pollSessions();
            handler.postDelayed(this, 5000L);
        }
    };

    public MediaHub(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.audioManager = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
        this.sessionManager = (MediaSessionManager) this.ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        this.btMusicClient = new TechBtMusicClient(this.ctx, this);
    }

    public void start() {
        if (started) return;
        started = true;
        MediaNotificationListener.setCallback(this);
        btMusicClient.start();
        if (sessionManager != null) {
            ComponentName nlsComp = new ComponentName(ctx, MediaNotificationListener.class);
            try {
                sessionManager.addOnActiveSessionsChangedListener(sessionsListener, null, handler);
                bindControllers(sessionManager.getActiveSessions(null));
            } catch (SecurityException se) {
                try {
                    sessionManager.addOnActiveSessionsChangedListener(sessionsListener, nlsComp, handler);
                    bindControllers(sessionManager.getActiveSessions(nlsComp));
                } catch (Throwable t) {
                    Log.w(TAG, "MediaSessionManager unavailable", t);
                }
            } catch (Throwable t) {
                Log.w(TAG, "MediaSessionManager init error", t);
            }
        }
        handler.postDelayed(periodicPoll, 4000L);
    }

    public void stop() {
        if (!started) return;
        started = false;
        handler.removeCallbacks(periodicPoll);
        MediaNotificationListener.setCallback(null);
        btMusicClient.stop();
        if (sessionManager != null) {
            try { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Throwable ignored) {}
        }
        clearTracked();
    }

    public boolean isBtA2dpConnected() {
        return btSnap != null && btSnap.a2dpConnected;
    }

    private void pollSessions() {
        if (sessionManager == null) return;
        try {
            bindControllers(sessionManager.getActiveSessions(null));
        } catch (SecurityException se) {
            try {
                bindControllers(sessionManager.getActiveSessions(new ComponentName(ctx, MediaNotificationListener.class)));
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private void clearTracked() {
        for (MediaController mc : tracked) {
            try { mc.unregisterCallback(controllerCallback); } catch (Throwable ignored) {}
        }
        tracked.clear();
        activeController = null;
    }

    private void bindControllers(List<MediaController> list) {
        clearTracked();
        if (list != null) {
            for (MediaController mc : list) {
                if (mc == null) continue;
                String pkg = mc.getPackageName();
                if ("com.android.server.telecom".equals(pkg) || "com.carsyso.bluetooth".equals(pkg)) continue;
                tracked.add(mc);
                try { mc.registerCallback(controllerCallback, handler); } catch (Throwable ignored) {}
            }
        }
        refreshCombinedState();
    }

    @Override
    public void onBtMusicChanged(TechBtMusicClient.BtMusicSnapshot snapshot) {
        this.btSnap = snapshot;
        refreshCombinedState();
    }

    private void refreshCombinedState() {
        MediaController localPlaying = null;
        MediaController localRecent = null;

        for (MediaController mc : tracked) {
            PlaybackState pb = mc.getPlaybackState();
            int st = pb != null ? pb.getState() : PlaybackState.STATE_NONE;
            if (st == PlaybackState.STATE_PLAYING) {
                localPlaying = mc;
                break;
            }
            if (localRecent == null && st != PlaybackState.STATE_ERROR) {
                MediaMetadata mm = mc.getMetadata();
                if (mm != null && !TextUtils.isEmpty(mm.getString(MediaMetadata.METADATA_KEY_TITLE))) {
                    localRecent = mc;
                }
            }
        }

        boolean btConnected = btSnap != null && btSnap.a2dpConnected;
        boolean btPlaying = btConnected && btSnap.playing;
        boolean btHasInfo = btConnected && !TextUtils.isEmpty(btSnap.title);

        if (!btConnected && ("com.carsyso.bluetooth".equals(notifPkg) || "com.android.bluetooth".equals(notifPkg))) {
            notifPkg = "";
            notifTitle = "";
            notifArtist = "";
            notifArt = null;
        }

        state.btA2dpConnected = btConnected;

        // Priority:
        // 1. Phone BT music/video actively playing (unless a local box MediaController is also actively playing more recently)
        // 2. Local box MediaController actively playing
        // 3. Phone BT music/video with metadata & connected A2DP (when no local app is playing and BT updated >= local)
        // 4. Local box paused MediaController with metadata
        // 5. Notification fallback / Idle
        if (btPlaying && (localPlaying == null || btSnap.lastUpdateMs >= localLastUpdateMs)) {
            applyBluetoothState();
        } else if (localPlaying != null) {
            applyLocalController(localPlaying);
        } else if (btHasInfo && (localRecent == null || btSnap.lastUpdateMs >= localLastUpdateMs)) {
            applyBluetoothState();
        } else if (localRecent != null) {
            applyLocalController(localRecent);
        } else if (btHasInfo) {
            applyBluetoothState();
        } else if (!TextUtils.isEmpty(notifTitle)) {
            activeController = null;
            state.hasSession = true;
            state.isBluetoothSource = "com.carsyso.bluetooth".equals(notifPkg) || "com.android.bluetooth".equals(notifPkg);
            state.packageName = notifPkg;
            state.sourceLabel = UiUtil.getAppLabel(ctx, notifPkg);
            state.title = notifTitle;
            state.artist = notifArtist;
            state.albumArt = notifArt;
            state.playing = audioManager != null && audioManager.isMusicActive();
            state.durationMs = 0L;
            state.positionMs = 0L;
            state.positionUpdateTimeMs = 0L;
        } else {
            activeController = null;
            state.hasSession = false;
            state.isBluetoothSource = state.btA2dpConnected;
            state.packageName = state.btA2dpConnected ? "com.carsyso.bluetooth" : "";
            state.sourceLabel = "蓝牙音乐";
            state.title = "";
            state.artist = "";
            state.albumArt = null;
            state.playing = false;
            state.durationMs = 0L;
            state.positionMs = 0L;
            state.positionUpdateTimeMs = 0L;
        }

        notifyListener();
    }

    private void applyBluetoothState() {
        activeController = null;
        state.hasSession = true;
        state.isBluetoothSource = true;
        state.packageName = "com.carsyso.bluetooth";
        state.sourceLabel = "蓝牙音乐";
        state.title = !TextUtils.isEmpty(btSnap.title) ? btSnap.title : (btSnap.playing ? "手机蓝牙播放中" : "");
        state.artist = !TextUtils.isEmpty(btSnap.artist) ? btSnap.artist : "手机蓝牙音频";
        state.albumArt = null;
        state.playing = btSnap.playing;
        state.durationMs = Math.max(0L, btSnap.durationMs);
        state.positionMs = Math.max(0L, btSnap.positionMs);
        state.positionUpdateTimeMs = SystemClock.elapsedRealtime();
        state.playbackSpeed = 1f;
    }

    private void applyLocalController(MediaController mc) {
        activeController = mc;
        String pkg = mc.getPackageName();
        MediaMetadata mm = mc.getMetadata();
        PlaybackState pb = mc.getPlaybackState();

        String title = mm != null ? safeStr(mm.getString(MediaMetadata.METADATA_KEY_TITLE)) : "";
        String artist = mm != null ? safeStr(mm.getString(MediaMetadata.METADATA_KEY_ARTIST)) : "";
        if (TextUtils.isEmpty(artist) && mm != null) {
            artist = safeStr(mm.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
        }
        if (TextUtils.isEmpty(artist) && mm != null) {
            artist = safeStr(mm.getString(MediaMetadata.METADATA_KEY_ALBUM));
        }
        Bitmap art = null;
        if (mm != null) {
            art = mm.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = mm.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = mm.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        long duration = mm != null ? mm.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0L;

        state.hasSession = true;
        state.isBluetoothSource = "com.carsyso.bluetooth".equals(pkg) || "com.android.bluetooth".equals(pkg);
        state.packageName = pkg;
        state.sourceLabel = UiUtil.getAppLabel(ctx, pkg);
        state.title = title;
        state.artist = artist;
        state.albumArt = art;
        state.durationMs = Math.max(0L, duration);
        if (pb != null) {
            state.playing = (pb.getState() == PlaybackState.STATE_PLAYING);
            state.positionMs = Math.max(0L, pb.getPosition());
            state.positionUpdateTimeMs = pb.getLastPositionUpdateTime();
            state.playbackSpeed = pb.getPlaybackSpeed();
        } else {
            state.playing = false;
            state.positionMs = 0L;
            state.positionUpdateTimeMs = 0L;
        }
    }

    @Override
    public void onNotificationMediaUpdate(final String pkg, final String title, final String artist,
                                          final Bitmap art, final MediaSession.Token token) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                notifPkg = pkg != null ? pkg : "";
                notifTitle = title != null ? title : "";
                notifArtist = artist != null ? artist : "";
                if (art != null) notifArt = art;
                refreshCombinedState();
            }
        });
    }

    @Override
    public void onNotificationMediaRemoved(final String pkg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (pkg != null && pkg.equals(notifPkg)) {
                    notifTitle = "";
                    notifArtist = "";
                    notifArt = null;
                    refreshCombinedState();
                }
            }
        });
    }

    public MediaState getState() {
        return state;
    }

    public void playPause() {
        if (state.isBluetoothSource || (activeController == null && isBtA2dpConnected())) {
            if (isBtA2dpConnected()) {
                boolean ok = state.playing ? btMusicClient.pause() : btMusicClient.play();
                if (ok) return;
            }
            // Do NOT fall through to global dispatchMediaKey when BT is disconnected
            // (it triggers BtMediaBrowserService's broken fake STATE_PLAYING!)
            if (activeController == null) return;
        }
        if (activeController != null) {
            try {
                if (state.playing) activeController.getTransportControls().pause();
                else activeController.getTransportControls().play();
                return;
            } catch (Throwable ignored) {}
        }
        if (state.hasSession) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    public void next() {
        if (state.isBluetoothSource || (activeController == null && isBtA2dpConnected())) {
            if (isBtA2dpConnected() && btMusicClient.next()) return;
            if (activeController == null) return;
        }
        if (activeController != null) {
            try {
                activeController.getTransportControls().skipToNext();
                return;
            } catch (Throwable ignored) {}
        }
        if (state.hasSession) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
        }
    }

    public void previous() {
        if (state.isBluetoothSource || (activeController == null && isBtA2dpConnected())) {
            if (isBtA2dpConnected() && btMusicClient.previous()) return;
            if (activeController == null) return;
        }
        if (activeController != null) {
            try {
                activeController.getTransportControls().skipToPrevious();
                return;
            } catch (Throwable ignored) {}
        }
        if (state.hasSession) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }
    }

    private void dispatchMediaKey(int keyCode) {
        if (audioManager == null) return;
        long now = SystemClock.uptimeMillis();
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void notifyListener() {
        if (listener != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onMediaStateChanged(state);
                }
            });
        }
    }

    private static String safeStr(String s) {
        return s == null ? "" : s.trim();
    }
}
