package org.carlife.hulab;

import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Display mirror adapter supporting both Android 9-13 (SurfaceControl.createDisplay)
 * and Android 14+ (DisplayManager.createVirtualDisplay static mirror helper).
 */
final class Api28DisplayMirror {
    private final Object virtualDisplay;
    private final IBinder surfaceControlDisplay;
    private final String apiName;
    private final int virtualDisplayId;

    private Api28DisplayMirror(Object vd, IBinder scDisplay, String api, int id) {
        this.virtualDisplay = vd;
        this.surfaceControlDisplay = scDisplay;
        this.apiName = api;
        this.virtualDisplayId = id;
    }

    static Api28DisplayMirror create(String name, int width, int height, int sourceDisplayId, Surface surface) throws Exception {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Class<?> dm = Class.forName("android.hardware.display.DisplayManager");
                Method create = dm.getDeclaredMethod("createVirtualDisplay", String.class, int.class, int.class, int.class, Surface.class);
                Object vd = create.invoke(null, name, width, height, sourceDisplayId, surface);
                if (vd != null) {
                    Object d = vd.getClass().getMethod("getDisplay").invoke(vd);
                    int id = ((Integer) d.getClass().getMethod("getDisplayId").invoke(d)).intValue();
                    return new Api28DisplayMirror(vd, null, "DisplayManager.createVirtualDisplay", id);
                }
            } catch (NoSuchMethodException ignored) {
                // Fall through to SurfaceControl path.
            }
        }
        if (sourceDisplayId != 0) {
            throw new IOException("SurfaceControl mirror on API " + Build.VERSION.SDK_INT + " supports primary display 0 only");
        }
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        IBinder token = (IBinder) sc.getMethod("createDisplay", String.class, boolean.class).invoke(null, name, Boolean.FALSE);
        if (token == null) {
            throw new IOException("SurfaceControl.createDisplay returned null");
        }
        try {
            Rect rect = new Rect(0, 0, width, height);
            sc.getMethod("openTransaction").invoke(null);
            try {
                sc.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(null, token, surface);
                sc.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                        .invoke(null, token, Integer.valueOf(0), rect, rect);
                sc.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(null, token, Integer.valueOf(0));
            } finally {
                sc.getMethod("closeTransaction").invoke(null);
            }
            return new Api28DisplayMirror(null, token, "SurfaceControl.createDisplay", -1);
        } catch (Throwable t) {
            try {
                sc.getMethod("destroyDisplay", IBinder.class).invoke(null, token);
            } catch (Throwable ignored) {}
            if (t instanceof Exception) throw (Exception) t;
            throw new IOException("SurfaceControl display mirror failed", t);
        }
    }

    String apiName() { return apiName; }
    int virtualDisplayId() { return virtualDisplayId; }

    void release() throws Exception {
        if (virtualDisplay != null) {
            virtualDisplay.getClass().getMethod("release").invoke(virtualDisplay);
        }
        if (surfaceControlDisplay != null) {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            sc.getMethod("destroyDisplay", IBinder.class).invoke(null, surfaceControlDisplay);
        }
    }
}
