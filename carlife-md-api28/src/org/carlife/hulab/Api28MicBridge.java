package org.carlife.hulab;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Android 9 (API 28) CarLife head-unit microphone bridge + BT HFP SCO call trigger.
 *
 * Registers an AudioPolicy recorder-injection mix (16 kHz mono PCM16) covering
 * MIC(1), VOICE_RECOGNITION(6), VOICE_COMMUNICATION(7), UNPROCESSED(9) (excluding
 * uid=0 and preset 7 when call routing is BT).
 *
 * Key reliability guarantees:
 *  - Creates a fresh AudioTrack via createAudioTrackSource(mix) in activateMic() and
 *    releases it in deactivateMic(), eliminating stale CBLK_INVALID (0x004) / dead
 *    IAudioTrack stalls across AudioPolicy registrations and standby timeouts.
 *  - Monitors playback head position in pump(); if write()==0 persists with stalled
 *    headPos >= 400 ms, automatically recreates AudioTrack (and AudioPolicy if needed).
 *  - When call routing is BT (captureRootCalls=false), observes sdsdk816 (uid=0) and
 *    VoIP (preset 7) recordings and opens/closes QCOM-BTD HFP SCO via AudioManager so
 *    Toyota Venza wakes its native Bluetooth hands-free phone UI and hardware AEC.
 */
final class Api28MicBridge implements AutoCloseable {
    interface Demand {
        void changed(boolean wanted);
    }

    static final int RATE = 16000;
    private static final int MAX_QUEUE_BYTES = 6400; // 200 ms @ 16 kHz mono PCM16
    private static final long MAX_AGE_MS = 180;
    private static final long STOP_DEBOUNCE_MS = 300;
    private static final int STALL_ZERO_THRESHOLD = 40;
    private static final long STALL_DURATION_MS = 400;
    private static final int[] DEFAULT_CAPTURE_PRESETS = {1, 6, 7, 9};

    private static final class Chunk {
        final long created;
        final byte[] data;
        Chunk(long created, byte[] data) { this.created = created; this.data = data; }
    }

    private final Demand demand;
    private final int[] capturePresets;
    private final boolean captureRootCalls;
    private final ArrayDeque<Chunk> queue = new ArrayDeque<Chunk>();
    private int queuedBytes;
    private long droppedBytes, receivedPackets, ignoredPackets, writtenBytes, writeZeroes;
    private long pcmSamples, lastReport;
    private double pcmEnergy;
    private int pcmPeak;

    private HandlerThread thread;
    private Handler worker;
    private Context runtimeContext;
    private AudioManager manager;
    private AudioManager.AudioRecordingCallback callback;
    private Object policy;
    private Object mix;
    private Class<?> policyType;
    private Class<?> mixType;
    private AudioTrack track;
    private Chunk writing;
    private int writingOffset;

    private int stallZeroStreak;
    private int lastHeadPos;
    private long lastHeadAdvanceMs;
    private int trackRecoveries;
    private int policyRecoveries;

    private volatile boolean closed;
    private volatile boolean recordingWanted;
    private volatile boolean callRecordingActive;
    private volatile String activeRecorderPkg = "";
    private boolean policyRegistered;
    private boolean callbackRegistered;
    private boolean btScoActive;

    private final Runnable stopDebounceTask = new Runnable() {
        public void run() {
            if (!closed && !hasActiveExternalRecorder()) {
                deactivateMic();
            }
        }
    };
    private final Runnable btScoStopDebounceTask = new Runnable() {
        public void run() {
            if (!closed && !hasActiveBtCallRecorder()) {
                deactivateBtSco();
            }
        }
    };
    private final Runnable pumpTask = new Runnable() {
        public void run() { pump(); }
    };

    Api28MicBridge(Demand demand) {
        this(demand, DEFAULT_CAPTURE_PRESETS, true);
    }

    Api28MicBridge(Demand demand, int[] presets) {
        this(demand, presets, true);
    }

    Api28MicBridge(Demand demand, int[] presets, boolean captureRootCalls) {
        if (demand == null) throw new NullPointerException("mic demand");
        this.demand = demand;
        this.capturePresets = (presets == null || presets.length == 0) ? DEFAULT_CAPTURE_PRESETS.clone() : presets.clone();
        this.captureRootCalls = captureRootCalls;
    }

