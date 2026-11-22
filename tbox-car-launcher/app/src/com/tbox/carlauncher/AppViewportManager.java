package com.tbox.carlauncher;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Connects the 1248x702 16:9 SurfaceView to VdInputBridge ("tbox_vd_bridge", uid=0) so the root
 * bridge owns the VirtualDisplay and forwards native multi-touch & Back keys directly via
 * InputManager.createInputForwarder, while TaskStackListener confines all Display-0 apps into 16:9.
 */
public final class AppViewportManager {
    private static final String TAG = "TboxAppViewport";

    public interface Listener {
        void onViewportReady(int virtualDisplayId);
        void onActiveAppChanged(String packageName);
        void onAppConfinedFromMainDisplay(String packageName);
        void onViewportEmpty();
    }

    private static final Set<String> DISPLAY0_WHITELIST_PKGS = new HashSet<String>();
    static {
        DISPLAY0_WHITELIST_PKGS.add("com.tbox.carlauncher");
        DISPLAY0_WHITELIST_PKGS.add("com.android.systemui");
        DISPLAY0_WHITELIST_PKGS.add("com.android.packageinstaller");
        DISPLAY0_WHITELIST_PKGS.add("com.google.android.packageinstaller");
        DISPLAY0_WHITELIST_PKGS.add("com.android.launcher3");
        DISPLAY0_WHITELIST_PKGS.add("com.autolauncher.motorcar");
    }

    private final Context ctx;
    private final FrameLayout container;
    private final LauncherPrefs prefs;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SurfaceView surfaceView;
    private RoundedCornerFrameOverlay cornerOverlay;
    private volatile IBinder bridgeBinder;
    private volatile int virtualDisplayId = -1;
    private volatile int activeDpi = 0;
    private volatile int activeVdW = 1248;
    private volatile int activeVdH = 702;
    private volatile boolean ready = false;
    private volatile boolean active = true;
    private Surface lastSurface;
    private int lastW, lastH;
    private int surfaceBufW = 0;
    private int surfaceBufH = 0;
    private int pendingDpi = -1;
    private int pendingVdW = 0;
    private int pendingVdH = 0;
    private Runnable pendingAfterResize = null;
    private String currentAppPkg = "";
    private String pendingLaunchPkg = null;

    private IActivityManager iActivityManager;
    private Method mGetService;
    private TaskStackListener taskStackListener;
    private boolean tslRegistered = false;
    private long lastConfineMs = 0L;
    private int lastPromoteTaskId = -1;
    private long lastPromoteMs = 0L;
    private long lastRecoverMs = 0L;
    private long lastLaunchMs = 0L;

    private boolean proxyBlockedByUi = true;
    private boolean display0Occluded = false;
    private boolean lastProxyEnabled = false;
    private int lastProxyX = -1;
    private int lastProxyY = -1;
    private int lastProxyW = -1;
    private int lastProxyH = -1;
    private Method mGetWindowSession;
    private Method mGetViewRootImpl;
    private Method mUpdateTapExcludeRegion;
    private int tapExcludeParamCount = 0;

    private Field fStackId, fDisplayId, fTopActivity, fVisible, fTaskIds, fTaskNames;

    private final Runnable checkStacksRunnable = new Runnable() {
        @Override
        public void run() {
            if (!active || !ready || virtualDisplayId <= 0 || pendingDpi > 0) return;
            enforceConfinementAndSyncTopApp();
        }
    };

    private final Runnable retryBindRunnable = new Runnable() {
        @Override
        public void run() {
            if (!active || ready || lastSurface == null || lastW <= 0 || lastH <= 0) return;
            attachSurfaceToBridge(lastSurface, lastW, lastH);
        }
    };

    private final Runnable completeResizeAttachRunnable = new Runnable() {
        @Override
        public void run() {
            if (pendingDpi <= 0) return;
            if (lastSurface == null || lastW <= 0 || lastH <= 0) {
                // Surface was destroyed while waiting for resize (e.g. Launcher covered by
                // PackageInstaller); clear pendingDpi so confinement checks never stall.
                pendingDpi = -1;
                pendingVdW = 0;
                pendingVdH = 0;
                pendingAfterResize = null;
                return;
            }
            int targetDpi = pendingDpi;
            Runnable after = pendingAfterResize;
            pendingDpi = -1;
            pendingVdW = 0;
            pendingVdH = 0;
            pendingAfterResize = null;
            activeDpi = targetDpi;
            attachSurfaceToBridge(lastSurface, lastW, lastH);
            if (after != null) {
                after.run();
            }
        }
    };

