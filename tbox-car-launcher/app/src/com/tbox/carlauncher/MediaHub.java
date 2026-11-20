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
 * 1) Privileged MediaSessionManager (captures com.carsyso.bluetooth A2DP/AVRCP + NetEase/Kuwo/StreamMusic/etc.)
 * 2) MediaNotificationListener fallback (captures com.carsyso.bluetooth track notifications even when HFP is off)
 * 3) AudioManager.dispatchMediaKeyEvent fallback for Prev / Play-Pause / Next
 */
public final class MediaHub implements MediaNotificationListener.Callback {
    private static final String TAG = "TboxMediaHub";

    public static final class MediaState {
        public String packageName = "";
        public String sourceLabel = "蓝牙音乐";
        public String title = "";
        public String artist = "";
        public Bitmap albumArt;
        public boolean playing;
        public long durationMs;
        public long positionMs;
        public long positionUpdateTimeMs;
        public float playbackSpeed = 1f;
        public boolean hasSession;

        public long estimatedPositionMs() {
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
    private final List<MediaController> tracked = new ArrayList<MediaController>();
    private MediaController activeController;

    private final MediaState state = new MediaState();
    private String notifPkg = "";
    private String notifTitle = "";
    private String notifArtist = "";
    private Bitmap notifArt = null;

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
            refreshFromControllers();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            refreshFromControllers();
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
    }

    public void start() {
        if (started) return;
        started = true;
        MediaNotificationListener.setCallback(this);
        if (sessionManager != null) {
            ComponentName nlsComp = new ComponentName(ctx, MediaNotificationListener.class);
            try {
                sessionManager.addOnActiveSessionsChangedListener(sessionsListener, null, handler);
                bindControllers(sessionManager.getActiveSessions(null));
            } catch (SecurityException se) {
                Log.w(TAG, "getActiveSessions(null) denied, trying with NLS component", se);
                try {
                    sessionManager.addOnActiveSessionsChangedListener(sessionsListener, nlsComp, handler);
                    bindControllers(sessionManager.getActiveSessions(nlsComp));
                } catch (Throwable t) {
                    Log.w(TAG, "MediaSessionManager unavailable, relying on NotificationListener + media keys", t);
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
        if (sessionManager != null) {
            try { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Throwable ignored) {}
        }
        clearTracked();
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
                if ("com.android.server.telecom".equals(pkg)) continue;
                tracked.add(mc);
                try { mc.registerCallback(controllerCallback, handler); } catch (Throwable ignored) {}
            }
        }
        refreshFromControllers();
    }

    private void refreshFromControllers() {
        MediaController best = null;
        // 1. Prefer any actively playing controller
        for (MediaController mc : tracked) {
            PlaybackState pb = mc.getPlaybackState();
            if (pb != null && pb.getState() == PlaybackState.STATE_PLAYING) {
                best = mc;
                break;
            }
        }
        // 2. Otherwise prefer one that has metadata title
        if (best == null) {
            for (MediaController mc : tracked) {
                MediaMetadata mm = mc.getMetadata();
                if (mm != null && !TextUtils.isEmpty(mm.getString(MediaMetadata.METADATA_KEY_TITLE))) {
                    best = mc;
                    break;
                }
            }
        }
        // 3. Otherwise first tracked controller
        if (best == null && !tracked.isEmpty()) {
            best = tracked.get(0);
        }

        activeController = best;

        if (best != null) {
            String pkg = best.getPackageName();
            MediaMetadata mm = best.getMetadata();
            PlaybackState pb = best.getPlaybackState();

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

            // Merge with notification metadata if session has empty title (common on BT AVRCP)
            if (TextUtils.isEmpty(title) && !TextUtils.isEmpty(notifTitle) && pkg.equals(notifPkg)) {
                title = notifTitle;
                if (TextUtils.isEmpty(artist)) artist = notifArtist;
                if (art == null) art = notifArt;
            }

            state.hasSession = true;
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
                state.playing = audioManager != null && audioManager.isMusicActive();
                state.positionMs = 0L;
                state.positionUpdateTimeMs = 0L;
            }
        } else if (!TextUtils.isEmpty(notifTitle)) {
            state.hasSession = true;
            state.packageName = notifPkg;
            state.sourceLabel = UiUtil.getAppLabel(ctx, notifPkg);
            state.title = notifTitle;
            state.artist = notifArtist;
            state.albumArt = notifArt;
            state.playing = audioManager != null && audioManager.isMusicActive();
            state.durationMs = 0L;
            state.positionMs = 0L;
        } else {
            state.hasSession = false;
            state.packageName = "";
            state.sourceLabel = "蓝牙音乐";
            state.title = "";
            state.artist = "";
            state.albumArt = null;
            state.playing = audioManager != null && audioManager.isMusicActive();
            state.durationMs = 0L;
            state.positionMs = 0L;
        }

        notifyListener();
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
                if (token != null && activeController == null) {
                    try {
                        MediaController mc = new MediaController(ctx, token);
                        tracked.add(mc);
                        mc.registerCallback(controllerCallback, handler);
                    } catch (Throwable ignored) {}
                }
                refreshFromControllers();
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
                    refreshFromControllers();
                }
            }
        });
    }

    public MediaState getState() {
        return state;
    }

    public void playPause() {
        if (activeController != null) {
            try {
                if (state.playing) {
                    activeController.getTransportControls().pause();
                } else {
                    activeController.getTransportControls().play();
                }
                return;
            } catch (Throwable t) {
                Log.w(TAG, "TransportControls playPause failed, fallback to key", t);
            }
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    public void next() {
        if (activeController != null) {
            try {
                activeController.getTransportControls().skipToNext();
                return;
            } catch (Throwable ignored) {}
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public void previous() {
        if (activeController != null) {
            try {
                activeController.getTransportControls().skipToPrevious();
                return;
            } catch (Throwable ignored) {}
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
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
