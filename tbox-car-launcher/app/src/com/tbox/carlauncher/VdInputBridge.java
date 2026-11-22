package com.tbox.carlauncher;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight system (uid=1000) Binder companion service ("tbox_vd_bridge") for TboxCarLauncher.
 * 1) Owns the 1248x702 16:9 VirtualDisplay so InputManager.createInputForwarder(displayId)
 *    succeeds natively, giving full multi-touch, Back key, app launching, and task confinement.
 * 2) Monitors real-time Bluetooth A2DP connection/playback state from XQBluetooth's serial AT
 *    reader (SuDing SD-835 / sdsdk816) and sends direct AVRCP AT commands to /dev/goc_serial so
 *    Play/Pause/Next/Prev work reliably even in A2DP-only (HFP=0) phone-audio mode!
 */
public final class VdInputBridge {
    private static final String TAG = "TboxVdInputBridge";
    public static final String SERVICE_NAME = "tbox_vd_bridge";
    public static final String DESCRIPTOR = "com.tbox.carlauncher.IVdBridge";

    public static final int TX_ATTACH_SURFACE = IBinder.FIRST_CALL_TRANSACTION + 0;
    public static final int TX_DETACH_SURFACE = IBinder.FIRST_CALL_TRANSACTION + 1;
    public static final int TX_MOTION = IBinder.FIRST_CALL_TRANSACTION + 2;
    public static final int TX_KEY = IBinder.FIRST_CALL_TRANSACTION + 3;
    public static final int TX_LAUNCH = IBinder.FIRST_CALL_TRANSACTION + 4;
    public static final int TX_MOVE_STACK = IBinder.FIRST_CALL_TRANSACTION + 5;
    public static final int TX_UNINSTALL = IBinder.FIRST_CALL_TRANSACTION + 6;
    public static final int TX_BT_CMD = IBinder.FIRST_CALL_TRANSACTION + 7;
    public static final int TX_BT_STATE = IBinder.FIRST_CALL_TRANSACTION + 8;
    public static final int TX_SWITCH_DPI = IBinder.FIRST_CALL_TRANSACTION + 9;
    public static final int TX_FORCE_STOP = IBinder.FIRST_CALL_TRANSACTION + 10;
    public static final int TX_MOVE_TASK_TO_STACK = IBinder.FIRST_CALL_TRANSACTION + 11;
    public static final int TX_GET_RECENTS = IBinder.FIRST_CALL_TRANSACTION + 12;
    public static final int TX_GET_TASK_SNAPSHOT = IBinder.FIRST_CALL_TRANSACTION + 13;
    public static final int TX_REMOVE_TASK = IBinder.FIRST_CALL_TRANSACTION + 14;
    public static final int TX_GET_CPU_TEMP = IBinder.FIRST_CALL_TRANSACTION + 15;
    public static final int TX_SET_CARLIFE_FPS = IBinder.FIRST_CALL_TRANSACTION + 16;
    public static final int TX_SEND_BACK = IBinder.FIRST_CALL_TRANSACTION + 17;

    // BT AT command codes for TX_BT_CMD
    public static final int BT_CMD_PLAY = 1;
    public static final int BT_CMD_PAUSE = 2;
    public static final int BT_CMD_TOGGLE = 3;
    public static final int BT_CMD_NEXT = 4;
    public static final int BT_CMD_PREV = 5;
    public static final int BT_CMD_QUERY = 6;

    // Authoritative A2DP state returned by TX_BT_STATE
    public static final int BT_STATE_UNKNOWN = -1;
    public static final int BT_STATE_DISCONNECTED = 0;
    public static final int BT_STATE_PAUSED = 1;
    public static final int BT_STATE_PLAYING = 2;

    private static final String GOC_SERIAL_PATH = "/dev/goc_serial";

    private static final int VD_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

    private static Context sysCtx;
    private static DisplayManager displayManager;
    private static InputManager inputManager;
    private static Method mCreateInputForwarder;
    private static Method mForwardEvent;
    private static IActivityManager iActivityManager;
    private static Field fStackId;
    private static Field fDisplayId;
    private static Field fVisible;
    private static Field fTopActivity;

    private static final class DisplaySlot {
        final int dpi;
        final int width;
        final int height;
        VirtualDisplay vd;
        int displayId = -1;
        Object inputForwarder;

        DisplaySlot(int dpi, int width, int height) {
            this.dpi = dpi;
            this.width = width;
            this.height = height;
        }
    }

    private static final Map<Integer, DisplaySlot> displaySlots = new HashMap<Integer, DisplaySlot>();
    private static DisplaySlot activeSlot = null;
    private static boolean surfaceAttached = false;
    private static Surface currentSurface = null;
    private static int baseW = 1248;
    private static int baseH = 702;
    private static int baseDpi = 238;

    private static VirtualDisplay virtualDisplay;
    private static int virtualDisplayId = -1;
    private static Object inputForwarder;
    private static int allowedLauncherUid = -1;
    private static final ExecutorService cmdExecutor = Executors.newSingleThreadExecutor();

    // Real-time BT A2DP state & metadata tracked directly from sdsdk816 / XQBluetooth serial AT logs
    private static final Object btWriteLock = new Object();
    private static volatile int btA2dpState = BT_STATE_UNKNOWN;
    private static volatile long btLastEventMs = 0L;
    private static volatile long btLastQueryMs = 0L;
    private static volatile String btTitle = "";
    private static volatile String btArtist = "";
    private static volatile String btAlbum = "";
    private static volatile long btPosMs = 0L;
    private static volatile long btDurMs = 0L;

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        Log.i(TAG, "Starting VdInputBridge Binder service uid=" + android.os.Process.myUid());

