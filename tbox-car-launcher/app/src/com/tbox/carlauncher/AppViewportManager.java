package com.tbox.carlauncher;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SurfaceView surfaceView;
    private RoundedCornerFrameOverlay cornerOverlay;
    private volatile IBinder bridgeBinder;
    private volatile int virtualDisplayId = -1;
    private volatile boolean ready = false;
    private volatile boolean active = true;
    private Surface lastSurface;
    private int lastW, lastH;
    private String currentAppPkg = "";
    private String pendingLaunchPkg = null;

    private IActivityManager iActivityManager;
    private Method mGetService;
    private TaskStackListener taskStackListener;
    private boolean tslRegistered = false;
    private long lastConfineMs = 0L;

    private Field fStackId, fDisplayId, fTopActivity, fVisible, fTaskIds;

    private final Runnable checkStacksRunnable = new Runnable() {
        @Override
        public void run() {
            if (!active || !ready || virtualDisplayId <= 0) return;
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

    public AppViewportManager(Context ctx, FrameLayout container, Listener listener) {
        this.ctx = ctx;
        this.container = container;
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

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                lastSurface = holder.getSurface();
                int w = surfaceView.getWidth();
                int h = surfaceView.getHeight();
                if (w > 0 && h > 0) {
                    lastW = w;
                    lastH = h;
                    attachSurfaceToBridge(lastSurface, w, h);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                lastSurface = holder.getSurface();
                lastW = width;
                lastH = height;
                if (width > 0 && height > 0) {
                    attachSurfaceToBridge(lastSurface, width, height);
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                lastSurface = null;
                detachSurfaceFromBridge();
            }
        });

        container.addView(surfaceView, 0,
            new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        cornerOverlay = new RoundedCornerFrameOverlay(ctx, 20f, UiUtil.BG_CANVAS, 0x28FFFFFF);
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
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            surface.writeToParcel(data, 0);
            data.writeInt(w);
            data.writeInt(h);
            data.writeInt(getDisplayDensityDpi());
            b.transact(VdInputBridge.TX_ATTACH_SURFACE, data, reply, 0);
            reply.readException();
            int dispId = reply.readInt();
            if (dispId > 0) {
                boolean firstReady = !ready;
                virtualDisplayId = dispId;
                ready = true;
                Log.i(TAG, "Bridge VirtualDisplay READY id=" + dispId + " size=" + w + "x" + h);
                registerTaskWatcher();
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
        for (int i = 0; i < count; i++) {
            props[i] = new MotionEvent.PointerProperties();
            ev.getPointerProperties(i, props[i]);
            coords[i] = new MotionEvent.PointerCoords();
            ev.getPointerCoords(i, coords[i]);
            // Ensure raw pointer coords are strictly surfaceView-local (0..1248, 0..702)
            coords[i].x = ev.getX(i);
            coords[i].y = ev.getY(i);
        }
        MotionEvent localEv = MotionEvent.obtain(
            ev.getDownTime(), ev.getEventTime(), ev.getAction(),
            count, props, coords, ev.getMetaState(), ev.getButtonState(),
            ev.getXPrecision(), ev.getYPrecision(), ev.getDeviceId(),
            ev.getEdgeFlags(), ev.getSource(), ev.getFlags()
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
        sendKey(b, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
        sendKey(b, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK);
        return true;
    }

    private void sendKey(IBinder b, int action, int keyCode) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            data.writeInt(action);
            data.writeInt(keyCode);
            b.transact(VdInputBridge.TX_KEY, data, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable t) {
            bridgeBinder = null;
        } finally {
            data.recycle();
        }
    }

    public void setActive(boolean isActive) {
        this.active = isActive;
        if (isActive && ready) {
            mainHandler.removeCallbacks(checkStacksRunnable);
            mainHandler.postDelayed(checkStacksRunnable, 150L);
        }
    }

    public void onLocationChanged() {}

    public boolean isReady() {
        return ready && virtualDisplayId > 0;
    }

    public int getVirtualDisplayId() {
        return virtualDisplayId;
    }

    public String getCurrentAppPackage() {
        return currentAppPkg;
    }

    public boolean launchPackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || "com.tbox.carlauncher".equals(pkg)) return false;
        if (!isReady()) {
            pendingLaunchPkg = pkg;
            return false;
        }
        IBinder b = getBridge();
        if (b == null) return false;
        String component = resolveBestComponent(pkg);
        if (component == null) {
            Log.w(TAG, "No launchable component for " + pkg);
            return false;
        }
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
            data.writeString(component);
            b.transact(VdInputBridge.TX_LAUNCH, data, null, IBinder.FLAG_ONEWAY);
            currentAppPkg = pkg;
            if (listener != null) listener.onActiveAppChanged(pkg);
            mainHandler.removeCallbacks(checkStacksRunnable);
            mainHandler.postDelayed(checkStacksRunnable, 500L);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "TX_LAUNCH failed for " + pkg, t);
            bridgeBinder = null;
            return false;
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
            int escapeStackId = -1;
            String escapePkg = null;
            int launcherStackId = -1;
            int launcherTaskId = -1;

            for (Object si : stacks) {
                if (si == null) continue;
                int stackId = fStackId.getInt(si);
                int dispId = fDisplayId.getInt(si);
                boolean visible = fVisible.getBoolean(si);
                ComponentName top = (ComponentName) fTopActivity.get(si);
                int[] taskIds = (int[]) fTaskIds.get(si);
                if (top == null) continue;
                String pkg = top.getPackageName();

                if ("com.tbox.carlauncher".equals(pkg) && dispId == 0) {
                    launcherStackId = stackId;
                    if (taskIds != null && taskIds.length > 0) {
                        launcherTaskId = taskIds[taskIds.length - 1];
                    }
                    continue;
                }

                if (dispId == virtualDisplayId && visible && topOnVd == null) {
                    topOnVd = pkg;
                } else if (dispId == 0 && visible && !isWhitelistedForDisplay0(top)) {
                    if (escapeStackId < 0) {
                        escapeStackId = stackId;
                        escapePkg = pkg;
                    }
                }
            }

            if (escapeStackId >= 0 && escapePkg != null) {
                long now = SystemClock.uptimeMillis();
                if (now - lastConfineMs > 250L) {
                    lastConfineMs = now;
                    Log.i(TAG, "Confining external app " + escapePkg + " (stack=" + escapeStackId
                        + ") from display 0 -> 16:9 display " + virtualDisplayId);
                    IBinder b = getBridge();
                    if (b != null) {
                        Parcel data = Parcel.obtain();
                        try {
                            data.writeInterfaceToken(VdInputBridge.DESCRIPTOR);
                            data.writeInt(escapeStackId);
                            data.writeInt(launcherTaskId);
                            data.writeInt(launcherStackId);
                            b.transact(VdInputBridge.TX_MOVE_STACK, data, null, IBinder.FLAG_ONEWAY);
                        } catch (Throwable t) {
                            bridgeBinder = null;
                        } finally {
                            data.recycle();
                        }
                    }
                    currentAppPkg = escapePkg;
                    if (listener != null) {
                        listener.onAppConfinedFromMainDisplay(escapePkg);
                        listener.onActiveAppChanged(escapePkg);
                    }
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

    private static boolean isWhitelistedForDisplay0(ComponentName cn) {
        if (cn == null) return true;
        String pkg = cn.getPackageName();
        if (DISPLAY0_WHITELIST_PKGS.contains(pkg)) return true;
        String cls = cn.getClassName();
        if ("com.android.settings.FallbackHome".equals(cls)) return true;
        if ("android".equals(pkg) && cls != null && cls.contains("ResolverActivity")) return true;
        return false;
    }

    public void release() {
        active = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (tslRegistered && iActivityManager != null && taskStackListener != null) {
            try { iActivityManager.unregisterTaskStackListener(taskStackListener); } catch (Throwable ignored) {}
            tslRegistered = false;
        }
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
            borderPaint.setStrokeWidth(2f);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            maskPath.reset();
            maskPath.setFillType(Path.FillType.EVEN_ODD);
            maskPath.addRect(0, 0, w, h, Path.Direction.CW);
            rect.set(0, 0, w, h);
            maskPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
            c.drawPath(maskPath, maskPaint);

            rect.set(1f, 1f, w - 1f, h - 1f);
            c.drawRoundRect(rect, radius, radius, borderPaint);
        }
    }
}