    public AppViewportManager(Context ctx, FrameLayout container, LauncherPrefs prefs, Listener listener) {
        this.ctx = ctx;
        this.container = container;
        this.prefs = prefs;
        this.listener = listener;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            mGetService = sm.getDeclaredMethod("getService", String.class);

            Method getAm = ActivityManager.class.getDeclaredMethod("getService");
            getAm.setAccessible(true);
            iActivityManager = (IActivityManager) getAm.invoke(null);

            Class<?> siCls = Class.forName("android.app.ActivityManager$StackInfo");
            fStackId = siCls.getField("stackId");
            fDisplayId = siCls.getField("displayId");
            fTopActivity = siCls.getField("topActivity");
            fVisible = siCls.getField("visible");
            fTaskIds = siCls.getField("taskIds");
            fTaskNames = siCls.getField("taskNames");

            try {
                Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
                mGetWindowSession = wmg.getMethod("getWindowSession");
                mGetViewRootImpl = View.class.getMethod("getViewRootImpl");
                Class<?> iws = Class.forName("android.view.IWindowSession");
                for (Method m : iws.getDeclaredMethods()) {
                    if ("updateTapExcludeRegion".equals(m.getName())) {
                        mUpdateTapExcludeRegion = m;
                        tapExcludeParamCount = m.getParameterTypes().length;
                        Log.i(TAG, "Resolved IWindowSession.updateTapExcludeRegion params=" + tapExcludeParamCount);
                        break;
                    }
                }
            } catch (Throwable wt) {
                Log.w(TAG, "Failed to resolve IWindowSession.updateTapExcludeRegion", wt);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to init reflection", t);
        }
    }

