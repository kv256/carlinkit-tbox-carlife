package org.carlife.hulab;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Coordinates QCOM-BTD A2DP auto-connect priority with CarLife USB audio routing.
 *
 * Toyota Venza keeps HFP (Phone) stably connected to QCOM-BTD while CarLife USB is active,
 * but disconnects A2DP (Media) ~200 ms after connect. Android's BluetoothPhonePolicy retries
 * A2DP every 6 s whenever bluetooth_a2dp_sink_priority_<MAC> >= PRIORITY_ON (100), causing
 * repeated BT disconnect notices, 49k-frame AudioFlinger track tear-downs, and audioserver
 * TimeCheck deadlocks.
 *
 * Policy:
 *  - When media=usb && navi=usb: keep bluetooth_a2dp_sink_priority_<MAC>=0 (PRIORITY_OFF)
 *    so HFP stays rock-solid without A2DP retry loops, and do NOT churn FOR_MEDIA.
 *  - When media=bt || navi=bt: restore priority=100 (PRIORITY_ON) and clear FOR_MEDIA=10->0
 *    once, guarded by an anti-flap circuit breaker if the HU rejects A2DP twice in <4 s.
 */
final class BtAudioPolicy {
    private static final long SHORT_DROP_WINDOW_MS = 4000L;
    private static final long FLAP_RESET_MS = 30000L;
    private static final long REENFORCE_COOLDOWN_MS = 15000L;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "carlife-bt-policy");
            t.setDaemon(true);
            return t;
        }
    });

    private static final Object LOCK = new Object();
    private static String lastRoutingSig = "";
    private static boolean flapSuppressed;
    private static int lastA2dpState = -1;
    private static long a2dpConnectedSinceMs;
    private static int shortDropCount;
    private static long lastShortDropMs;
    private static long lastReenforceMs;

    private BtAudioPolicy() {}

    static boolean needsBtA2dp(AudioUsage.Config cfg) {
        return cfg != null && (!cfg.mediaUsb || !cfg.naviUsb);
    }

    static void sync(final AudioUsage.Config cfg) {
        if (cfg == null) return;
        final boolean wantA2dp = needsBtA2dp(cfg);
        final boolean resetFlap;
        synchronized (LOCK) {
            resetFlap = !cfg.signature.equals(lastRoutingSig);
            if (resetFlap) {
                lastRoutingSig = cfg.signature;
                flapSuppressed = false;
                shortDropCount = 0;
                lastShortDropMs = 0;
            }
            if (!wantA2dp) {
                lastReenforceMs = SystemClock.uptimeMillis();
            }
        }
        final boolean allowA2dp;
        synchronized (LOCK) {
            allowA2dp = wantA2dp && !flapSuppressed;
        }
        WORKER.execute(new Runnable() {
            public void run() {
                applyA2dpPriorities(allowA2dp, resetFlap ? "routing_sync" : "session_sync");
                if (allowA2dp) {
                    MdMain.Session.clearForceNoBtA2dp();
                }
            }
        });
    }

    static void poll(AudioUsage.Config cfg) {
        if (cfg == null) return;
        BluetoothAdapter adapter;
        try {
            adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return;
        } catch (Throwable ignored) {
            return;
        }
        int state;
        try {
            state = adapter.getProfileConnectionState(BluetoothProfile.A2DP);
        } catch (Throwable ignored) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean wantA2dp = needsBtA2dp(cfg);
        boolean triggerSuppress = false;
        String suppressReason = null;

        synchronized (LOCK) {
            if (state == BluetoothProfile.STATE_CONNECTED && lastA2dpState != BluetoothProfile.STATE_CONNECTED) {
                a2dpConnectedSinceMs = now;
                if (!wantA2dp && now - lastReenforceMs >= REENFORCE_COOLDOWN_MS) {
                    lastReenforceMs = now;
                    triggerSuppress = true;
                    suppressReason = "usb_mode_reenforce";
                }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && lastA2dpState == BluetoothProfile.STATE_CONNECTED) {
                long heldMs = a2dpConnectedSinceMs > 0 ? (now - a2dpConnectedSinceMs) : Long.MAX_VALUE;
                if (wantA2dp && !flapSuppressed && heldMs <= SHORT_DROP_WINDOW_MS) {
                    if (lastShortDropMs > 0 && now - lastShortDropMs > FLAP_RESET_MS) {
                        shortDropCount = 0;
                    }
                    shortDropCount++;
                    lastShortDropMs = now;
                    if (shortDropCount >= 2) {
                        flapSuppressed = true;
                        triggerSuppress = true;
                        suppressReason = "peer_rejected_flap_held_" + heldMs + "ms";
                    }
                }
            }
            lastA2dpState = state;
        }

        if (triggerSuppress) {
            final String reason = suppressReason;
            WORKER.execute(new Runnable() {
                public void run() {
                    applyA2dpPriorities(false, reason);
                }
            });
        }
    }

    private static void applyA2dpPriorities(boolean enable, String reason) {
        int target = enable ? 100 : 0;
        List<String> keys = listA2dpPriorityKeys();
        if (keys.isEmpty()) return;
        int changed = 0;
        StringBuilder updated = new StringBuilder();
        for (String entry : keys) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String key = entry.substring(0, eq).trim();
            String cur = entry.substring(eq + 1).trim();
            int curVal = -1;
            try { curVal = Integer.parseInt(cur); } catch (NumberFormatException ignored) {}
            // When enabling, keep PRIORITY_AUTO_CONNECT (1000) or PRIORITY_ON (100) untouched.
            if (enable && curVal >= 100) continue;
            if (!enable && curVal == 0) continue;
            if (runSettings("put", "global", key, String.valueOf(target))) {
                changed++;
                if (updated.length() > 0) updated.append(',');
                updated.append(key).append(':').append(curVal).append("->").append(target);
            }
        }
        if (changed > 0 || (reason != null && reason.startsWith("peer_rejected"))) {
            MdMain.logFields("bt_a2dp_policy", "reason", reason, "enable", enable,
                    "target_priority", target, "changed", changed, "keys", updated.toString());
        }
    }

    private static List<String> listA2dpPriorityKeys() {
        List<String> out = new ArrayList<String>();
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/settings", "list", "global").redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("bluetooth_a2dp_sink_priority_")) {
                    out.add(line);
                }
            }
            p.waitFor();
        } catch (Throwable ignored) {
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    private static boolean runSettings(String... args) {
        Process p = null;
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "/system/bin/settings";
            System.arraycopy(args, 0, cmd, 1, args.length);
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            while (br.readLine() != null) {}
            return p.waitFor() == 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }
}