    boolean isCallRecordingActive() {
        return recordingWanted && callRecordingActive;
    }

    boolean isPackageRecording(String pkg) {
        return recordingWanted && pkg != null && pkg.equals(activeRecorderPkg);
    }

    synchronized void start() {
        if (closed || thread != null) return;
        thread = new HandlerThread("carlife-api28-mic", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(new Runnable() {
            public void run() { initialize(); }
        });
    }

    /** Called on USB reader thread when HU sends 0x58001 (16 kHz mono PCM16). */
    void onMic(byte[] pcm, int timestamp) {
        if (closed || pcm == null || pcm.length == 0 || (pcm.length & 1) != 0 || pcm.length > 32000) {
            synchronized (queue) { ignoredPackets++; }
            return;
        }
        long now = SystemClock.uptimeMillis();
        synchronized (queue) {
            receivedPackets++;
            if (!recordingWanted || closed) {
                ignoredPackets++;
                return;
            }
            while (!queue.isEmpty() && now - queue.peekFirst().created > MAX_AGE_MS) {
                Chunk old = queue.removeFirst();
                queuedBytes -= old.data.length;
                droppedBytes += old.data.length;
            }
            int offset = Math.max(0, pcm.length - MAX_QUEUE_BYTES);
            int len = pcm.length - offset;
            droppedBytes += offset;
            while (queuedBytes + len > MAX_QUEUE_BYTES && !queue.isEmpty()) {
                Chunk old = queue.removeFirst();
                queuedBytes -= old.data.length;
                droppedBytes += old.data.length;
            }
            byte[] copy = new byte[len];
            System.arraycopy(pcm, offset, copy, 0, len);
            queue.addLast(new Chunk(now, copy));
            queuedBytes += len;
        }
    }

    private void registerPolicyInternal() throws Exception {
        policyType = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> pbType = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        mixType = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mbType = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Class<?> ruleType = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> rbType = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");

        int matchPreset = ruleType.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null);
        int loopback = mixType.getField("ROUTE_FLAG_LOOP_BACK").getInt(null);

        Object rb = rbType.getConstructor().newInstance();
        for (int preset : capturePresets) {
            AudioAttributes.Builder ab = new AudioAttributes.Builder();
            call(ab, "setInternalCapturePreset", new Class<?>[]{int.class}, Integer.valueOf(preset));
            call(rb, "addRule", new Class<?>[]{AudioAttributes.class, int.class}, ab.build(), Integer.valueOf(matchPreset));
        }
        if (!captureRootCalls) {
            try {
                int matchUid = ruleType.getField("RULE_MATCH_UID").getInt(null);
                call(rb, "excludeMixRule", new Class<?>[]{int.class, Object.class}, Integer.valueOf(matchUid), Integer.valueOf(0));
            } catch (Throwable ignored) {}
        }
        Object rule = call(rb, "build", new Class<?>[0]);
        Object mb = mbType.getConstructor(ruleType).newInstance(rule);
        AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        call(mb, "setFormat", new Class<?>[]{AudioFormat.class}, fmt);
        call(mb, "setRouteFlags", new Class<?>[]{int.class}, Integer.valueOf(loopback));
        mix = call(mb, "build", new Class<?>[0]);

        Object pb = pbType.getConstructor(Context.class).newInstance(runtimeContext);
        call(pb, "setLooper", new Class<?>[]{Looper.class}, worker.getLooper());
        call(pb, "addMix", new Class<?>[]{mixType}, mix);
        policy = call(pb, "build", new Class<?>[0]);

        int rc = ((Integer) call(manager, "registerAudioPolicy", new Class<?>[]{policyType}, policy)).intValue();
        if (rc != 0) throw new IOException("registerAudioPolicy(mic) rc=" + rc);
        policyRegistered = true;
    }