    private IBinder getBridge() {
        IBinder b = bridgeBinder;
        if (b != null && b.isBinderAlive()) return b;
        try {
            if (mGetService != null) {
                b = (IBinder) mGetService.invoke(null, VdInputBridge.SERVICE_NAME);
                if (b != null && b.isBinderAlive()) {
                    bridgeBinder = b;
                    return b;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getService(tbox_vd_bridge) failed", t);
        }
        bridgeBinder = null;
        return null;
    }

    public void attach() {
        if (surfaceView != null) return;

        surfaceView = new SurfaceView(ctx);
        surfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                if (!ready || virtualDisplayId <= 0) return false;
                sendMotion(ev);
                return true;
            }
        });
        surfaceView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int orr, int ob) {
                if (l != ol || t != ot || r != orr || b != ob) {
                    syncTouchProxy(false);
                }
            }
        });

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                lastSurface = holder.getSurface();
                int w = surfaceView.getWidth();
                int h = surfaceView.getHeight();
                if (w > 0 && h > 0) {
                    lastW = w;
                    lastH = h;
                    if (surfaceBufW <= 0 || surfaceBufH <= 0) {
                        surfaceBufW = w;
                        surfaceBufH = h;
                    }
                    int wantDpi = activeDpi > 0 ? activeDpi : getDisplayDensityDpi();
                    int wantW = VdInputBridge.computeScaledWidth(w, getDisplayDensityDpi(), wantDpi);
                    int wantH = VdInputBridge.computeScaledHeight(wantW);
                    if (pendingDpi <= 0 && (surfaceBufW != wantW || surfaceBufH != wantH)) {
                        pendingDpi = wantDpi;
                        pendingVdW = wantW;
                        pendingVdH = wantH;
                        pendingAfterResize = null;
                        surfaceView.getHolder().setFixedSize(wantW, wantH);
                        mainHandler.removeCallbacks(completeResizeAttachRunnable);
                        mainHandler.postDelayed(completeResizeAttachRunnable, 220L);
                    } else if (pendingDpi <= 0) {
                        attachSurfaceToBridge(lastSurface, w, h);
                    }
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                lastSurface = holder.getSurface();
                surfaceBufW = width;
                surfaceBufH = height;
                int vw = surfaceView.getWidth();
                int vh = surfaceView.getHeight();
                if (vw > 0 && vh > 0) {
                    lastW = vw;
                    lastH = vh;
                }
                if (pendingDpi > 0) {
                    if (width == pendingVdW && height == pendingVdH) {
                        // Give SurfaceFlinger ~65ms to apply BufferLayer::setSize and finish
                        // releasing the previous DisplayDevice before binding the new VD.
                        mainHandler.removeCallbacks(completeResizeAttachRunnable);
                        mainHandler.postDelayed(completeResizeAttachRunnable, 65L);
                    }
                    return;
                }
                if (lastW > 0 && lastH > 0) {
                    attachSurfaceToBridge(lastSurface, lastW, lastH);
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                mainHandler.removeCallbacks(completeResizeAttachRunnable);
                pendingDpi = -1;
                pendingVdW = 0;
                pendingVdH = 0;
                pendingAfterResize = null;
                lastSurface = null;
                surfaceBufW = 0;
                surfaceBufH = 0;
                syncTouchProxy(true);
                detachSurfaceFromBridge();
            }
        });

        container.addView(surfaceView, 0,
            new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        cornerOverlay = new RoundedCornerFrameOverlay(ctx, 20f, UiUtil.BG_CANVAS, 0x0DFFFFFF);
        container.addView(cornerOverlay, 1,
            new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private int getDisplayDensityDpi() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return dm != null && dm.densityDpi > 0 ? dm.densityDpi : 238;
    }

    private void attachSurfaceToBridge(Surface surface, int w, int h) {
        IBinder b = getBridge();
        if (b == null) {
            Log.w(TAG, "tbox_vd_bridge not ready yet, retrying in 800ms");
            mainHandler.removeCallbacks(retryBindRunnable);
            mainHandler.postDelayed(retryBindRunnable, 800L);
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            int initDpi = activeDpi > 0 ? activeDpi : getDisplayDensityDpi();
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            surface.writeToParcel(data, 0);
            data.writeInt(w);
            data.writeInt(h);
            data.writeInt(initDpi);
            b.transact(VdInputBridge.TX_ATTACH_SURFACE, data, reply, 0);
            reply.readException();
            int dispId = reply.readInt();
            if (dispId > 0) {
                boolean firstReady = !ready;
                virtualDisplayId = dispId;
                activeDpi = initDpi;
                activeVdW = VdInputBridge.computeScaledWidth(w, getDisplayDensityDpi(), initDpi);
                activeVdH = VdInputBridge.computeScaledHeight(activeVdW);
                ready = true;
                Log.i(TAG, "Bridge VirtualDisplay READY id=" + dispId + " view=" + w + "x" + h
                    + " vd=" + activeVdW + "x" + activeVdH + " dpi=" + initDpi);
                registerTaskWatcher();
                syncTouchProxy(true);
                if (firstReady && listener != null) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onViewportReady(virtualDisplayId);
                            if (pendingLaunchPkg != null) {
                                String pkg = pendingLaunchPkg;
                                pendingLaunchPkg = null;
                                launchPackage(pkg);
                            }
                        }
                    });
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "TX_ATTACH_SURFACE failed", t);
            bridgeBinder = null;
            mainHandler.removeCallbacks(retryBindRunnable);
            mainHandler.postDelayed(retryBindRunnable, 1000L);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void detachSurfaceFromBridge() {
        IBinder b = getBridge();
        if (b == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            b.transact(VdInputBridge.TX_DETACH_SURFACE, data, reply, 0);
            reply.readException();
        } catch (Throwable ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void sendMotion(MotionEvent ev) {
        IBinder b = getBridge();
        if (b == null) return;
        int count = Math.min(10, ev.getPointerCount());
        if (count <= 0) return;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        float vw = surfaceView != null && surfaceView.getWidth() > 0 ? surfaceView.getWidth() : 1248f;
        float vh = surfaceView != null && surfaceView.getHeight() > 0 ? surfaceView.getHeight() : 702f;
        float sx = activeVdW / vw;
        float sy = activeVdH / vh;
        for (int i = 0; i < count; i++) {
            props[i] = new MotionEvent.PointerProperties();
            ev.getPointerProperties(i, props[i]);
            coords[i] = new MotionEvent.PointerCoords();
            ev.getPointerCoords(i, coords[i]);
            // Scale surfaceView-local coords (0..1248, 0..702) cleanly to VirtualDisplay coords (0..vdW, 0..vdH)
            coords[i].x = ev.getX(i) * sx;
            coords[i].y = ev.getY(i) * sy;
        }
        // Strip obscured flags inherited from Display 0 overlays (e.g. temp monitor / floating ball)
        // so PackageInstaller & runtime permission dialogs inside the VirtualDisplay never drop taps.
        int cleanFlags = ev.getFlags() & ~(MotionEvent.FLAG_WINDOW_IS_OBSCURED | 0x2);
        MotionEvent localEv = MotionEvent.obtain(
            ev.getDownTime(), ev.getEventTime(), ev.getAction(),
            count, props, coords, ev.getMetaState(), ev.getButtonState(),
            ev.getXPrecision(), ev.getYPrecision(), ev.getDeviceId(),
            ev.getEdgeFlags(), ev.getSource(), cleanFlags
        );
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            localEv.writeToParcel(data, 0);
            b.transact(VdInputBridge.TX_MOTION, data, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable t) {
            bridgeBinder = null;
        } finally {
            data.recycle();
            localEv.recycle();
        }
    }

    public boolean sendBackKey() {
        IBinder b = getBridge();
        if (!isReady() || b == null) return false;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            b.transact(VdInputBridge.TX_SEND_BACK, data, null, IBinder.FLAG_ONEWAY);
            // Re-check stack state after the Back animation settles so an exiting root app
            // immediately transitions the 16:9 viewport back to another live app or App Drawer.
            mainHandler.removeCallbacks(checkStacksRunnable);
            mainHandler.postDelayed(checkStacksRunnable, 380L);
            return true;
        } catch (Throwable t) {
            bridgeBinder = null;
            return false;
        } finally {
            data.recycle();
        }
    }

    public void setTouchProxyBlocked(boolean blocked) {
        if (proxyBlockedByUi != blocked) {
            proxyBlockedByUi = blocked;
            syncTouchProxy(false);
        } else if (!blocked) {
            syncTouchProxy(false);
        }
    }

    public void focusLauncherHomeStack() {
        if (iActivityManager == null) return;
        try {
            iActivityManager.setFocusedStack(0);
        } catch (Throwable ignored) {}
    }

    public void syncTouchProxy(boolean force) {
        boolean wantEnabled = active && ready && virtualDisplayId > 0
            && lastSurface != null && !proxyBlockedByUi && !display0Occluded;
        int x = 0, y = 0, w = 0, h = 0;
        if (wantEnabled && surfaceView != null) {
            // Exclude the entire Display 0 Launcher window (1920x720) from Display 0's
            // TaskTapPointerEventListener while a 16:9 app is active. Touches on the Left Dock,
            // Right Cards, and SurfaceView still dispatch normally via InputDispatcher, AND:
            //   1) Floating IME popups (e.g. WeType PopupWindow extending past x=1384 or dragged
            //      anywhere on screen) never trigger setFocusedTask(LauncherActivity), which
            //      previously closed the keyboard and stole focus from the VirtualDisplay EditText!
            //   2) Tapping Music Card controls never pauses BiliThings / VirtualDisplay apps.
            View root = surfaceView.getRootView();
            w = (root != null && root.getWidth() > 0) ? root.getWidth() : surfaceView.getWidth();
            h = (root != null && root.getHeight() > 0) ? root.getHeight() : surfaceView.getHeight();
            x = 0;
            y = 0;
            if (w <= 0 || h <= 0) {
                wantEnabled = false;
            }
        }
        if (!force && wantEnabled == lastProxyEnabled
                && (!wantEnabled || (x == lastProxyX && y == lastProxyY
                    && w == lastProxyW && h == lastProxyH))) {
            return;
        }
        if (surfaceView == null || mGetWindowSession == null || mGetViewRootImpl == null
                || mUpdateTapExcludeRegion == null) {
            return;
        }
        try {
            Object vri = mGetViewRootImpl.invoke(surfaceView);
            if (vri == null) return;
            Field fWin = vri.getClass().getDeclaredField("mWindow");
            fWin.setAccessible(true);
            Object iWindow = fWin.get(vri);
            Object session = mGetWindowSession.invoke(null);
            if (iWindow == null || session == null) return;
            int regionId = System.identityHashCode(surfaceView);
            if (tapExcludeParamCount == 6) {
                if (wantEnabled) {
                    mUpdateTapExcludeRegion.invoke(session, iWindow, regionId, x, y, w, h);
                } else {
                    mUpdateTapExcludeRegion.invoke(session, iWindow, regionId, 0, 0, 0, 0);
                }
            } else if (tapExcludeParamCount == 3) {
                Region r = wantEnabled ? new Region(x, y, x + w, y + h) : null;
                mUpdateTapExcludeRegion.invoke(session, iWindow, regionId, r);
            } else if (tapExcludeParamCount == 2) {
                Region r = wantEnabled ? new Region(x, y, x + w, y + h) : null;
                mUpdateTapExcludeRegion.invoke(session, iWindow, r);
            }
            lastProxyEnabled = wantEnabled;
            lastProxyX = x;
            lastProxyY = y;
            lastProxyW = w;
            lastProxyH = h;
            Log.i(TAG, "Updated WindowSession tapExcludeRegion enabled=" + wantEnabled
                + " rect=(" + x + "," + y + " " + w + "x" + h + ")");
        } catch (Throwable t) {
            Log.w(TAG, "updateTapExcludeRegion failed", t);
            lastProxyEnabled = false;
        }
    }

    public void setActive(boolean isActive) {
        this.active = isActive;
        syncTouchProxy(false);
        if (isActive && ready) {
            mainHandler.removeCallbacks(checkStacksRunnable);
            mainHandler.postDelayed(checkStacksRunnable, 150L);
        }
    }

    public void onLocationChanged() {
        syncTouchProxy(false);
    }

    public boolean isReady() {
        return ready && virtualDisplayId > 0;
    }

    public int getVirtualDisplayId() {
        return virtualDisplayId;
    }

    public String getCurrentAppPackage() {
        return currentAppPkg;
    }

    public boolean uninstallUserPackage(String pkg) {
        IBinder b = getBridge();
        if (b == null || pkg == null || pkg.isEmpty()) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            data.writeString(pkg);
            b.transact(VdInputBridge.TX_UNINSTALL, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (Throwable t) {
            Log.w(TAG, "TX_UNINSTALL failed for " + pkg, t);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public int getActiveDpi() {
        return activeDpi > 0 ? activeDpi : getDisplayDensityDpi();
    }

    private boolean switchToDpiSync(int targetDpi, String pkg) {
        IBinder b = getBridge();
        if (b == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            data.writeInt(targetDpi);
            b.transact(VdInputBridge.TX_SWITCH_DPI, data, reply, 0);
            reply.readException();
            int dispId = reply.readInt();
            int vdW = reply.readInt();
            int vdH = reply.readInt();
            if (dispId > 0) {
                virtualDisplayId = dispId;
                activeDpi = targetDpi;
                if (vdW > 0 && vdH > 0) {
                    activeVdW = vdW;
                    activeVdH = vdH;
                }
                Log.i(TAG, "Switched viewport for " + pkg + " -> display=" + dispId
                    + " vd=" + activeVdW + "x" + activeVdH + " dpi=" + targetDpi);
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "TX_SWITCH_DPI(" + targetDpi + ") failed", t);
            bridgeBinder = null;
        } finally {
            data.recycle();
            reply.recycle();
        }
        return false;
    }

    public void ensureDpiForPackage(final String pkg, final Runnable onReady) {
        int baseDpi = getDisplayDensityDpi();
        int targetDpi = prefs != null
            ? prefs.getEffectiveAppDpi(pkg, baseDpi)
            : baseDpi;
        int vw = surfaceView != null && surfaceView.getWidth() > 0
            ? surfaceView.getWidth() : (lastW > 0 ? lastW : 1248);
        int targetW = VdInputBridge.computeScaledWidth(vw, baseDpi, targetDpi);
        int targetH = VdInputBridge.computeScaledHeight(targetW);

        if (pendingDpi <= 0 && targetDpi == activeDpi && virtualDisplayId > 0
                && surfaceBufW == targetW && surfaceBufH == targetH) {
            if (onReady != null) onReady.run();
            return;
        }

        if (surfaceView != null && (surfaceBufW != targetW || surfaceBufH != targetH)) {
            Log.i(TAG, "Resizing SurfaceView buffer " + surfaceBufW + "x" + surfaceBufH
                + " -> " + targetW + "x" + targetH + " before attaching dpi=" + targetDpi + " (" + pkg + ")");
            mainHandler.removeCallbacks(completeResizeAttachRunnable);
            detachSurfaceFromBridge();
            pendingDpi = targetDpi;
            pendingVdW = targetW;
            pendingVdH = targetH;
            pendingAfterResize = onReady;
            surfaceView.getHolder().setFixedSize(targetW, targetH);
            // Fallback in case SurfaceView was already at requested size and skips surfaceChanged
            mainHandler.postDelayed(completeResizeAttachRunnable, 220L);
            return;
        }

        switchToDpiSync(targetDpi, pkg);
        if (onReady != null) onReady.run();
    }

    public static final class RecentTaskItem {
        public final int taskId;
        public final String packageName;
        public final boolean running;

        public RecentTaskItem(int taskId, String packageName, boolean running) {
            this.taskId = taskId;
            this.packageName = packageName;
            this.running = running;
        }
    }

    public List<RecentTaskItem> getRecentTasks() {
        List<RecentTaskItem> out = new ArrayList<RecentTaskItem>();
        IBinder b = getBridge();
        if (b == null) return out;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            b.transact(VdInputBridge.TX_GET_RECENTS, data, reply, 0);
            reply.readException();
            int count = reply.readInt();
            for (int i = 0; i < count; i++) {
                int tid = reply.readInt();
                String pkg = reply.readString();
                boolean running = reply.readInt() != 0;
                if (pkg != null && !pkg.isEmpty()) {
                    out.add(new RecentTaskItem(tid, pkg, running));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "TX_GET_RECENTS failed", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
        return out;
    }

    public Bitmap getTaskSnapshot(int taskId) {
        IBinder b = getBridge();
        if (b == null || taskId <= 0) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            data.writeInt(taskId);
            b.transact(VdInputBridge.TX_GET_TASK_SNAPSHOT, data, reply, 0);
            reply.readException();
            byte[] jpg = reply.createByteArray();
            if (jpg != null && jpg.length > 0) {
                return BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
            }
        } catch (Throwable t) {
            Log.w(TAG, "TX_GET_TASK_SNAPSHOT(" + taskId + ") failed", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
        return null;
    }

    public boolean removeRecentTask(int taskId) {
        IBinder b = getBridge();
        if (b == null || taskId <= 0) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            data.writeInt(taskId);
            b.transact(VdInputBridge.TX_REMOVE_TASK, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (Throwable t) {
            Log.w(TAG, "TX_REMOVE_TASK(" + taskId + ") failed", t);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public void setCarLifeFps(int fps) {
        IBinder b = getBridge();
        if (b == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            data.writeInt(fps == 60 ? 60 : 30);
            b.transact(VdInputBridge.TX_SET_CARLIFE_FPS, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "TX_SET_CARLIFE_FPS failed", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public float getCpuTempCelsius() {
        IBinder b = getBridge();
        if (b == null) return -1f;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            b.transact(VdInputBridge.TX_GET_CPU_TEMP, data, reply, 0);
            reply.readException();
            int milliC = reply.readInt();
            return milliC > 0 ? (milliC / 1000f) : -1f;
        } catch (Throwable t) {
            return -1f;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public void forceStopPackage(String pkg) {
        IBinder b = getBridge();
        if (b == null || pkg == null || pkg.isEmpty()) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            data.writeString(pkg);
            b.transact(VdInputBridge.TX_FORCE_STOP, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "TX_FORCE_STOP failed for " + pkg, t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public boolean launchPackage(final String pkg) {
        if (pkg == null || pkg.isEmpty() || "com.tbox.carlauncher".equals(pkg)) return false;
        if (!isReady()) {
            pendingLaunchPkg = pkg;
            return false;
        }
        if (getBridge() == null) return false;
        final String component = resolveBestComponent(pkg);
        if (component == null) {
            Log.w(TAG, "No launchable component for " + pkg);
            return false;
        }
        ensureDpiForPackage(pkg, new Runnable() {
            @Override
            public void run() {
                dispatchLaunchComponent(pkg, component);
            }
        });
        return true;
    }

    private void dispatchLaunchComponent(String pkg, String component) {
        IBinder b = getBridge();
        if (b == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            data.writeString(component);
            b.transact(VdInputBridge.TX_LAUNCH, data, null, IBinder.FLAG_ONEWAY);
            lastLaunchMs = SystemClock.uptimeMillis();
            currentAppPkg = pkg;
            if (listener != null) listener.onActiveAppChanged(pkg);
            mainHandler.removeCallbacks(checkStacksRunnable);
            mainHandler.postDelayed(checkStacksRunnable, 500L);
        } catch (Throwable t) {
            Log.e(TAG, "TX_LAUNCH failed for " + pkg, t);
            bridgeBinder = null;
        } finally {
            data.recycle();
        }
    }

    private String resolveBestComponent(String pkg) {
        if (AMapBridge.AMAP_PKG.equals(pkg)) {
            return AMapBridge.AMAP_PKG + "/.MainMapActivity";
        }
        PackageManager pm = ctx.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null || intent.getComponent() == null) return null;
        return intent.getComponent().flattenToShortString();
    }

    private void registerTaskWatcher() {
        if (tslRegistered || iActivityManager == null) return;
        taskStackListener = new TaskStackListener() {
            @Override
            public void onTaskStackChanged() {
                scheduleCheck();
            }

            @Override
            public void onTaskMovedToFront(int taskId) {
                scheduleCheck();
            }

            @Override
            public void onTaskCreated(int taskId, ComponentName componentName) {
                scheduleCheck();
            }
        };
        try {
            iActivityManager.registerTaskStackListener(taskStackListener);
            tslRegistered = true;
            Log.i(TAG, "Global TaskStackListener registered");
        } catch (Throwable t) {
            Log.w(TAG, "registerTaskStackListener failed", t);
        }
    }

    private void scheduleCheck() {
        mainHandler.removeCallbacks(checkStacksRunnable);
        mainHandler.postDelayed(checkStacksRunnable, 120L);
    }

    private void enforceConfinementAndSyncTopApp() {
        if (iActivityManager == null || virtualDisplayId <= 0) return;
        try {
            List<?> stacks = iActivityManager.getAllStackInfos();
            if (stacks == null) return;

            String topOnVd = null;
            String fallbackVdPkg = null;
            int escapeStackId = -1;
            int escapeTaskId = -1;
            String escapePkg = null;
            int promoteTaskId = -1;
            String promotePkg = null;
            boolean installerActiveOnDisplay0 = false;
            boolean otherVisibleOnDisplay0 = false;
            int launcherStackId = -1;
            int launcherTaskId = -1;
            int display0StandardStackId = -1;
            String autoPkg = prefs != null ? prefs.getAutoLaunchPackage() : "";

            for (Object si : stacks) {
                if (si == null) continue;
                int stackId = fStackId.getInt(si);
                int dispId = fDisplayId.getInt(si);
                boolean visible = fVisible.getBoolean(si);
                ComponentName top = (ComponentName) fTopActivity.get(si);
                int[] taskIds = (int[]) fTaskIds.get(si);
                String[] taskNames = fTaskNames != null ? (String[]) fTaskNames.get(si) : null;
                if (top == null) continue;
                String pkg = top.getPackageName();

                if (dispId == 0 && stackId > 0 && display0StandardStackId < 0) {
                    display0StandardStackId = stackId;
                }

                if (dispId == 0 && "com.tbox.carlauncher".equals(pkg)) {
                    launcherStackId = stackId;
                    if (taskIds != null && taskIds.length > 0) {
                        launcherTaskId = taskIds[taskIds.length - 1];
                    }
                    continue;
                }
                if (dispId == 0 && launcherStackId < 0 && taskNames != null) {
                    for (int i = 0; i < taskNames.length; i++) {
                        String tn = taskNames[i];
                        if (tn != null && tn.startsWith("com.tbox.carlauncher/")) {
                            launcherStackId = stackId;
                            if (taskIds != null && i < taskIds.length) {
                                launcherTaskId = taskIds[i];
                            }
                            break;
                        }
                    }
                }

                if (dispId == 0 && visible && isPackageInstallerPkg(pkg)) {
                    installerActiveOnDisplay0 = true;
                }
                if (dispId == 0 && visible && !"com.tbox.carlauncher".equals(pkg)
                        && !isTransientIgnoreActivity(top)) {
                    otherVisibleOnDisplay0 = true;
                }

                if (dispId == virtualDisplayId && visible) {
                    if (topOnVd == null) topOnVd = pkg;
                    // PackageInstallerActivity disables its Install button inside onPause() as
                    // anti-tapjacking protection. Because touching Display 0 SurfaceView triggers
                    // TaskTapPointerEventListener -> setFocusedTask(Launcher) -> onPause() BEFORE
                    // ACTION_UP arrives on the VirtualDisplay, promote ANY PackageInstaller task
                    // (standalone or in-task) to Display 0 fullscreen where Install taps work natively!
                    if (promoteTaskId < 0 && isPackageInstallerPkg(pkg)
                            && taskIds != null && taskIds.length > 0) {
                        promoteTaskId = taskIds[taskIds.length - 1];
                        promotePkg = pkg;
                    }
                } else if (dispId > 0 && dispId != virtualDisplayId && visible && !isPackageInstallerPkg(pkg)) {
                    if (fallbackVdPkg == null || pkg.equals(currentAppPkg) || pkg.equals(autoPkg)) {
                        fallbackVdPkg = pkg;
                    }
                } else if (dispId == 0 && visible && !isWhitelistedForDisplay0(top)) {
                    if (escapeStackId < 0) {
                        escapeStackId = stackId;
                        escapePkg = pkg;
                        if (taskIds != null && taskIds.length > 0) {
                            escapeTaskId = taskIds[taskIds.length - 1];
                        }
                    }
                }
            }

            if (display0Occluded != otherVisibleOnDisplay0) {
                display0Occluded = otherVisibleOnDisplay0;
                syncTouchProxy(false);
            }

            if (promoteTaskId > 0) {
                long now = SystemClock.uptimeMillis();
                if (promoteTaskId != lastPromoteTaskId || now - lastPromoteMs > 400L) {
                    lastPromoteTaskId = promoteTaskId;
                    lastPromoteMs = now;
                    Log.i(TAG, "Promoting " + promotePkg + " (task=" + promoteTaskId
                        + ") from VirtualDisplay -> Display 0 (stdStack=" + display0StandardStackId + ")");
                    IBinder b = getBridge();
                    if (b != null) {
                        Parcel data = Parcel.obtain();
                        try {
                            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
                            data.writeInt(promoteTaskId);
                            data.writeInt(display0StandardStackId);
                            b.transact(VdInputBridge.TX_MOVE_TASK_TO_STACK, data, null, IBinder.FLAG_ONEWAY);
                        } catch (Throwable t) {
                            bridgeBinder = null;
                        } finally {
                            data.recycle();
                        }
                    }
                }
                return;
            }

            if (escapeStackId >= 0 && escapePkg != null) {
                long now = SystemClock.uptimeMillis();
                if (now - lastConfineMs > 250L) {
                    lastConfineMs = now;
                    final int fStack = escapeStackId;
                    final int fTask = escapeTaskId;
                    final String fPkg = escapePkg;
                    final int fLauncherTask = launcherTaskId;
                    final int fLauncherStack = launcherStackId;
                    final int baseDpi = getDisplayDensityDpi();
                    final int fTargetDpi = prefs != null
                        ? prefs.getEffectiveAppDpi(fPkg, baseDpi)
                        : baseDpi;
                    final Runnable dispatchMove = new Runnable() {
                        @Override
                        public void run() {
                            Log.i(TAG, "Confining external app " + fPkg + " (stack=" + fStack
                                + ", task=" + fTask + ", dpi=" + fTargetDpi + ") from display 0 -> 16:9 viewport");
                            IBinder b = getBridge();
                            if (b != null) {
                                Parcel data = Parcel.obtain();
                                try {
                                    data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
                                    data.writeInt(fStack);
                                    data.writeInt(fTask);
                                    data.writeInt(fTargetDpi);
                                    data.writeInt(fLauncherTask);
                                    data.writeInt(fLauncherStack);
                                    b.transact(VdInputBridge.TX_MOVE_STACK, data, null, IBinder.FLAG_ONEWAY);
                                } catch (Throwable t) {
                                    bridgeBinder = null;
                                } finally {
                                    data.recycle();
                                }
                            }
                            currentAppPkg = fPkg;
                            if (listener != null) {
                                listener.onAppConfinedFromMainDisplay(fPkg);
                                listener.onActiveAppChanged(fPkg);
                            }
                        }
                    };
                    if (lastSurface == null) {
                        // Launcher is currently hidden/stopped behind PackageInstaller or the escaped
                        // fullscreen app, so its SurfaceView is destroyed. Move the stack off Display 0
                        // immediately into a headless VirtualDisplay slot and pull Launcher back to front;
                        // surfaceCreated() will resize and attach once Launcher resumes!
                        int vw = lastW > 0 ? lastW : 1248;
                        activeDpi = fTargetDpi;
                        activeVdW = VdInputBridge.computeScaledWidth(vw, baseDpi, fTargetDpi);
                        activeVdH = VdInputBridge.computeScaledHeight(activeVdW);
                        dispatchMove.run();
                    } else {
                        ensureDpiForPackage(fPkg, dispatchMove);
                    }
                }
                return;
            }

            if (topOnVd == null && !installerActiveOnDisplay0 && !otherVisibleOnDisplay0) {
                long now = SystemClock.uptimeMillis();
                if (now - lastLaunchMs < 1800L) {
                    // App is still starting up; re-check shortly rather than prematurely showing Drawer.
                    mainHandler.removeCallbacks(checkStacksRunnable);
                    mainHandler.postDelayed(checkStacksRunnable, 500L);
                    return;
                }
                if (fallbackVdPkg != null) {
                    if (now - lastRecoverMs > 600L) {
                        lastRecoverMs = now;
                        final String fRecover = fallbackVdPkg;
                        Log.i(TAG, "Active VirtualDisplay " + virtualDisplayId
                            + " is empty; auto-recovering live app " + fRecover + " from peer DPI slot");
                        ensureDpiForPackage(fRecover, new Runnable() {
                            @Override
                            public void run() {
                                currentAppPkg = fRecover;
                                if (listener != null) listener.onActiveAppChanged(fRecover);
                            }
                        });
                    }
                    return;
                }
                // All VirtualDisplay slots are now empty (e.g. user pressed Back on the app's root screen).
                // Never leave the 16:9 viewport blank/black: notify Launcher to show the App Drawer!
                if (!currentAppPkg.isEmpty()) {
                    Log.i(TAG, "All VirtualDisplays empty (last=" + currentAppPkg + "); returning to App Drawer");
                    currentAppPkg = "";
                }
                if (listener != null) {
                    listener.onViewportEmpty();
                }
                return;
            }

            if (topOnVd != null && !topOnVd.equals(currentAppPkg)) {
                currentAppPkg = topOnVd;
                if (listener != null) listener.onActiveAppChanged(topOnVd);
            }
        } catch (Throwable t) {
            Log.w(TAG, "enforceConfinementAndSyncTopApp error", t);
        }
    }

    private static boolean isPackageInstallerPkg(String pkg) {
        return "com.android.packageinstaller".equals(pkg)
            || "com.google.android.packageinstaller".equals(pkg);
    }

    private static boolean isTransientIgnoreActivity(ComponentName cn) {
        if (cn == null) return true;
        String pkg = cn.getPackageName();
        String cls = cn.getClassName();
        return "com.estrongs.android.pop".equals(pkg) && cls != null
            && (cls.contains("RemainTransitionActivity") || cls.contains("InstallMonitorActivity"));
    }

    private static boolean isWhitelistedForDisplay0(ComponentName cn) {
        if (cn == null) return true;
        String pkg = cn.getPackageName();
        if (DISPLAY0_WHITELIST_PKGS.contains(pkg)) return true;
        String cls = cn.getClassName();
        if (cls != null) {
            if ("com.android.settings.FallbackHome".equals(cls)) return true;
            if ("android".equals(pkg) && cls.contains("ResolverActivity")) return true;
            if (isTransientIgnoreActivity(cn)) return true;
        }
        return false;
    }

    public void release() {
        active = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (tslRegistered && iActivityManager != null && taskStackListener != null) {
            try { iActivityManager.unregisterTaskStackListener(taskStackListener); } catch (Throwable ignored) {}
            tslRegistered = false;
        }
        syncTouchProxy(true);
        detachSurfaceFromBridge();
        ready = false;
        virtualDisplayId = -1;
    }

    private static final class RoundedCornerFrameOverlay extends View {
        private final float radius;
        private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path maskPath = new Path();
        private final RectF rect = new RectF();

        RoundedCornerFrameOverlay(Context ctx, float radiusPx, int bgColor, int borderColor) {
            super(ctx);
            this.radius = radiusPx;
            setClickable(false);
            setFocusable(false);
            maskPaint.setColor(bgColor);
            maskPaint.setStyle(Paint.Style.FILL);
            borderPaint.setColor(borderColor);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(1f);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            maskPath.reset();
            maskPath.setFillType(Path.FillType.EVEN_ODD);
            maskPath.addRect(-1f, -1f, w + 1f, h + 1f, Path.Direction.CW);
            rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f);
            maskPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
            c.drawPath(maskPath, maskPaint);

            rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f);
            c.drawRoundRect(rect, radius, radius, borderPaint);
        }
    }
}