        if (!initSystemContextAndApis()) {
            Log.e(TAG, "Failed to init system APIs, exiting");
            System.exit(1);
        }

        startBtAtMonitor();

        Binder service = new BridgeBinder();
        if (!registerBinderService(SERVICE_NAME, service)) {
            Log.e(TAG, "Failed to register ServiceManager service " + SERVICE_NAME);
            System.exit(1);
        }
        Log.i(TAG, "Registered Binder service '" + SERVICE_NAME + "' successfully");
        Looper.loop();
    }

    private static boolean initSystemContextAndApis() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread == null) thread = at.getMethod("systemMain").invoke(null);
            sysCtx = (Context) at.getMethod("getSystemContext").invoke(thread);
            displayManager = (DisplayManager) sysCtx.getSystemService(Context.DISPLAY_SERVICE);

            Method mGetInstance = InputManager.class.getDeclaredMethod("getInstance");
            mGetInstance.setAccessible(true);
            inputManager = (InputManager) mGetInstance.invoke(null);

            mCreateInputForwarder = InputManager.class.getDeclaredMethod("createInputForwarder", int.class);
            mCreateInputForwarder.setAccessible(true);

            Class<?> iface = Class.forName("android.app.IInputForwarder");
            mForwardEvent = iface.getDeclaredMethod("forwardEvent", InputEvent.class);
            mForwardEvent.setAccessible(true);

            Method mGetService = ActivityManager.class.getDeclaredMethod("getService");
            mGetService.setAccessible(true);
            iActivityManager = (IActivityManager) mGetService.invoke(null);

            try {
                Class<?> siCls = Class.forName("android.app.ActivityManager$StackInfo");
                fStackId = siCls.getField("stackId");
                fDisplayId = siCls.getField("displayId");
                fVisible = siCls.getField("visible");
                fTopActivity = siCls.getField("topActivity");
            } catch (Throwable ignored) {}

            return displayManager != null && inputManager != null && iActivityManager != null;
        } catch (Throwable t) {
            Log.e(TAG, "initSystemContextAndApis failed", t);
            return false;
        }
    }

    private static void startBtAtMonitor() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                // Probe initial state shortly after logcat tail starts
                Thread probe = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try { Thread.sleep(1500L); } catch (InterruptedException ignored) {}
                        if (btA2dpState == BT_STATE_UNKNOWN) {
                            writeBtAt("AT#MV");
                        }
                    }
                }, "BtAtInitProbe");
                probe.setDaemon(true);
                probe.start();

                while (true) {
                    Process proc = null;
                    BufferedReader br = null;
                    try {
                        // Omit -T 1 so we replay boot history and immediately learn current MU/MY state
                        proc = new ProcessBuilder(
                            "/system/bin/logcat", "-v", "raw",
                            "-s", "BTRead:D", "BTMusicManager2:D"
                        ).redirectErrorStream(true).start();
                        br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        while ((line = br.readLine()) != null) {
                            parseBtLogLine(line);
                        }
                    } catch (Throwable ex) {
                        Log.w(TAG, "BtAtMonitor loop error", ex);
                    } finally {
                        if (br != null) try { br.close(); } catch (Throwable ignored) {}
                        if (proc != null) try { proc.destroy(); } catch (Throwable ignored) {}
                    }
                    try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
                }
            }
        }, "BtAtMonitor");
        t.setDaemon(true);
        t.start();
    }

    private static void parseBtLogLine(String line) {
        if (line == null || line.isEmpty()) return;
        int newState = -99;
        if (line.contains("[MU0]") || line.contains("[MU1]") || line.contains("[MU2]")
            || line.contains("[MY]") || line.contains("a2dp连接状态: 0")) {
            newState = BT_STATE_DISCONNECTED;
        } else if (line.contains("[MU4]") || line.contains("[MB]")) {
            newState = BT_STATE_PLAYING;
        } else if (line.contains("[MU3]") || line.contains("[MA]")) {
            newState = BT_STATE_PAUSED;
        } else if (line.contains("a2dp连接状态: 1")) {
            if (btA2dpState <= BT_STATE_DISCONNECTED) {
                newState = BT_STATE_PAUSED;
            }
        }
        if (newState != -99) {
            int prev = btA2dpState;
            btA2dpState = newState;
            btLastEventMs = System.currentTimeMillis();
            if (newState == BT_STATE_DISCONNECTED) {
                btTitle = "";
                btArtist = "";
                btAlbum = "";
                btPosMs = 0L;
                btDurMs = 0L;
            }
            if (prev != newState) {
                Log.i(TAG, "BT A2DP state changed: " + prev + " -> " + newState + " (" + line.trim() + ")");
            }
            return;
        }

        // Parse track metadata directly from [MI<title>\uFFFD<artist>\uFFFD<album>\uFFFD<dur>...]
        int miStart = line.indexOf("[MI");
        if (miStart >= 0) {
            int miEnd = line.lastIndexOf(']');
            if (miEnd > miStart + 3) {
                String payload = line.substring(miStart + 3, miEnd);
                String[] parts = payload.split("\ufffd", -1);
                if (parts.length >= 1) {
                    String title = parts[0].trim();
                    String artist = parts.length >= 2 ? parts[1].trim() : "";
                    String album = parts.length >= 3 ? parts[2].trim() : "";
                    long dur = 0L;
                    if (parts.length >= 4) {
                        try { dur = Long.parseLong(parts[3].trim()); } catch (Throwable ignored) {}
                    }
                    if (!title.isEmpty()) {
                        boolean changed = !title.equals(btTitle) || !artist.equals(btArtist);
                        btTitle = title;
                        btArtist = !artist.isEmpty() ? artist : album;
                        btAlbum = album;
                        if (dur > 0) {
                            btDurMs = dur;
                        } else if (changed) {
                            btDurMs = 0L;
                        }
                        if (changed) {
                            btPosMs = 0L;
                            Log.i(TAG, "BT track changed: title=" + btTitle + " artist=" + btArtist + " dur=" + btDurMs);
                        }
                        btLastEventMs = System.currentTimeMillis();
                    }
                }
            }
            return;
        }

        // Parse playback position & duration from [MT<8-digit posMs><8-digit durMs>]
        int mtStart = line.indexOf("[MT");
        if (mtStart >= 0) {
            int mtEnd = line.indexOf(']', mtStart + 3);
            if (mtEnd >= mtStart + 19) {
                String digits = line.substring(mtStart + 3, mtEnd).trim();
                if (digits.length() >= 16) {
                    try {
                        long pos = Long.parseLong(digits.substring(0, 8));
                        long dur = Long.parseLong(digits.substring(8, 16));
                        if (btA2dpState > BT_STATE_DISCONNECTED) {
                            btPosMs = Math.max(0L, pos);
                            if (dur > 0L) btDurMs = dur;
                            btLastEventMs = System.currentTimeMillis();
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private static boolean writeBtAt(String atCmd) {
        synchronized (btWriteLock) {
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(GOC_SERIAL_PATH);
                byte[] bytes = (atCmd + "\r\n").getBytes(StandardCharsets.US_ASCII);
                fos.write(bytes);
                fos.flush();
                Log.i(TAG, "Sent BT AT command: " + atCmd);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "writeBtAt(" + atCmd + ") failed", t);
                return false;
            } finally {
                if (fos != null) try { fos.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean handleBtCmd(int cmd) {
        if (cmd == BT_CMD_QUERY) {
            btLastQueryMs = System.currentTimeMillis();
            return writeBtAt("AT#MV");
        }
        // Never send playback AT commands if A2DP is known disconnected
        if (btA2dpState == BT_STATE_DISCONNECTED) {
            Log.i(TAG, "Ignoring BT cmd=" + cmd + " because A2DP is disconnected");
            return false;
        }
        switch (cmd) {
            case BT_CMD_PLAY:
                return writeBtAt("AT#MS");
            case BT_CMD_PAUSE:
                return writeBtAt("AT#MB");
            case BT_CMD_TOGGLE:
                return writeBtAt("AT#MA");
            case BT_CMD_NEXT:
                return writeBtAt("AT#MD");
            case BT_CMD_PREV:
                return writeBtAt("AT#ME");
            default:
                return false;
        }
    }

    private static boolean registerBinderService(String name, IBinder binder) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method add = sm.getDeclaredMethod("addService", String.class, IBinder.class);
            add.invoke(null, name, binder);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "ServiceManager.addService failed", t);
            return false;
        }
    }

    private static boolean isAllowedUid(int uid) {
        if (uid == 0 || uid == 1000 || uid == 2000) return true;
        if (allowedLauncherUid > 0 && uid == allowedLauncherUid) return true;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/data/system/packages.list"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("com.tbox.carlauncher ")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        allowedLauncherUid = Integer.parseInt(parts[1]);
                        return uid == allowedLauncherUid;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) try { br.close(); } catch (Throwable ignored) {}
        }
        return false;
    }

    public static int computeScaledWidth(int baseWidth, int baseDensity, int targetDpi) {
        if (targetDpi <= 0 || targetDpi == baseDensity) return baseWidth;
        int w = Math.round(baseWidth * ((float) baseDensity / (float) targetDpi));
        w = Math.max(960, Math.min(2560, w));
        return (w / 16) * 16; // keep clean 16:9 even dimensions
    }

    public static int computeScaledHeight(int scaledWidth) {
        int h = Math.round(scaledWidth * 9f / 16f);
        return (h / 2) * 2;
    }

    private static synchronized int attachSurface(Surface surface, int w, int h, int dpi) {
        if (surface == null || w <= 0 || h <= 0) return -1;
        if (dpi <= 0) dpi = baseDpi;
        currentSurface = surface;
        if (activeSlot == null) {
            baseW = w;
            baseH = h;
        }
        return switchToDpi(dpi);
    }

    private static synchronized int switchToDpi(int dpi) {
        if (dpi <= 0) dpi = baseDpi;
        if (currentSurface == null || baseW <= 0 || baseH <= 0) return -1;
        int slotW = computeScaledWidth(baseW, baseDpi, dpi);
        int slotH = computeScaledHeight(slotW);
        // Keep VirtualDisplay density at baseDpi (238) while scaling the 16:9 canvas resolution!
        // SurfaceFlinger hardware-scales slotW x slotH cleanly onto the 1248x702 SurfaceView,
        // which works for 100% of apps (including apps like Bilibili Things that read Display 0's
        // Application density or hardcode 1080p pixel layouts!).
        try {
            if (activeSlot != null && activeSlot.dpi == dpi && activeSlot.vd != null) {
                activeSlot.vd.setSurface(currentSurface);
                activeSlot.vd.resize(slotW, slotH, baseDpi);
                surfaceAttached = true;
                if (activeSlot.inputForwarder == null && activeSlot.displayId > 0) {
                    activeSlot.inputForwarder = mCreateInputForwarder.invoke(inputManager, activeSlot.displayId);
                }
                virtualDisplay = activeSlot.vd;
                virtualDisplayId = activeSlot.displayId;
                inputForwarder = activeSlot.inputForwarder;
                Log.i(TAG, "Reattached Surface to VirtualDisplay id=" + virtualDisplayId
                    + " size=" + slotW + "x" + slotH + " targetDpi=" + dpi);
                return virtualDisplayId;
            }

            if (activeSlot != null && activeSlot.vd != null && surfaceAttached) {
                try {
                    activeSlot.vd.setSurface(null);
                } catch (Throwable ignored) {}
                surfaceAttached = false;
                // Give SurfaceFlinger one vsync to release the Surface producer before connecting to another VD
                try { Thread.sleep(45L); } catch (InterruptedException ignored) {}
            }

            DisplaySlot target = displaySlots.get(dpi);
            if (target == null || target.vd == null) {
                target = new DisplaySlot(dpi, slotW, slotH);
                target.vd = displayManager.createVirtualDisplay(
                    "TboxCarViewport_" + dpi, slotW, slotH, baseDpi, currentSurface, VD_FLAGS
                );
                if (target.vd != null && target.vd.getDisplay() != null) {
                    target.displayId = target.vd.getDisplay().getDisplayId();
                    target.inputForwarder = mCreateInputForwarder.invoke(inputManager, target.displayId);
                    displaySlots.put(dpi, target);
                    Log.i(TAG, "Created owned VirtualDisplay id=" + target.displayId
                        + " size=" + slotW + "x" + slotH + " targetDpi=" + dpi
                        + " forwarder=" + (target.inputForwarder != null));
                }
            } else {
                target.vd.setSurface(currentSurface);
                target.vd.resize(slotW, slotH, baseDpi);
                if (target.inputForwarder == null && target.displayId > 0) {
                    target.inputForwarder = mCreateInputForwarder.invoke(inputManager, target.displayId);
                }
                Log.i(TAG, "Switched active VirtualDisplay -> id=" + target.displayId
                    + " size=" + slotW + "x" + slotH + " targetDpi=" + dpi);
            }

            activeSlot = target;
            surfaceAttached = (target.vd != null);
            virtualDisplay = target.vd;
            virtualDisplayId = target.displayId;
            inputForwarder = target.inputForwarder;
            return virtualDisplayId;
        } catch (Throwable t) {
            Log.e(TAG, "switchToDpi(" + dpi + ") failed", t);
            return -1;
        }
    }

    private static synchronized void detachSurface() {
        currentSurface = null;
        if (activeSlot != null && activeSlot.vd != null && surfaceAttached) {
            try {
                activeSlot.vd.setSurface(null);
            } catch (Throwable ignored) {}
        }
        surfaceAttached = false;
    }

    private static int findVisibleStackOnDisplay(int dispId) {
        if (iActivityManager == null || dispId <= 0 || fStackId == null || fDisplayId == null) {
            return -1;
        }
        try {
            List<?> stacks = iActivityManager.getAllStackInfos();
            if (stacks == null) return -1;
            int fallback = -1;
            for (Object si : stacks) {
                if (si == null) continue;
                int d = fDisplayId.getInt(si);
                if (d != dispId) continue;
                int sid = fStackId.getInt(si);
                if (sid <= 0) continue;
                boolean vis = fVisible != null && fVisible.getBoolean(si);
                ComponentName top = fTopActivity != null ? (ComponentName) fTopActivity.get(si) : null;
                if (vis && top != null) {
                    return sid;
                }
                if (fallback < 0 && top != null) {
                    fallback = sid;
                }
            }
            return fallback;
        } catch (Throwable t) {
            Log.w(TAG, "findVisibleStackOnDisplay(" + dispId + ") failed", t);
            return -1;
        }
    }

    private static void sendBackKeyToActiveVd() {
        final int dispId;
        synchronized (VdInputBridge.class) {
            dispId = virtualDisplayId;
        }
        if (dispId <= 0) return;
        cmdExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    int vdStackId = findVisibleStackOnDisplay(dispId);
                    if (vdStackId <= 0 || iActivityManager == null) {
                        // Never inject KEYCODE_BACK when no app is visible on the VirtualDisplay;
                        // otherwise InputDispatcher delivers it back to LauncherActivity on Display 0!
                        Log.i(TAG, "No visible app on display=" + dispId + "; skipping KEYCODE_BACK");
                        return;
                    }
                    iActivityManager.setFocusedStack(vdStackId);
                    // Wait briefly so WindowManagerService & InputDispatcher switch global
                    // FocusedWindow from Display 0 (Launcher) to the VirtualDisplay app!
                    try { Thread.sleep(45L); } catch (InterruptedException ignored) {}
                    if (findVisibleStackOnDisplay(dispId) <= 0) {
                        return;
                    }
                    long downTime = SystemClock.uptimeMillis();
                    int keyFlags = KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY;
                    KeyEvent down = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_BACK, 0, 0, -1, 0, keyFlags, InputDevice.SOURCE_KEYBOARD);
                    forwardEvent(down);
                    long upTime = SystemClock.uptimeMillis();
                    KeyEvent up = new KeyEvent(downTime, upTime, KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_BACK, 0, 0, -1, 0, keyFlags, InputDevice.SOURCE_KEYBOARD);
                    forwardEvent(up);
                    Log.i(TAG, "Sent KEYCODE_BACK to display=" + dispId + " stack=" + vdStackId);
                } catch (Throwable t) {
                    Log.w(TAG, "sendBackKeyToActiveVd failed", t);
                }
            }
        });
    }

    private static void forceStopPackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || "com.tbox.carlauncher".equals(pkg) || "com.android.systemui".equals(pkg)) {
            return;
        }
        try {
            Log.i(TAG, "Force-stopping package for DPI reload: " + pkg);
            Process p = new ProcessBuilder("/system/bin/am", "force-stop", pkg)
                .redirectErrorStream(true).start();
            p.waitFor();
        } catch (Throwable t) {
            Log.w(TAG, "am force-stop failed: " + pkg, t);
        }
    }

    private static void forwardEvent(InputEvent ev) {
        Object fwd;
        int dispId;
        synchronized (VdInputBridge.class) {
            fwd = inputForwarder;
            dispId = virtualDisplayId;
        }
        if (fwd == null || dispId <= 0) return;
        try {
            mForwardEvent.invoke(fwd, ev);
        } catch (Throwable t) {
            Log.w(TAG, "forwardEvent failed, recreating forwarder", t);
            synchronized (VdInputBridge.class) {
                try {
                    inputForwarder = mCreateInputForwarder.invoke(inputManager, virtualDisplayId);
                    if (inputForwarder != null) mForwardEvent.invoke(inputForwarder, ev);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void launchComponent(final String component) {
        final int dispId;
        synchronized (VdInputBridge.class) {
            dispId = virtualDisplayId;
        }
        if (dispId <= 0 || component == null || component.isEmpty()) return;
        cmdExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "Launching " + component + " on display " + dispId);
                    Process p = new ProcessBuilder(
                        "/system/bin/am", "start",
                        "--display", String.valueOf(dispId),
                        "-n", component,
                        "-f", "0x10200000"
                    ).redirectErrorStream(true).start();
                    p.waitFor();
                } catch (Throwable t) {
                    Log.w(TAG, "launchComponent failed: " + component, t);
                }
            }
        });
    }

    /**
     * Ensures a VirtualDisplay slot exists for the requested DPI even if Launcher's SurfaceView
     * is currently detached (e.g. while Launcher is covered by PackageInstaller or an escaped
     * fullscreen app on Display 0). When a Surface is attached, delegates to switchToDpi().
     */
    private static synchronized int ensureSlotForDpi(int dpi) {
        if (dpi <= 0) dpi = baseDpi;
        if (currentSurface != null) {
            return switchToDpi(dpi);
        }
        int slotW = computeScaledWidth(baseW, baseDpi, dpi);
        int slotH = computeScaledHeight(slotW);
        try {
            DisplaySlot target = displaySlots.get(dpi);
            if (target == null || target.vd == null) {
                target = new DisplaySlot(dpi, slotW, slotH);
                target.vd = displayManager.createVirtualDisplay(
                    "TboxCarViewport_" + dpi, slotW, slotH, baseDpi, null, VD_FLAGS
                );
                if (target.vd != null && target.vd.getDisplay() != null) {
                    target.displayId = target.vd.getDisplay().getDisplayId();
                    target.inputForwarder = mCreateInputForwarder.invoke(inputManager, target.displayId);
                    displaySlots.put(dpi, target);
                    Log.i(TAG, "Created headless VirtualDisplay id=" + target.displayId
                        + " size=" + slotW + "x" + slotH + " targetDpi=" + dpi);
                }
            }
            if (target != null && target.displayId > 0) {
                activeSlot = target;
                surfaceAttached = false;
                virtualDisplay = target.vd;
                virtualDisplayId = target.displayId;
                inputForwarder = target.inputForwarder;
                return target.displayId;
            }
        } catch (Throwable t) {
            Log.e(TAG, "ensureSlotForDpi(" + dpi + ") failed", t);
        }
        return virtualDisplayId;
    }

    private static void moveStack(final int stackId, final int taskId, final int targetDpi,
                                  final int launcherTaskId, final int launcherStackId) {
        if (iActivityManager == null || (stackId < 0 && taskId <= 0)) return;
        cmdExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    int dispId = ensureSlotForDpi(targetDpi > 0 ? targetDpi : baseDpi);
                    if (dispId <= 0) {
                        Log.w(TAG, "moveStack aborted: no VirtualDisplay for dpi=" + targetDpi);
                        return;
                    }
                    if (stackId > 0 && stackId != launcherStackId) {
                        Log.i(TAG, "Moving stack=" + stackId + " -> display=" + dispId + " (dpi=" + targetDpi + ")");
                        iActivityManager.moveStackToDisplay(stackId, dispId);
                    } else if (taskId > 0) {
                        // Never move the stack that contains LauncherActivity; reparent ONLY the escaped task!
                        Log.i(TAG, "Reparenting task=" + taskId + " (shared stack=" + stackId
                            + ") -> display=" + dispId + " via startActivityFromRecents");
                        android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                        Method mSetDisp = android.app.ActivityOptions.class.getMethod("setLaunchDisplayId", int.class);
                        mSetDisp.invoke(opts, dispId);
                        Method mStartRecents = iActivityManager.getClass().getMethod(
                            "startActivityFromRecents", int.class, android.os.Bundle.class
                        );
                        mStartRecents.invoke(iActivityManager, taskId, opts.toBundle());
                    }
                    if (launcherTaskId > 0) {
                        iActivityManager.moveTaskToFront(launcherTaskId, 0, null);
                    } else if (launcherStackId >= 0) {
                        iActivityManager.setFocusedStack(launcherStackId);
                    } else {
                        Process p = new ProcessBuilder(
                            "/system/bin/am", "start",
                            "--display", "0",
                            "-a", "android.intent.action.MAIN",
                            "-c", "android.intent.category.HOME"
                        ).redirectErrorStream(true).start();
                        p.waitFor();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "moveStack failed stack=" + stackId + " task=" + taskId + " dpi=" + targetDpi, t);
                }
            }
        });
    }

    private static void moveTaskToStack(final int taskId, final int targetStackId) {
        if (iActivityManager == null || taskId <= 0) return;
        cmdExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (targetStackId > 0) {
                    try {
                        Log.i(TAG, "Promoting task=" + taskId + " -> Display 0 standard stack=" + targetStackId);
                        Method m = iActivityManager.getClass().getMethod(
                            "moveTaskToStack", int.class, int.class, boolean.class
                        );
                        m.invoke(iActivityManager, taskId, targetStackId, true);
                        return;
                    } catch (Throwable t) {
                        Log.w(TAG, "moveTaskToStack failed task=" + taskId + " stack=" + targetStackId + ", falling back to startActivityFromRecents", t);
                    }
                }
                // On a fresh boot Display 0 only has Stack #0 (ACTIVITY_TYPE_HOME), which rejects
                // moveTaskToStack for standard tasks. Use startActivityFromRecents with launchDisplayId=0
                // so AMS creates a standard fullscreen stack on Display 0 and reparents ONLY this task.
                try {
                    Log.i(TAG, "Promoting task=" + taskId + " -> Display 0 via startActivityFromRecents(display=0)");
                    android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                    Method mSetDisp = android.app.ActivityOptions.class.getMethod("setLaunchDisplayId", int.class);
                    mSetDisp.invoke(opts, 0);
                    Method mStartRecents = iActivityManager.getClass().getMethod(
                        "startActivityFromRecents", int.class, android.os.Bundle.class
                    );
                    mStartRecents.invoke(iActivityManager, taskId, opts.toBundle());
                } catch (Throwable t) {
                    Log.w(TAG, "startActivityFromRecents(display=0) failed task=" + taskId, t);
                }
            }
        });
    }

    private static volatile String cachedCpuThermalPath = null;

    private static final class RecentEntry {
        final int taskId;
        final String pkg;
        final boolean running;

        RecentEntry(int taskId, String pkg, boolean running) {
            this.taskId = taskId;
            this.pkg = pkg;
            this.running = running;
        }
    }

    private static boolean isExcludedRecentPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        return "com.tbox.carlauncher".equals(pkg)
            || "com.android.systemui".equals(pkg)
            || "com.android.launcher3".equals(pkg)
            || "com.autolauncher.motorcar".equals(pkg)
            || "com.android.packageinstaller".equals(pkg)
            || "com.google.android.packageinstaller".equals(pkg)
            || "com.suding.factorytest".equals(pkg);
    }

    @SuppressWarnings("unchecked")
    private static List<RecentEntry> queryRecentTasks() {
        List<RecentEntry> out = new ArrayList<RecentEntry>();
        if (iActivityManager == null) return out;
        try {
            Method mGetRecents = iActivityManager.getClass().getMethod(
                "getRecentTasks", int.class, int.class, int.class
            );
            Object slice = mGetRecents.invoke(
                iActivityManager, 24, ActivityManager.RECENT_IGNORE_UNAVAILABLE, 0
            );
            if (slice == null) return out;
            Method mGetList = slice.getClass().getMethod("getList");
            List<?> raw = (List<?>) mGetList.invoke(slice);
            if (raw == null) return out;

            Set<String> seen = new HashSet<String>();
            for (Object obj : raw) {
                if (!(obj instanceof ActivityManager.RecentTaskInfo)) continue;
                ActivityManager.RecentTaskInfo rti = (ActivityManager.RecentTaskInfo) obj;
                ComponentName cn = rti.baseActivity;
                if (cn == null) cn = rti.origActivity;
                if (cn == null && rti.baseIntent != null) cn = rti.baseIntent.getComponent();
                if (cn == null) {
                    try {
                        cn = (ComponentName) rti.getClass().getField("realActivity").get(rti);
                    } catch (Throwable ignored) {}
                }
                if (cn == null) continue;
                String pkg = cn.getPackageName();
                if (isExcludedRecentPkg(pkg) || seen.contains(pkg)) continue;
                // Skip ES File Explorer's invisible install monitor hook activity
                if ("com.estrongs.android.pop".equals(pkg)
                        && cn.getClassName() != null && cn.getClassName().contains("InstallMonitor")) {
                    continue;
                }
                int tid = rti.id >= 0 ? rti.id : rti.persistentId;
                if (tid <= 0) continue;
                seen.add(pkg);
                out.add(new RecentEntry(tid, pkg, rti.id >= 0));
                if (out.size() >= 14) break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "queryRecentTasks failed", t);
        }
        return out;
    }

    private static byte[] captureTaskSnapshotJpeg(int taskId) {
        if (iActivityManager == null || taskId <= 0) return null;
        Bitmap hwBmp = null;
        Bitmap swBmp = null;
        Bitmap scaled = null;
        try {
            Method mGetSnap = iActivityManager.getClass().getMethod(
                "getTaskSnapshot", int.class, boolean.class
            );
            Object snap = mGetSnap.invoke(iActivityManager, taskId, true);
            if (snap == null) return null;
            Method mGetBuf = snap.getClass().getMethod("getSnapshot");
            Object gb = mGetBuf.invoke(snap);
            if (gb == null) return null;
            Class<?> gbCls = Class.forName("android.graphics.GraphicBuffer");
            Method mCreateHw = Bitmap.class.getMethod("createHardwareBitmap", gbCls);
            hwBmp = (Bitmap) mCreateHw.invoke(null, gb);
            if (hwBmp == null) return null;
            swBmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false);
            if (swBmp == null) return null;

            int w = swBmp.getWidth();
            int h = swBmp.getHeight();
            Bitmap toEncode = swBmp;
            if (w > 420 && h > 0) {
                int tw = 420;
                int th = Math.max(1, Math.round(h * (420f / w)));
                scaled = Bitmap.createScaledBitmap(swBmp, tw, th, true);
                toEncode = scaled;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(24 * 1024);
            toEncode.compress(Bitmap.CompressFormat.JPEG, 76, baos);
            return baos.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "captureTaskSnapshotJpeg(" + taskId + ") failed", t);
            return null;
        } finally {
            if (scaled != null && scaled != swBmp) try { scaled.recycle(); } catch (Throwable ignored) {}
            if (swBmp != null) try { swBmp.recycle(); } catch (Throwable ignored) {}
            if (hwBmp != null) try { hwBmp.recycle(); } catch (Throwable ignored) {}
        }
    }

    private static boolean removeRecentTask(int taskId) {
        if (iActivityManager == null || taskId <= 0) return false;
        try {
            Method mRemove = iActivityManager.getClass().getMethod("removeTask", int.class);
            Object res = mRemove.invoke(iActivityManager, taskId);
            boolean ok = (res instanceof Boolean) && ((Boolean) res).booleanValue();
            Log.i(TAG, "removeTask(" + taskId + ") -> " + ok);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "removeTask(" + taskId + ") failed", t);
            return false;
        }
    }

    private static String readSmallFile(String path) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(path));
            String line = br.readLine();
            return line != null ? line.trim() : null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (br != null) try { br.close(); } catch (Throwable ignored) {}
        }
    }

    private static int readCpuTempMilliC() {
        String path = cachedCpuThermalPath;
        if (path == null) {
            String bestPath = null;
            int bestRank = -1;
            for (int i = 0; i < 40; i++) {
                String dir = "/sys/class/thermal/thermal_zone" + i;
                File tempFile = new File(dir + "/temp");
                if (!tempFile.exists()) continue;
                String type = readSmallFile(dir + "/type");
                if (type == null) type = "";
                String lower = type.toLowerCase();
                int rank = -1;
                if (lower.contains("deca-cpu-max") || lower.contains("cpu-max")) {
                    rank = 100;
                } else if (lower.contains("apc1-l2") || lower.contains("apc1-cpu")) {
                    rank = 80;
                } else if (lower.contains("cpu")) {
                    rank = 60;
                } else if (lower.contains("tsens") || lower.contains("soc")) {
                    rank = 40;
                }
                if (rank > bestRank) {
                    String val = readSmallFile(dir + "/temp");
                    if (val != null && !val.isEmpty()) {
                        bestRank = rank;
                        bestPath = dir + "/temp";
                    }
                }
            }
            if (bestPath == null) bestPath = "/sys/class/thermal/thermal_zone0/temp";
            cachedCpuThermalPath = bestPath;
            path = bestPath;
            Log.i(TAG, "Selected CPU thermal zone: " + path);
        }
        String raw = readSmallFile(path);
        if (raw == null || raw.isEmpty()) return -1;
        try {
            int v = Integer.parseInt(raw);
            if (v > 0 && v < 200) return v * 1000;
            if (v >= 200 && v < 2000) return v * 100;
            return v;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean uninstallPackageSync(final String pkg) {
        if (pkg == null || pkg.isEmpty() || "com.tbox.carlauncher".equals(pkg) || pkg.startsWith("com.android.")) {
            return false;
        }
        if (sysCtx == null) return false;
        try {
            PackageManager pm = sysCtx.getPackageManager();
            PackageInstaller installer = pm.getPackageInstaller();
            final CountDownLatch latch = new CountDownLatch(1);
            Binder receiverBinder = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                    if (code == IBinder.FIRST_CALL_TRANSACTION) {
                        latch.countDown();
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    return super.onTransact(code, data, reply, flags);
                }
            };
            Constructor<IntentSender> ctor = IntentSender.class.getDeclaredConstructor(IBinder.class);
            ctor.setAccessible(true);
            IntentSender sender = ctor.newInstance(receiverBinder);

            Log.i(TAG, "Uninstalling user package via PackageInstaller: " + pkg);
            installer.uninstall(pkg, sender);
            boolean signaled = latch.await(12L, TimeUnit.SECONDS);

            boolean gone = false;
            try {
                pm.getPackageInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                gone = true;
            }
            Log.i(TAG, "Uninstall result for " + pkg + ": signaled=" + signaled + " gone=" + gone);
            return gone;
        } catch (Throwable t) {
            Log.w(TAG, "PackageInstaller.uninstall failed: " + pkg, t);
            return false;
        }
    }

    private static final class BridgeBinder extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            int callingUid = Binder.getCallingUid();
            if (!isAllowedUid(callingUid)) {
                Log.w(TAG, "Rejected Binder tx=" + code + " from uid=" + callingUid);
                return false;
            }
            long ident = Binder.clearCallingIdentity();
            try {
                if (code == TX_ATTACH_SURFACE) {
                    data.enforceInterface(DESCRIPTOR);
                    Surface surface = Surface.CREATOR.createFromParcel(data);
                    int w = data.readInt();
                    int h = data.readInt();
                    int dpi = data.readInt();
                    int dispId = attachSurface(surface, w, h, dpi);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(dispId);
                    }
                    return true;
                } else if (code == TX_DETACH_SURFACE) {
                    data.enforceInterface(DESCRIPTOR);
                    detachSurface();
                    if (reply != null) reply.writeNoException();
                    return true;
                } else if (code == TX_MOTION) {
                    data.enforceInterface(DESCRIPTOR);
                    MotionEvent ev = MotionEvent.CREATOR.createFromParcel(data);
                    try {
                        forwardEvent(ev);
                    } finally {
                        ev.recycle();
                    }
                    return true;
                } else if (code == TX_KEY) {
                    data.enforceInterface(DESCRIPTOR);
                    int action = data.readInt();
                    int keyCode = data.readInt();
                    long now = SystemClock.uptimeMillis();
                    KeyEvent ke = new KeyEvent(now, now, action, keyCode, 0, 0,
                        -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
                    forwardEvent(ke);
                    return true;
                } else if (code == TX_LAUNCH) {
                    data.enforceInterface(DESCRIPTOR);
                    String component = data.readString();
                    launchComponent(component);
                    return true;
                } else if (code == TX_MOVE_STACK) {
                    data.enforceInterface(DESCRIPTOR);
                    int stackId = data.readInt();
                    int taskId = data.readInt();
                    int targetDpi = data.readInt();
                    int launcherTaskId = data.readInt();
                    int launcherStackId = data.readInt();
                    moveStack(stackId, taskId, targetDpi, launcherTaskId, launcherStackId);
                    return true;
                } else if (code == TX_UNINSTALL) {
                    data.enforceInterface(DESCRIPTOR);
                    final String pkg = data.readString();
                    boolean ok = uninstallPackageSync(pkg);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(ok ? 1 : 0);
                    }
                    return true;
                } else if (code == TX_BT_CMD) {
                    data.enforceInterface(DESCRIPTOR);
                    int cmd = data.readInt();
                    boolean ok = handleBtCmd(cmd);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(ok ? 1 : 0);
                    }
                    return true;
                } else if (code == TX_BT_STATE) {
                    data.enforceInterface(DESCRIPTOR);
                    if (btA2dpState == BT_STATE_UNKNOWN) {
                        long now = System.currentTimeMillis();
                        if (now - btLastQueryMs > 3000L) {
                            btLastQueryMs = now;
                            cmdExecutor.execute(new Runnable() {
                                @Override
                                public void run() {
                                    writeBtAt("AT#MV");
                                }
                            });
                        }
                    }
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(btA2dpState);
                        reply.writeLong(btLastEventMs);
                        reply.writeString(btTitle);
                        reply.writeString(btArtist);
                        reply.writeString(btAlbum);
                        reply.writeLong(btPosMs);
                        reply.writeLong(btDurMs);
                    }
                    return true;
                } else if (code == TX_SWITCH_DPI) {
                    data.enforceInterface(DESCRIPTOR);
                    int dpi = data.readInt();
                    int dispId = switchToDpi(dpi);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(dispId);
                        reply.writeInt(activeSlot != null ? activeSlot.width : baseW);
                        reply.writeInt(activeSlot != null ? activeSlot.height : baseH);
                    }
                    return true;
                } else if (code == TX_FORCE_STOP) {
                    data.enforceInterface(DESCRIPTOR);
                    String pkg = data.readString();
                    forceStopPackage(pkg);
                    if (reply != null) reply.writeNoException();
                    return true;
                } else if (code == TX_MOVE_TASK_TO_STACK) {
                    data.enforceInterface(DESCRIPTOR);
                    int taskId = data.readInt();
                    int targetStackId = data.readInt();
                    moveTaskToStack(taskId, targetStackId);
                    return true;
                } else if (code == TX_GET_RECENTS) {
                    data.enforceInterface(DESCRIPTOR);
                    List<RecentEntry> list = queryRecentTasks();
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(list.size());
                        for (RecentEntry e : list) {
                            reply.writeInt(e.taskId);
                            reply.writeString(e.pkg);
                            reply.writeInt(e.running ? 1 : 0);
                        }
                    }
                    return true;
                } else if (code == TX_GET_TASK_SNAPSHOT) {
                    data.enforceInterface(DESCRIPTOR);
                    int taskId = data.readInt();
                    byte[] jpg = captureTaskSnapshotJpeg(taskId);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeByteArray(jpg);
                    }
                    return true;
                } else if (code == TX_REMOVE_TASK) {
                    data.enforceInterface(DESCRIPTOR);
                    int taskId = data.readInt();
                    boolean ok = removeRecentTask(taskId);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(ok ? 1 : 0);
                    }
                    return true;
                } else if (code == TX_GET_CPU_TEMP) {
                    data.enforceInterface(DESCRIPTOR);
                    int milliC = readCpuTempMilliC();
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(milliC);
                    }
                    return true;
                } else if (code == TX_SET_CARLIFE_FPS) {
                    data.enforceInterface(DESCRIPTOR);
                    final int fps = data.readInt() == 60 ? 60 : 30;
                    cmdExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Process p = new ProcessBuilder(
                                    "/system/bin/setprop", "persist.tbox.carlife.fps", String.valueOf(fps)
                                ).redirectErrorStream(true).start();
                                p.waitFor();
                                Log.i(TAG, "Updated persist.tbox.carlife.fps=" + fps);
                            } catch (Throwable t) {
                                Log.w(TAG, "setprop persist.tbox.carlife.fps failed", t);
                            }
                        }
                    });
                    if (reply != null) reply.writeNoException();
                    return true;
                } else if (code == TX_SEND_BACK) {
                    data.enforceInterface(DESCRIPTOR);
                    sendBackKeyToActiveVd();
                    return true;
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