    private void unregisterPolicyInternal() {
        if (manager != null && policyRegistered && policy != null && policyType != null) {
            try {
                try {
                    call(manager, "unregisterAudioPolicy", new Class<?>[]{policyType}, policy);
                } catch (NoSuchMethodException api28) {
                    call(manager, "unregisterAudioPolicyAsync", new Class<?>[]{policyType}, policy);
                    try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            } catch (Throwable ignored) {}
            policyRegistered = false;
        }
        policy = null;
        mix = null;
    }

    private int openTrack() throws Exception {
        closeTrack();
        if (!policyRegistered || policy == null || mix == null || mixType == null) {
            throw new IOException("mic AudioPolicy not registered");
        }
        AudioTrack fresh = (AudioTrack) call(policy, "createAudioTrackSource", new Class<?>[]{mixType}, mix);
        if (fresh == null || fresh.getState() != AudioTrack.STATE_INITIALIZED) {
            if (fresh != null) {
                try { fresh.release(); } catch (RuntimeException ignored) {}
            }
            throw new IOException("mic AudioTrack not initialized");
        }
        fresh.play();
        track = fresh;
        stallZeroStreak = 0;
        lastHeadPos = fresh.getPlaybackHeadPosition();
        lastHeadAdvanceMs = SystemClock.uptimeMillis();
        return fresh.getBufferSizeInFrames();
    }

    private void closeTrack() {
        AudioTrack old = track;
        track = null;
        if (old != null) {
            try { old.stop(); } catch (RuntimeException ignored) {}
            try { old.release(); } catch (RuntimeException ignored) {}
        }
    }

    private void initialize() {
        if (closed) return;
        try {
            runtimeContext = AndroidRuntimeContext.get();
            manager = (AudioManager) runtimeContext.getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) throw new IOException("AudioManager unavailable");

            registerPolicyInternal();
            String addr = String.valueOf(call(mix, "getRegistration", new Class<?>[0]));

            callback = new AudioManager.AudioRecordingCallback() {
                @Override
                public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    if (!closed && Api28MicBridge.this.callback == this) {
                        reconcile(configs);
                    }
                }
            };
            manager.registerAudioRecordingCallback(callback, worker);
            callbackRegistered = true;

            MdMain.logFields("api28_mic_bridge", "state", "ready", "rate", RATE, "channels", 1,
                    "track_mode", "lazy_per_session", "presets", Arrays.toString(capturePresets),
                    "capture_root_calls", captureRootCalls, "address", addr);
            reconcile(manager.getActiveRecordingConfigurations());
        } catch (Throwable t) {
            MdMain.logFields("api28_mic_bridge", "state", "init_failed", "error", describe(t));
            cleanupInternal();
        }
    }

    private int clientUid(AudioRecordingConfiguration c) {
        try {
            return ((Integer) call(c, "getClientUid", new Class<?>[0])).intValue();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private boolean isExternalAppRecorder(AudioRecordingConfiguration c) {
        if (c == null) return false;
        int src = c.getClientAudioSource();
        boolean match = false;
        for (int p : capturePresets) {
            if (src == p) { match = true; break; }
        }
        if (!match) return false;
        int uid = clientUid(c);
        // MdMain's own AudioPolicySources sinks use source=8 (REMOTE_SUBMIX), never in capturePresets.
        // uid=0 is sdsdk816 (Tbox Bluetooth phone daemon), captured iff call audio is routed to USB.
        if (uid == 0) return captureRootCalls;
        return true;
    }

    private boolean isBtCallRecorder(AudioRecordingConfiguration c) {
        if (c == null || captureRootCalls) return false;
        int src = c.getClientAudioSource();
        int uid = clientUid(c);
        // When call=bt: trigger QCOM-BTD HFP SCO for sdsdk816 calls (uid=0, MIC/VOICE_COMM)
        // and VoIP apps using VOICE_COMMUNICATION(7).
        return (uid == 0 && (src == 1 || src == 7)) || src == 7;
    }

    private boolean hasActiveExternalRecorder() {
        if (manager == null) return false;
        try {
            List<AudioRecordingConfiguration> list = manager.getActiveRecordingConfigurations();
            if (list == null) return false;
            for (AudioRecordingConfiguration c : list) {
                if (isExternalAppRecorder(c)) return true;
            }
        } catch (RuntimeException ignored) {}
        return false;
    }

    private boolean hasActiveBtCallRecorder() {
        if (manager == null || captureRootCalls) return false;
        try {
            List<AudioRecordingConfiguration> list = manager.getActiveRecordingConfigurations();
            if (list == null) return false;
            for (AudioRecordingConfiguration c : list) {
                if (isBtCallRecorder(c)) return true;
            }
        } catch (RuntimeException ignored) {}
        return false;
    }

    private void reconcile(List<AudioRecordingConfiguration> configs) {
        if (closed || !policyRegistered || mix == null) return;
        AudioRecordingConfiguration active = null;
        AudioRecordingConfiguration btCall = null;
        boolean hasCall = false;
        if (configs != null) {
            for (AudioRecordingConfiguration c : configs) {
                if (isExternalAppRecorder(c)) {
                    if (active == null) active = c;
                    int u = clientUid(c);
                    if (u == 0 || c.getClientAudioSource() == 7) {
                        hasCall = true;
                        active = c;
                    }
                }
                if (isBtCallRecorder(c)) {
                    if (btCall == null || clientUid(c) == 0) {
                        btCall = c;
                    }
                }
            }
        }

        // 1. USB CarLife mic capture (voice search always; phone calls when call=usb)
        if (active != null) {
            callRecordingActive = hasCall;
            worker.removeCallbacks(stopDebounceTask);
            if (!recordingWanted) {
                String pkg = "";
                int uid = clientUid(active);
                try {
                    pkg = String.valueOf(call(active, "getClientPackageName", new Class<?>[0]));
                } catch (Throwable ignored) {}
                activateMic(uid, active.getClientAudioSource(), pkg, hasCall);
            }
        } else if (recordingWanted) {
            callRecordingActive = false;
            worker.removeCallbacks(stopDebounceTask);
            worker.postDelayed(stopDebounceTask, STOP_DEBOUNCE_MS);
        } else {
            callRecordingActive = false;
        }

        // 2. BT HFP SCO call routing when call=bt
        if (btCall != null) {
            worker.removeCallbacks(btScoStopDebounceTask);
            if (!btScoActive) {
                String pkg = "";
                int uid = clientUid(btCall);
                try {
                    pkg = String.valueOf(call(btCall, "getClientPackageName", new Class<?>[0]));
                } catch (Throwable ignored) {}
                activateBtSco(uid, btCall.getClientAudioSource(), pkg);
            }
        } else if (btScoActive) {
            worker.removeCallbacks(btScoStopDebounceTask);
            worker.postDelayed(btScoStopDebounceTask, STOP_DEBOUNCE_MS);
        }
    }

    private void activateBtSco(int uid, int source, String pkg) {
        if (manager == null || btScoActive) return;
        boolean hfpConnected = false;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isEnabled()) {
                hfpConnected = adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED;
            }
        } catch (Throwable ignored) {}
        if (!hfpConnected) {
            MdMain.logFields("api28_bt_sco", "state", "skipped_hfp_disconnected",
                    "uid", uid, "source", source, "package", pkg);
            return;
        }
        try {
            manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            manager.startBluetoothSco();
            manager.setBluetoothScoOn(true);
            btScoActive = true;
            MdMain.logFields("api28_bt_sco", "state", "sco_start",
                    "uid", uid, "source", source, "package", pkg, "hfp_connected", true);
        } catch (Throwable t) {
            MdMain.logFields("api28_bt_sco", "state", "sco_start_error", "error", describe(t));
        }
    }

    private void deactivateBtSco() {
        if (manager == null || !btScoActive) return;
        btScoActive = false;
        try {
            manager.setBluetoothScoOn(false);
            manager.stopBluetoothSco();
            manager.setMode(AudioManager.MODE_NORMAL);
            MdMain.logFields("api28_bt_sco", "state", "sco_stop");
        } catch (Throwable t) {
            MdMain.logFields("api28_bt_sco", "state", "sco_stop_error", "error", describe(t));
        }
    }

    private void activateMic(int uid, int source, String pkg, boolean isCall) {
        synchronized (queue) {
            queue.clear();
            queuedBytes = 0;
        }
        writing = null;
        writingOffset = 0;
        trackRecoveries = 0;
        policyRecoveries = 0;
        int bufFrames;
        try {
            bufFrames = openTrack();
        } catch (Throwable e) {
            MdMain.logFields("api28_mic_bridge", "state", "track_open_error", "error", describe(e));
            return;
        }
        recordingWanted = true;
        activeRecorderPkg = pkg == null ? "" : pkg;
        MdMain.logFields("api28_mic_bridge", "state", "mic_start", "uid", uid, "source", source,
                "package", pkg, "is_call", isCall, "buffer_frames", bufFrames);
        try { demand.changed(true); } catch (RuntimeException ignored) {}
        worker.removeCallbacks(pumpTask);
        worker.post(pumpTask);
    }

    private void deactivateMic() {
        if (!recordingWanted) return;
        recordingWanted = false;
        callRecordingActive = false;
        activeRecorderPkg = "";
        worker.removeCallbacks(pumpTask);
        synchronized (queue) {
            queue.clear();
            queuedBytes = 0;
        }
        writing = null;
        writingOffset = 0;
        int finalHead = track != null ? safeHeadPos(track) : 0;
        closeTrack();
        MdMain.logFields("api28_mic_bridge", "state", "mic_stop",
                "received_packets", receivedPackets, "written_bytes", writtenBytes,
                "dropped_bytes", droppedBytes, "pcm_peak", pcmPeak,
                "head_pos", finalHead, "track_recoveries", trackRecoveries,
                "policy_recoveries", policyRecoveries);
        try { demand.changed(false); } catch (RuntimeException ignored) {}
    }

    private boolean recoverTrack(String reason) {
        MdMain.logFields("api28_mic_bridge", "state", "mic_track_recover",
                "reason", reason, "attempt", trackRecoveries + 1, "written_bytes", writtenBytes);
        try {
            openTrack();
            return true;
        } catch (Throwable t) {
            MdMain.logFields("api28_mic_bridge", "state", "mic_track_recover_failed",
                    "reason", reason, "error", describe(t));
            return false;
        }
    }

    private boolean recoverPolicy(String reason) {
        MdMain.logFields("api28_mic_bridge", "state", "mic_policy_recover",
                "reason", reason, "attempt", policyRecoveries + 1);
        try {
            closeTrack();
            unregisterPolicyInternal();
            registerPolicyInternal();
            openTrack();
            return true;
        } catch (Throwable t) {
            MdMain.logFields("api28_mic_bridge", "state", "mic_policy_recover_failed",
                    "reason", reason, "error", describe(t));
            return false;
        }
    }

    private static int safeHeadPos(AudioTrack t) {
        try { return t.getPlaybackHeadPosition(); } catch (RuntimeException ignored) { return -1; }
    }

    private void pump() {
        if (closed || !recordingWanted) return;
        long now = SystemClock.uptimeMillis();
        try {
            if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) {
                if (trackRecoveries < 2) {
                    trackRecoveries++;
                    recoverTrack("uninitialized");
                }
            }
            if (track != null && track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                track.play();
            }
            for (int i = 0; i < 6 && track != null; i++) {
                if (writing != null && now - writing.created > MAX_AGE_MS) {
                    synchronized (queue) { droppedBytes += (writing.data.length - writingOffset); }
                    writing = null;
                    writingOffset = 0;
                }
                if (writing == null) {
                    synchronized (queue) {
                        while (!queue.isEmpty() && now - queue.peekFirst().created > MAX_AGE_MS) {
                            Chunk old = queue.removeFirst();
                            queuedBytes -= old.data.length;
                            droppedBytes += old.data.length;
                        }
                        if (!queue.isEmpty()) {
                            writing = queue.removeFirst();
                            queuedBytes -= writing.data.length;
                            writingOffset = 0;
                        }
                    }
                    if (writing == null) break;
                }
                int count = Math.min(1024, writing.data.length - writingOffset);
                int n = track.write(writing.data, writingOffset, count, AudioTrack.WRITE_NON_BLOCKING);
                if (n < 0) {
                    if (trackRecoveries < 2) {
                        trackRecoveries++;
                        if (recoverTrack("write_err_" + n)) continue;
                    }
                    throw new IOException("AudioTrack.write=" + n);
                }
                if (n == 0) {
                    writeZeroes++;
                    stallZeroStreak++;
                    int head = safeHeadPos(track);
                    if (head != lastHeadPos && head >= 0) {
                        lastHeadPos = head;
                        lastHeadAdvanceMs = now;
                        stallZeroStreak = 0;
                    } else if (stallZeroStreak >= STALL_ZERO_THRESHOLD && now - lastHeadAdvanceMs >= STALL_DURATION_MS) {
                        if (trackRecoveries < 2) {
                            trackRecoveries++;
                            recoverTrack("head_stalled_pos_" + head);
                        } else if (policyRecoveries < 1) {
                            policyRecoveries++;
                            recoverPolicy("head_stalled_after_track_retry");
                        }
                    }
                    break;
                }
                stallZeroStreak = 0;
                int head = safeHeadPos(track);
                if (head != lastHeadPos && head >= 0) {
                    lastHeadPos = head;
                    lastHeadAdvanceMs = now;
                }
                for (int k = writingOffset; k + 1 < writingOffset + n; k += 2) {
                    int s = (short) ((writing.data[k] & 0xff) | ((writing.data[k + 1] & 0xff) << 8));
                    int a = Math.abs(s);
                    if (a > pcmPeak) pcmPeak = a;
                    pcmEnergy += (double) s * s;
                    pcmSamples++;
                }
                writingOffset += n;
                writtenBytes += n;
                if (writingOffset >= writing.data.length) {
                    writing = null;
                    writingOffset = 0;
                }
            }
            if (now - lastReport >= 2000) {
                lastReport = now;
                MdMain.logFields("api28_mic_bridge", "state", "active",
                        "received_packets", receivedPackets, "ignored_packets", ignoredPackets,
                        "written_bytes", writtenBytes, "dropped_bytes", droppedBytes,
                        "write_zeroes", writeZeroes, "pcm_peak", pcmPeak,
                        "pcm_rms", pcmSamples == 0 ? 0 : (int) Math.sqrt(pcmEnergy / pcmSamples),
                        "head_pos", track != null ? safeHeadPos(track) : -1,
                        "play_state", track != null ? track.getPlayState() : -1);
            }
        } catch (Throwable t) {
            MdMain.logFields("api28_mic_bridge", "state", "pump_error", "error", describe(t));
        }
        if (!closed && recordingWanted) {
            worker.postDelayed(pumpTask, 5);
        }
    }

    private void cleanupInternal() {
        recordingWanted = false;
        callRecordingActive = false;
        activeRecorderPkg = "";
        synchronized (queue) {
            queue.clear();
            queuedBytes = 0;
        }
        writing = null;
        writingOffset = 0;
        deactivateBtSco();
        if (manager != null && callbackRegistered && callback != null) {
            try { manager.unregisterAudioRecordingCallback(callback); } catch (RuntimeException ignored) {}
            callbackRegistered = false;
        }
        closeTrack();
        unregisterPolicyInternal();
        manager = null;
        runtimeContext = null;
    }

    @Override
    public void close() {
        final Handler h;
        final HandlerThread t;
        synchronized (this) {
            if (closed) return;
            closed = true;
            recordingWanted = false;
            callRecordingActive = false;
            h = worker;
            t = thread;
        }
        if (h == null) return;
        final CountDownLatch done = new CountDownLatch(1);
        h.removeCallbacksAndMessages(null);
        h.post(new Runnable() {
            public void run() {
                try { cleanupInternal(); } finally {
                    done.countDown();
                    if (t != null) t.quitSafely();
                }
            }
        });
        if (Looper.myLooper() != h.getLooper()) {
            try { done.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static Object call(Object target, String name, Class<?>[] sig, Object... args) throws Exception {
        Method m = target.getClass().getMethod(name, sig);
        try { return m.invoke(target, args); }
        catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw new IOException(name + " failed", c);
        }
    }

    private static String describe(Throwable t) {
        Throwable c = t;
        while (c instanceof InvocationTargetException && c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
