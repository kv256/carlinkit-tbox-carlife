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
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight root (uid=0) Binder companion service ("tbox_vd_bridge") for TboxCarLauncher.
 * Owns the 1248x702 16:9 VirtualDisplay so that InputManager.createInputForwarder(displayId)
 * succeeds natively (same owner UID + INJECT_EVENTS), giving full multi-touch, Back key,
 * app launching, and Display-0 task confinement with <1ms Binder latency.
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

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        Log.i(TAG, "Starting VdInputBridge Binder service uid=" + android.os.Process.myUid());

        if (!initSystemContextAndApis()) {
            Log.e(TAG, "Failed to init system APIs, exiting");
            System.exit(1);
        }

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
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
