package com.tbox.carlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

/**
 * Standard AMapAuto (com.autonavi.amapauto) Broadcast Protocol bridge.
 * - Listens to AUTONAVI_STANDARD_BROADCAST_SEND for real-time turn-by-turn guide info (KEY_TYPE=10001),
 *   state changes (KEY_TYPE=10019), and Home/Company query replies (KEY_TYPE=10046).
 * - Automatically uses AMapAuto's built-in Home/Company (KEY_TYPE=10045 query + KEY_TYPE=10040 navigate),
 *   and prompts the user to configure Home/Company inside AMapAuto if not yet set.
 */
public final class AMapBridge {
    private static final String TAG = "TboxAMapBridge";
    public static final String AMAP_PKG = "com.autonavi.amapauto";
    public static final String ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND";
    public static final String ACTION_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV";

    public static final int KEY_GUIDE_INFO = 10001;
    public static final int KEY_APP_STATE = 10019;
    public static final int KEY_SEARCH = 10036;
    public static final int KEY_GO_HOME_COMPANY = 10040;
    public static final int KEY_QUERY_HOME_COMPANY = 10045;
    public static final int KEY_RESULT_HOME_COMPANY = 10046;
    public static final int KEY_RESULT_REQUEST = 12005;

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
        void onHomeCompanyNotSet(boolean isCompany);
    }

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final NavState state = new NavState();
    private boolean registered = false;

    // Pending one-click Home/Company request awaiting KEY_TYPE=10046 confirmation
    private int pendingDestType = 0; // 0=none, 1=home, 2=company
    private final Runnable pendingFallbackNav = new Runnable() {
        @Override
        public void run() {
            int dest = pendingDestType;
            pendingDestType = 0;
            if (dest == 1 || dest == 2) {
                Log.i(TAG, "No 10046 reply within timeout, sending 10040 directly dest=" + (dest == 2 ? 1 : 0));
                sendGoHomeCompanyBroadcast(dest == 2);
            }
        }
    };

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
            } else if (keyType == KEY_RESULT_HOME_COMPANY) {
                handleHomeCompanyResult(intent);
            } else if (keyType == KEY_RESULT_REQUEST) {
                handleRequestResult(intent);
            } else if (keyType != -1 && keyType != 12205 && keyType != 60021 && keyType != 13022) {
                Bundle extras = intent.getExtras();
                Log.d(TAG, "AMapAuto SEND KEY_TYPE=" + keyType + " extras=" + (extras != null ? extras.toString() : "null"));
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
        handler.removeCallbacks(pendingFallbackNav);
        pendingDestType = 0;
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

    private void handleHomeCompanyResult(Intent i) {
        Bundle b = i.getExtras();
        Log.i(TAG, "AMapAuto 10046 result: " + (b != null ? b.toString() : "null"));
        int extraType = i.getIntExtra("EXTRA_TYPE", pendingDestType);
        String poiName = i.getStringExtra("POINAME");
        String address = i.getStringExtra("ADDRESS");
        double lat = i.getDoubleExtra("LAT", 0.0);
        double lon = i.getDoubleExtra("LON", 0.0);
        boolean hasCoord = (Math.abs(lat) > 0.0001 || Math.abs(lon) > 0.0001);
        boolean isConfigured = hasCoord || !TextUtils.isEmpty(poiName) || !TextUtils.isEmpty(address);

        if (pendingDestType != 0 && (extraType == pendingDestType || extraType <= 0)) {
            final boolean isCompany = (pendingDestType == 2);
            pendingDestType = 0;
            handler.removeCallbacks(pendingFallbackNav);
            if (isConfigured) {
                sendGoHomeCompanyBroadcast(isCompany);
            } else {
                Log.i(TAG, "AMapAuto Home/Company not set (isCompany=" + isCompany + ")");
                if (listener != null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onHomeCompanyNotSet(isCompany);
                        }
                    });
                }
            }
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

    private boolean lastRequestedCompany = false;
    private long lastRequestTimeMs = 0L;

    private void handleRequestResult(Intent i) {
        boolean ok = i.getBooleanExtra("EXTRA_REQUEST_RESULT", true);
        int reqId = i.getIntExtra("EXTRA_REQUEST_ID", -1);
        Log.i(TAG, "AMapAuto 12005 result: ok=" + ok + " reqId=" + reqId);
        if (!ok && (System.currentTimeMillis() - lastRequestTimeMs < 5000L)) {
            final boolean isComp = lastRequestedCompany;
            if (listener != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onHomeCompanyNotSet(isComp);
                    }
                });
            }
        }
    }

    /**
     * Automatically reads AMapAuto's saved Home (isCompany=false) or Company (isCompany=true).
     * Queries KEY_TYPE=10045 & triggers KEY_TYPE=10040; if AMapAuto reports unset via
     * KEY_TYPE=10046 or KEY_TYPE=12005 (EXTRA_REQUEST_RESULT=false), prompts the user
     * to configure Home/Company inside AMapAuto.
     */
    public void navigateHomeOrCompany(final boolean isCompany) {
        handler.removeCallbacks(pendingFallbackNav);
        pendingDestType = isCompany ? 2 : 1;
        lastRequestedCompany = isCompany;
        lastRequestTimeMs = System.currentTimeMillis();

        Intent q = new Intent(ACTION_RECV);
        q.setPackage(AMAP_PKG);
        q.putExtra("SOURCE_APP", "TboxCarLauncher");
        q.putExtra("KEY_TYPE", KEY_QUERY_HOME_COMPANY);
        q.putExtra("EXTRA_TYPE", pendingDestType); // 1=Home, 2=Company
        ctx.sendBroadcast(q);

        // If AMapAuto doesn't answer 10045 with 10046 within 350ms, trigger 10040 directly
        handler.postDelayed(pendingFallbackNav, 350L);
    }

    private void sendGoHomeCompanyBroadcast(boolean isCompany) {
        final Intent b = new Intent(ACTION_RECV);
        b.setPackage(AMAP_PKG);
        b.putExtra("SOURCE_APP", "TboxCarLauncher");
        b.putExtra("KEY_TYPE", KEY_GO_HOME_COMPANY);
        b.putExtra("DEST", isCompany ? 1 : 0);
        b.putExtra("IS_START_NAVI", 0); // 0 = direct start navigation
        b.putExtra("EXTRA_DEV", 0);
        ctx.sendBroadcast(b);
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
