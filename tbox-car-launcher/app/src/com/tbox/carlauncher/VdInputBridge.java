package com.tbox.carlauncher;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.Context;
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
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private static synchronized int attachSurface(Surface surface, int w, int h, int dpi) {
        if (surface == null || w <= 0 || h <= 0) return -1;
        if (dpi <= 0) dpi = 238;
        try {
            if (virtualDisplay == null) {
                virtualDisplay = displayManager.createVirtualDisplay(
                    "TboxCarViewport16x9", w, h, dpi, surface, VD_FLAGS
                );
                if (virtualDisplay != null && virtualDisplay.getDisplay() != null) {
                    virtualDisplayId = virtualDisplay.getDisplay().getDisplayId();
                    inputForwarder = mCreateInputForwarder.invoke(inputManager, virtualDisplayId);
                    Log.i(TAG, "Created owned VirtualDisplay id=" + virtualDisplayId
                        + " size=" + w + "x" + h + " dpi=" + dpi + " forwarder=" + (inputForwarder != null));
                }
            } else {
                virtualDisplay.setSurface(surface);
                virtualDisplay.resize(w, h, dpi);
                if (inputForwarder == null && virtualDisplayId > 0) {
                    inputForwarder = mCreateInputForwarder.invoke(inputManager, virtualDisplayId);
                }
                Log.i(TAG, "Reattached Surface to VirtualDisplay id=" + virtualDisplayId + " size=" + w + "x" + h);
            }
            return virtualDisplayId;
        } catch (Throwable t) {
            Log.e(TAG, "attachSurface failed", t);
            return -1;
        }
    }

    private static synchronized void detachSurface() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.setSurface(null);
            } catch (Throwable ignored) {}
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

    private static void moveStack(final int stackId, final int launcherTaskId, final int launcherStackId) {
        final int dispId;
        synchronized (VdInputBridge.class) {
            dispId = virtualDisplayId;
        }
        if (iActivityManager == null || stackId < 0 || dispId <= 0) return;
        cmdExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "Moving stack=" + stackId + " -> display=" + dispId);
                    iActivityManager.moveStackToDisplay(stackId, dispId);
                    if (launcherTaskId > 0) {
                        iActivityManager.moveTaskToFront(launcherTaskId, 0, null);
                    } else if (launcherStackId >= 0) {
                        iActivityManager.setFocusedStack(launcherStackId);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "moveStack failed stack=" + stackId + " display=" + dispId, t);
                }
            }
        });
    }

    private static void uninstallPackage(final String pkg) {
        if (pkg == null || pkg.isEmpty() || "com.tbox.carlauncher".equals(pkg) || pkg.startsWith("com.android.")) {
            return;
        }
        cmdExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "Uninstalling user package: " + pkg);
                    Process p = new ProcessBuilder("/system/bin/pm", "uninstall", pkg)
                        .redirectErrorStream(true).start();
                    p.waitFor();
                } catch (Throwable t) {
                    Log.w(TAG, "pm uninstall failed: " + pkg, t);
                }
            }
        });
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
                    int launcherTaskId = data.readInt();
                    int launcherStackId = data.readInt();
                    moveStack(stackId, launcherTaskId, launcherStackId);
                    return true;
                } else if (code == TX_UNINSTALL) {
                    data.enforceInterface(DESCRIPTOR);
                    final String pkg = data.readString();
                    uninstallPackage(pkg);
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
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
