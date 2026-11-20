package com.tbox.carlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Standard AMapAuto (com.autonavi.amapauto) Broadcast Protocol v2/v3 bridge.
 * Listens to AUTONAVI_STANDARD_BROADCAST_SEND for real-time turn-by-turn guide info (KEY_TYPE=10001)
 * and state changes (KEY_TYPE=10019), and sends AUTONAVI_STANDARD_BROADCAST_RECV for Home/Company/Search.
 */
public final class AMapBridge {
    private static final String TAG = "TboxAMapBridge";
    public static final String AMAP_PKG = "com.autonavi.amapauto";
    public static final String ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND";
    public static final String ACTION_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV";

    public static final int KEY_GUIDE_INFO = 10001;
    public static final int KEY_APP_STATE = 10019;
    public static final int KEY_OPEN_MAP = 10034;
    public static final int KEY_SEARCH = 10036;
    public static final int KEY_DIRECT_NAVI = 10038;
    public static final int KEY_GO_HOME_COMPANY = 10040;

    private static final long GUIDE_STALE_MS = 20_000L;

    public static final class NavState {
        public boolean navigating;
        public int iconType;
        public int segRemainDis;
        public int routeRemainDis;
        public int routeRemainTimeSec;
        public int routeAllDis;
        public int routeAllTimeSec;
        public String nextRoadName = "";
        public String curRoadName = "";
        public int guideType; // 0 = GPS, 1 = Simulation
        public long lastUpdateMs;
    }

    public interface Listener {
        void onNavStateChanged(NavState state);
    }

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final NavState state = new NavState();
    private boolean registered = false;

    private final Runnable staleCheck = new Runnable() {
        @Override
        public void run() {
            if (state.navigating && (System.currentTimeMillis() - state.lastUpdateMs > GUIDE_STALE_MS)) {
                Log.i(TAG, "Guide info stale (>20s), returning to idle mode");
                clearNav();
            }
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_SEND.equals(intent.getAction())) return;
            int keyType = intent.getIntExtra("KEY_TYPE", -1);
            if (keyType == KEY_GUIDE_INFO) {
                handleGuideInfo(intent);
            } else if (keyType == KEY_APP_STATE) {
                handleAppState(intent);
            }
        }
    };

    public AMapBridge(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (registered) return;
        IntentFilter f = new IntentFilter(ACTION_SEND);
        ctx.registerReceiver(receiver, f);
        registered = true;
    }

    public void stop() {
        if (!registered) return;
        handler.removeCallbacks(staleCheck);
        try { ctx.unregisterReceiver(receiver); } catch (Throwable ignored) {}
        registered = false;
    }

    public NavState getState() {
        return state;
    }

    private void handleGuideInfo(Intent i) {
        int icon = i.getIntExtra("ICON", 0);
        int segDis = i.getIntExtra("SEG_REMAIN_DIS", -1);
        int remDis = i.getIntExtra("ROUTE_REMAIN_DIS", -1);
        int remTime = i.getIntExtra("ROUTE_REMAIN_TIME", -1);
        int allDis = i.getIntExtra("ROUTE_ALL_DIS", 0);
        int allTime = i.getIntExtra("ROUTE_ALL_TIME", 0);
        String nextRoad = i.getStringExtra("NEXT_ROAD_NAME");
        String curRoad = i.getStringExtra("CUR_ROAD_NAME");
        int gType = i.getIntExtra("TYPE", 0);

        if (segDis < 0 && remDis < 0 && icon == 0) {
            return;
        }
        state.navigating = true;
        state.iconType = icon;
        state.segRemainDis = Math.max(0, segDis);
        state.routeRemainDis = Math.max(0, remDis);
        state.routeRemainTimeSec = Math.max(0, remTime);
        if (allDis > 0) state.routeAllDis = allDis;
        else if (state.routeAllDis < state.routeRemainDis) state.routeAllDis = state.routeRemainDis;
        if (allTime > 0) state.routeAllTimeSec = allTime;
        state.nextRoadName = nextRoad != null ? nextRoad.trim() : "";
        state.curRoadName = curRoad != null ? curRoad.trim() : "";
        state.guideType = gType;
        state.lastUpdateMs = System.currentTimeMillis();

        handler.removeCallbacks(staleCheck);
        handler.postDelayed(staleCheck, GUIDE_STALE_MS + 1000L);
        notifyListener();
    }

    private void handleAppState(Intent i) {
        int extraState = i.getIntExtra("EXTRA_STATE", -1);
        Log.d(TAG, "AMapAuto EXTRA_STATE=" + extraState);
        // 9=stop nav, 12=stop sim nav, 2=exit app (note: 40 is periodic idle/cruise heartbeat)
        if (extraState == 9 || extraState == 12 || extraState == 2) {
            clearNav();
        }
    }

    public void clearNav() {
        handler.removeCallbacks(staleCheck);
        state.navigating = false;
        state.iconType = 0;
        state.segRemainDis = 0;
        state.routeRemainDis = 0;
        state.routeRemainTimeSec = 0;
        state.routeAllDis = 0;
        state.nextRoadName = "";
        state.curRoadName = "";
        notifyListener();
    }

    private void notifyListener() {
        if (listener != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onNavStateChanged(state);
                }
            });
        }
    }

    /**
     * Trigger AMapAuto navigation to Home (dest=0) or Company (dest=1).
     * Uses custom coordinate override (KEY_TYPE=10038) if configured in LauncherPrefs;
     * otherwise uses AMapAuto's built-in saved Home/Company via KEY_TYPE=10040.
     */
    public void navigateHomeOrCompany(final boolean isCompany, LauncherPrefs prefs) {
        double lat = isCompany ? prefs.getCompanyLat() : prefs.getHomeLat();
        double lon = isCompany ? prefs.getCompanyLon() : prefs.getHomeLon();
        String name = isCompany ? prefs.getCompanyName() : prefs.getHomeName();

        final Intent b = new Intent(ACTION_RECV);
        b.setPackage(AMAP_PKG);
        b.putExtra("SOURCE_APP", "TboxCarLauncher");

        if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
            b.putExtra("KEY_TYPE", KEY_DIRECT_NAVI);
            b.putExtra("POINAME", (name == null || name.isEmpty()) ? (isCompany ? "公司" : "家") : name);
            b.putExtra("LAT", lat);
            b.putExtra("LON", lon);
            b.putExtra("DEV", 0);
            b.putExtra("STYLE", 0);
        } else {
            b.putExtra("KEY_TYPE", KEY_GO_HOME_COMPANY);
            b.putExtra("DEST", isCompany ? 1 : 0);
            b.putExtra("IS_START_NAVI", 0); // 0 = direct start navigation
            b.putExtra("EXTRA_DEV", 0);
        }
        // Send immediately and once more after 1.2s in case AMapAuto is cold-starting
        ctx.sendBroadcast(b);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                ctx.sendBroadcast(b);
            }
        }, 1200L);
    }

    public void openSearch() {
        final Intent b = new Intent(ACTION_RECV);
        b.setPackage(AMAP_PKG);
        b.putExtra("KEY_TYPE", KEY_SEARCH);
        b.putExtra("SOURCE_APP", "TboxCarLauncher");
        b.putExtra("KEYWORDS", "");
        ctx.sendBroadcast(b);
    }
}
