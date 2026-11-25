package com.tbox.carlauncher;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main 1920x720 CarPlay-style Cockpit Launcher (Scheme B exact pixel geometry):
 *   - Left Dock:          x=12,   y=9,   w=112,  h=702
 *   - Center 16:9 App:    x=136,  y=9,   w=1248, h=702 (exact 16:9!)
 *   - Top-Right Nav:      x=1396, y=9,   w=512,  h=345
 *   - Bottom-Right Music: x=1396, y=366, w=512,  h=345
 */
public class LauncherActivity extends Activity {
    private static final String TAG = "TboxCarLauncher";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private LauncherPrefs prefs;
    private AMapBridge amapBridge;
    private MediaHub mediaHub;
    private AppViewportManager viewport;

    private DockView dockView;
    private FrameLayout centerContainer;
    private AppDrawerView appDrawerView;
    private RecentsView recentsView;
    private NavWidgetView navWidgetView;
    private MusicWidgetView musicWidgetView;

    private boolean drawerVisible = true;
    private boolean recentsVisible = false;
    private boolean activityStopped = false;
    private boolean hasLaunchedAnyApp = false;
    private float lastCpuTempC = -1f;

    private final Runnable cpuTempPollRunnable = new Runnable() {
        @Override
        public void run() {
            final int mode = prefs != null ? prefs.getCpuTempMode() : LauncherPrefs.CPU_TEMP_OFF;
            if (mode == LauncherPrefs.CPU_TEMP_OFF) {
                applyCpuTempUi(mode, -1f);
                return;
            }
            bgExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    final float tempC = viewport != null ? viewport.getCpuTempCelsius() : -1f;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (tempC > 0f) lastCpuTempC = tempC;
                            int curMode = prefs != null ? prefs.getCpuTempMode() : LauncherPrefs.CPU_TEMP_OFF;
                            applyCpuTempUi(curMode, lastCpuTempC);
                            if (curMode != LauncherPrefs.CPU_TEMP_OFF) {
                                handler.removeCallbacks(cpuTempPollRunnable);
                                handler.postDelayed(cpuTempPollRunnable, 3000L);
                            }
                        }
                    });
                }
            });
        }
    };

    private final BroadcastReceiver sysReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String act = intent.getAction();
            if (Intent.ACTION_TIME_TICK.equals(act)
                || Intent.ACTION_TIME_CHANGED.equals(act)
                || Intent.ACTION_DATE_CHANGED.equals(act)) {
                if (dockView != null) dockView.refreshClockAndStatus();
                if (navWidgetView != null) navWidgetView.refreshDate();
            } else if (ConnectivityManager.CONNECTIVITY_ACTION.equals(act)
                || BluetoothAdapter.ACTION_STATE_CHANGED.equals(act)) {
                if (dockView != null) dockView.refreshClockAndStatus();
            }
        }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (appDrawerView != null) appDrawerView.reloadApps(prefs);
            if (dockView != null) dockView.setDockPackages(prefs.getDockPackages());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        applyImmersiveMode();

        prefs = new LauncherPrefs(this);
        CockpitDialog.setVisibilityListener(new CockpitDialog.VisibilityListener() {
            @Override
            public void onOpenDialogCountChanged(int openCount) {
                updateViewportTouchProxy();
            }
        });
        buildCockpitUi();

        amapBridge = new AMapBridge(this, new AMapBridge.Listener() {
            @Override
            public void onNavStateChanged(AMapBridge.NavState state) {
                if (navWidgetView != null) navWidgetView.updateNavState(state);
            }

            @Override
            public void onHomeCompanyNotSet(boolean isCompany) {
                Toast.makeText(
                    LauncherActivity.this,
                    isCompany ? "高德地图未设置「公司」，请先在高德中设置" : "高德地图未设置「家」，请先在高德中设置",
                    Toast.LENGTH_LONG
                ).show();
            }
        });
        amapBridge.start();

        mediaHub = new MediaHub(this, new MediaHub.Listener() {
            @Override
            public void onMediaStateChanged(MediaHub.MediaState state) {
                if (musicWidgetView != null) musicWidgetView.updateMediaState(state);
                if (dockView != null && state != null) dockView.setBtConnected(state.btA2dpConnected);
            }
        });
        mediaHub.start();

        IntentFilter sf = new IntentFilter();
        sf.addAction(Intent.ACTION_TIME_TICK);
        sf.addAction(Intent.ACTION_TIME_CHANGED);
        sf.addAction(Intent.ACTION_DATE_CHANGED);
        sf.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        sf.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(sysReceiver, sf);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(pkgReceiver, pf);
    }

    private void buildCockpitUi() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int sw = Math.max(dm.widthPixels, dm.heightPixels);
        int sh = Math.min(dm.widthPixels, dm.heightPixels);

        // Exact Scheme B pixel geometry at 1920x720, proportionally scaled if resolution differs
        final int padTop = (sh == 720) ? 9 : Math.max(6, Math.round(sh * 9f / 720f));
        final int padX = (sw == 1920) ? 12 : Math.max(8, Math.round(sw * 12f / 1920f));
        final int gap = padX;
        final int contentH = sh - padTop * 2; // 702 on 720p

        // Strictly 16:9 center width!
        int centerW = Math.round(contentH * 16f / 9f); // 1248 on 702px height (exact!)
        if (centerW % 2 != 0) centerW--;
        int dockW = (sw == 1920) ? 112 : Math.max(92, Math.round(sw * 112f / 1920f));
        int rightW = sw - padX * 2 - dockW - centerW - gap * 2;
        if (rightW < 340) {
            // Shrink center slightly while preserving exact 16:9 if screen is narrower than 24:9
            rightW = Math.max(300, sw / 4);
            centerW = sw - padX * 2 - dockW - rightW - gap * 2;
        }
        int rightCardH = (contentH - gap) / 2; // 345 on 702px height!

        Log.i(TAG, "Cockpit layout sw=" + sw + " sh=" + sh + " dock=" + dockW + "x" + contentH
            + " center=" + centerW + "x" + contentH + " (ratio=" + String.format("%.4f", (float) centerW / contentH)
            + ") right=" + rightW + "x" + rightCardH);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiUtil.BG_CANVAS);

        // 1. Left Dock (112x702)
        dockView = new DockView(this, new DockView.Callbacks() {
            @Override
            public void onDockAppClicked(int slot, String pkg) {
                openAppInViewport(pkg);
            }

            @Override
            public void onDockAppLongClicked(int slot) {
                showDockSlotPicker(slot);
            }

            @Override
            public void onHomeToggleClicked() {
                if (recentsVisible) setRecentsVisible(false);
                if (drawerVisible && hasLaunchedAnyApp) {
                    setDrawerVisible(false);
                } else {
                    setDrawerVisible(true);
                }
            }

            @Override
            public void onBackClicked() {
                if (recentsVisible) {
                    setRecentsVisible(false);
                    if (!hasLaunchedAnyApp) setDrawerVisible(true);
                } else if (drawerVisible) {
                    if (hasLaunchedAnyApp) setDrawerVisible(false);
                } else if (!hasLaunchedAnyApp) {
                    setDrawerVisible(true);
                } else if (viewport != null) {
                    viewport.sendBackKey();
                }
            }

            @Override
            public void onBackLongClicked() {
                setRecentsVisible(!recentsVisible);
            }

            @Override
            public void onVoiceClicked() {
                triggerVoiceAssistant();
            }

            @Override
            public void onSettingsLongClicked() {
                showLauncherSettingsDialog();
            }
        });
        dockView.setDockPackages(prefs.getDockPackages());
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(dockW, contentH);
        dockLp.leftMargin = padX;
        dockLp.topMargin = padTop;
        root.addView(dockView, dockLp);

        // 2. Center 16:9 Main App Viewport (1248x702)
        centerContainer = new FrameLayout(this);
        centerContainer.setBackgroundColor(UiUtil.BG_CANVAS);
        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(centerW, contentH);
        centerLp.leftMargin = padX + dockW + gap;
        centerLp.topMargin = padTop;
        root.addView(centerContainer, centerLp);

        viewport = new AppViewportManager(this, centerContainer, prefs, new AppViewportManager.Listener() {
            @Override
            public void onViewportReady(int virtualDisplayId) {
                refreshCpuTempMonitor();
                String autoPkg = prefs.getAutoLaunchPackage();
                if (!TextUtils.isEmpty(autoPkg) && !hasLaunchedAnyApp) {
                    openAppInViewport(autoPkg);
                }
            }

            @Override
            public void onActiveAppChanged(final String packageName) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (dockView != null) dockView.setActiveState(packageName, drawerVisible);
                    }
                });
            }

            @Override
            public void onAppConfinedFromMainDisplay(final String packageName) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        hasLaunchedAnyApp = true;
                        setDrawerVisible(false);
                        applyImmersiveMode();
                    }
                });
            }

            @Override
            public void onViewportEmpty() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        hasLaunchedAnyApp = false;
                        if (!drawerVisible && !recentsVisible) {
                            setDrawerVisible(true);
                        } else if (dockView != null) {
                            dockView.setActiveState("", drawerVisible || recentsVisible);
                        }
                    }
                });
            }
        });
        viewport.attach();

        // App Drawer stacked cleanly above ActivityView inside the 1248x702 frame
        appDrawerView = new AppDrawerView(this, new AppDrawerView.Callbacks() {
            @Override
            public void onAppSelected(AppDrawerView.AppEntry app) {
                openAppInViewport(app.packageName);
            }

            @Override
            public void onAppLongPressed(AppDrawerView.AppEntry app) {
                showAppOptionsDialog(app);
            }

            @Override
            public void onOpenSettings() {
                showLauncherSettingsDialog();
            }
        });
        appDrawerView.reloadApps(prefs);
        centerContainer.addView(appDrawerView,
            new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // CarPlay-style 16:9 Recent Apps Switcher stacked cleanly above viewport & drawer
        recentsView = new RecentsView(this, prefs, new RecentsView.Callbacks() {
            @Override
            public void onAppChosen(String packageName) {
                setRecentsVisible(false);
                openAppInViewport(packageName);
            }

            @Override
            public void onTaskRemoved(final int taskId, final String packageName) {
                bgExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (viewport != null) viewport.removeRecentTask(taskId);
                    }
                });
            }

            @Override
            public void onClearAll(final List<AppViewportManager.RecentTaskItem> items) {
                bgExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (viewport != null && items != null) {
                            for (AppViewportManager.RecentTaskItem it : items) {
                                viewport.removeRecentTask(it.taskId);
                            }
                        }
                    }
                });
                hasLaunchedAnyApp = false;
                setRecentsVisible(false);
                setDrawerVisible(true);
                Toast.makeText(LauncherActivity.this, "已清理全部最近任务", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onCloseRequested() {
                setRecentsVisible(false);
                if (!hasLaunchedAnyApp) {
                    setDrawerVisible(true);
                }
            }

            @Override
            public void onOpenDrawerRequested() {
                setRecentsVisible(false);
                setDrawerVisible(true);
            }
        });
        recentsView.setVisibility(View.GONE);
        centerContainer.addView(recentsView,
            new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 3. Top-Right Navigation Widget (512x345)
        int rightLeft = padX + dockW + gap + centerW + gap;
        navWidgetView = new NavWidgetView(this, new NavWidgetView.Callbacks() {
            @Override
            public void onGoHomeClicked() {
                openAppInViewport(AMapBridge.AMAP_PKG);
                amapBridge.navigateHomeOrCompany(false);
            }

            @Override
            public void onGoCompanyClicked() {
                openAppInViewport(AMapBridge.AMAP_PKG);
                amapBridge.navigateHomeOrCompany(true);
            }

            @Override
            public void onOpenMapClicked() {
                openAppInViewport(AMapBridge.AMAP_PKG);
            }

            @Override
            public void onSearchClicked() {
                openAppInViewport(AMapBridge.AMAP_PKG);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        amapBridge.openSearch();
                    }
                }, 600L);
            }
        });
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(rightW, rightCardH);
        navLp.leftMargin = rightLeft;
        navLp.topMargin = padTop;
        root.addView(navWidgetView, navLp);

        // 4. Bottom-Right Music Widget (512x345)
        musicWidgetView = new MusicWidgetView(this, new MusicWidgetView.Callbacks() {
            @Override
            public void onPrevious() {
                mediaHub.previous();
            }

            @Override
            public void onPlayPause() {
                mediaHub.playPause();
            }

            @Override
            public void onNext() {
                mediaHub.next();
            }

            @Override
            public void onOpenMediaSource(String packageName) {
                if (TextUtils.isEmpty(packageName)
                    || "com.carsyso.bluetooth".equals(packageName)
                    || "com.android.bluetooth".equals(packageName)) {
                    // When only BT music (A2DP) is connected without HFP, stock BT music app falsely shows disconnected;
                    // keep playback smooth right from this widget!
                    mediaHub.playPause();
                    return;
                }
                openAppInViewport(packageName);
            }
        });
        FrameLayout.LayoutParams musicLp = new FrameLayout.LayoutParams(rightW, contentH - rightCardH - gap);
        musicLp.leftMargin = rightLeft;
        musicLp.topMargin = padTop + rightCardH + gap;
        root.addView(musicWidgetView, musicLp);

        setContentView(root);
        setDrawerVisible(true);
        refreshCpuTempMonitor();
    }

    private void openAppInViewport(String pkg) {
        if (TextUtils.isEmpty(pkg) || viewport == null) return;
        if (BACK_CAR_PKG.equals(pkg) && isCarLifeMode()) {
            requestCarLifeBackToHu();
            return;
        }
        hasLaunchedAnyApp = true;
        setRecentsVisible(false);
        setDrawerVisible(false);
        boolean ok = viewport.launchPackage(pkg);
        if (!ok && viewport.isReady()) {
            Toast.makeText(this, "未找到应用：" + pkg, Toast.LENGTH_SHORT).show();
        }
    }

    // OEM "原车" app: it asks MainAiBox to return the head unit to its own UI, which is a CarPlay
    // (reverse CarPlay) request and does nothing in CarLife mode. There MdMain sends the CarLife
    // MSG_CMD_GO_TO_DESKTOP instead, requested over its local control socket.
    private static final String BACK_CAR_PKG = "com.ds.backcar";
    private static final String MD_CONTROL_SOCKET = "tbox_carlife_md";

    private static String sysProp(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class).invoke(null, key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean isCarLifeMode() {
        return "1".equals(sysProp("persist.tbox.carlife", "0"))
            && !"1".equals(sysProp("persist.sdcarplay.reverse.disable", "0"));
    }

    private void requestCarLifeBackToHu() {
        bgExecutor.execute(new Runnable() {
            @Override
            public void run() {
                String reply;
                LocalSocket socket = new LocalSocket();
                try {
                    socket.connect(new LocalSocketAddress(MD_CONTROL_SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
                    socket.setSoTimeout(1500);
                    OutputStream out = socket.getOutputStream();
                    out.write("back_to_hu\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    InputStream in = socket.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    int b;
                    while (sb.length() < 32 && (b = in.read()) != -1 && b != '\n') sb.append((char) b);
                    reply = sb.toString().trim();
                } catch (Throwable t) {
                    reply = "unreachable";
                    Log.w(TAG, "back_to_hu: MdMain control socket unavailable", t);
                } finally {
                    try { socket.close(); } catch (Throwable ignored) {}
                }
                Log.i(TAG, "back_to_hu reply=" + reply);
                if (!"ok".equals(reply)) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(LauncherActivity.this, "CarLife 未连接车机，无法返回原车", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    private void setDrawerVisible(boolean show) {
        if (show && recentsVisible) setRecentsVisible(false);
        drawerVisible = show;
        if (appDrawerView != null) {
            appDrawerView.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (dockView != null) {
            dockView.setActiveState(viewport != null ? viewport.getCurrentAppPackage() : "", show || recentsVisible);
        }
        updateViewportTouchProxy();
        if (show && viewport != null) {
            viewport.focusLauncherHomeStack();
        }
    }

    private void setRecentsVisible(boolean show) {
        recentsVisible = show;
        if (recentsView != null) {
            if (show) {
                recentsView.setVisibility(View.VISIBLE);
                recentsView.reload(viewport, viewport != null ? viewport.getCurrentAppPackage() : "");
            } else {
                recentsView.setVisibility(View.GONE);
            }
        }
        if (dockView != null) {
            dockView.setActiveState(viewport != null ? viewport.getCurrentAppPackage() : "", drawerVisible || show);
        }
        updateViewportTouchProxy();
        if (show && viewport != null) {
            viewport.focusLauncherHomeStack();
        }
    }

    private void updateViewportTouchProxy() {
        if (viewport != null) {
            boolean blocked = activityStopped || drawerVisible || recentsVisible
                || CockpitDialog.getOpenDialogCount() > 0;
            viewport.setTouchProxyBlocked(blocked);
        }
    }

    private void refreshCpuTempMonitor() {
        handler.removeCallbacks(cpuTempPollRunnable);
        int mode = prefs != null ? prefs.getCpuTempMode() : LauncherPrefs.CPU_TEMP_OFF;
        applyCpuTempUi(mode, lastCpuTempC);
        if (mode != LauncherPrefs.CPU_TEMP_OFF) {
            handler.post(cpuTempPollRunnable);
        }
    }

    private void applyCpuTempUi(int mode, float tempC) {
        if (dockView != null) {
            dockView.setCpuTemp(mode == LauncherPrefs.CPU_TEMP_DOCK, tempC);
        }
        if (navWidgetView != null) {
            navWidgetView.setCpuTemp(mode == LauncherPrefs.CPU_TEMP_NAV, tempC);
        }
    }

    private void triggerVoiceAssistant() {
        try {
            Intent i1 = new Intent("com.texustek.main.ext.voice.assist");
            sendBroadcast(i1);
            Intent i2 = new Intent("com.aispeech.lyra.action.TOGGLE_SPEECH");
            sendBroadcast(i2);
            Toast.makeText(this, "唤醒语音助手", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "triggerVoiceAssistant error", t);
        }
    }

    private void showDockSlotPicker(final int slot) {
        final List<AppDrawerView.AppEntry> all = appDrawerView.getApps();
        if (all.isEmpty()) return;
        String[] dockPkgs = prefs.getDockPackages();
        String curPkg = (dockPkgs != null && slot < dockPkgs.length) ? dockPkgs[slot] : "";
        final CockpitDialog.Handle h = CockpitDialog.create(
            this, 680, null, CockpitDialog.ICON_SLIDERS, UiUtil.ACCENT_BLUE,
            "设置 Dock 第 " + (slot + 1) + " 位快捷应用",
            "点击下方应用图标即可替换该快捷槽位",
            "Dock " + (slot + 1)
        );
        h.body.addView(CockpitDialog.makeAppGridPicker(this, all, curPkg, new CockpitDialog.AppPickCallback() {
            @Override
            public void onAppPicked(AppDrawerView.AppEntry app) {
                prefs.setDockPackage(slot, app.packageName);
                dockView.setDockPackages(prefs.getDockPackages());
                h.dismiss();
                Toast.makeText(LauncherActivity.this, "已固定「" + app.label + "」到 Dock " + (slot + 1), Toast.LENGTH_SHORT).show();
            }
        }));
        h.show();
    }

    private void showAppOptionsDialog(final AppDrawerView.AppEntry app) {
        final boolean canUninstall = isUserInstalledApp(app.packageName);
        int curDpi = prefs.getAppDpiOverride(app.packageName);
        String dpiPill = curDpi > 0 ? (curDpi + " dpi") : "默认 238";
        boolean isAutoLaunch = app.packageName.equals(prefs.getAutoLaunchPackage());

        final CockpitDialog.Handle h = CockpitDialog.create(
            this, 640, app.icon, 0, 0,
            app.label,
            app.packageName,
            curDpi > 0 ? (curDpi + " dpi") : null
        );

        h.body.addView(CockpitDialog.makeSectionLabel(this, "固定到左侧 Dock 快捷栏"));
        h.body.addView(CockpitDialog.makeDockSlotBar(this, prefs.getDockPackages(), app.packageName,
            new CockpitDialog.SlotCallback() {
                @Override
                public void onSlotSelected(int slotIndex) {
                    prefs.setDockPackage(slotIndex, app.packageName);
                    dockView.setDockPackages(prefs.getDockPackages());
                    h.dismiss();
                    Toast.makeText(LauncherActivity.this,
                        "已固定「" + app.label + "」到 Dock " + (slotIndex + 1), Toast.LENGTH_SHORT).show();
                }
            }));

        h.body.addView(CockpitDialog.makeSectionLabel(this, "显示与启动选项"));
        h.body.addView(CockpitDialog.makeActionRow(
            this, CockpitDialog.ICON_SCALE, UiUtil.ACCENT_BLUE,
            "调整显示缩放 / DPI",
            "独立缩放界面字体与布局比例，不影响其它应用",
            dpiPill, UiUtil.ACCENT_BLUE, false,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    h.dismiss();
                    showAppDpiDialog(app.packageName, app.label, app.icon);
                }
            }
        ));

        h.body.addView(CockpitDialog.makeActionRow(
            this, CockpitDialog.ICON_BOOT, isAutoLaunch ? UiUtil.ACCENT_GREEN : UiUtil.ACCENT_AMBER,
            isAutoLaunch ? "已设为开机默认打开" : "设为开机默认在主区打开",
            isAutoLaunch ? "点击恢复为开机显示应用抽屉" : "开机自动在 16:9 主显示区启动此应用",
            isAutoLaunch ? "当前默认" : null, UiUtil.ACCENT_GREEN, false,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    h.dismiss();
                    if (isAutoLaunch) {
                        prefs.setAutoLaunchPackage("");
                        Toast.makeText(LauncherActivity.this, "已取消开机默认，开机将显示应用抽屉", Toast.LENGTH_SHORT).show();
                    } else {
                        prefs.setAutoLaunchPackage(app.packageName);
                        Toast.makeText(LauncherActivity.this, "开机将自动在 16:9 主区打开「" + app.label + "」", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        ));

        if (canUninstall) {
            h.body.addView(CockpitDialog.makeActionRow(
                this, CockpitDialog.ICON_TRASH, 0xFFF87171,
                "卸载应用",
                "从车机盒子中永久删除此第三方应用",
                null, 0, true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        h.dismiss();
                        confirmUninstallApp(app);
                    }
                }
            ));
        }

        h.show();
    }

    private void showAppDpiDialog(final String pkg, final String label, Bitmap icon) {
        if (TextUtils.isEmpty(pkg)) return;
        Bitmap appIcon = icon != null ? icon : CockpitDialog.loadRoundedIcon(this, pkg);
        int cur = prefs.getAppDpiOverride(pkg);
        final CockpitDialog.Handle h = CockpitDialog.create(
            this, 680, appIcon, CockpitDialog.ICON_SCALE, UiUtil.ACCENT_BLUE,
            "「" + label + "」显示缩放",
            "虚拟分辨率硬件缩放 · 选择后仅重启此应用立即生效",
            cur > 0 ? (cur + " dpi") : "默认 238"
        );
        h.body.addView(CockpitDialog.makeDpiGrid(this, cur, new CockpitDialog.DpiCallback() {
            @Override
            public void onDpiSelected(int chosen) {
                prefs.setAppDpiOverride(pkg, chosen);
                h.dismiss();
                if (viewport != null) {
                    viewport.forceStopPackage(pkg);
                }
                Toast.makeText(
                    LauncherActivity.this,
                    "已将「" + label + "」设为 " + (chosen > 0 ? (chosen + " dpi") : "默认 238 dpi") + "，正在重启应用…",
                    Toast.LENGTH_SHORT
                ).show();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        openAppInViewport(pkg);
                    }
                }, 250L);
            }
        }));
        h.show();
    }

    private boolean isUserInstalledApp(String pkg) {
        if (TextUtils.isEmpty(pkg) || "com.tbox.carlauncher".equals(pkg) || "com.tbox.usbswitch".equals(pkg)) {
            return false;
        }
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void confirmUninstallApp(final AppDrawerView.AppEntry app) {
        final CockpitDialog.Handle h = CockpitDialog.create(
            this, 560, app.icon, CockpitDialog.ICON_TRASH, 0xFFF87171,
            "确定卸载「" + app.label + "」？",
            app.packageName,
            null
        );
        TextView msg = new TextView(this);
        msg.setText("卸载后将移除该应用及其本地数据，确认要继续删除吗？");
        msg.setTextColor(0xFFCBD5E1);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f);
        msg.setLineSpacing(6f, 1f);
        h.body.addView(msg);

        h.body.addView(CockpitDialog.makeButtonBar(
            this, "取消", "确定卸载", true,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    h.dismiss();
                }
            },
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    h.dismiss();
                    Toast.makeText(LauncherActivity.this, "正在卸载「" + app.label + "」…", Toast.LENGTH_SHORT).show();
                    bgExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            final boolean ok = viewport != null && viewport.uninstallUserPackage(app.packageName);
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (ok) {
                                        if (app.packageName.equals(prefs.getAutoLaunchPackage())) {
                                            prefs.setAutoLaunchPackage("");
                                        }
                                        if (appDrawerView != null) appDrawerView.reloadApps(prefs);
                                        if (dockView != null) dockView.setDockPackages(prefs.getDockPackages());
                                        Toast.makeText(LauncherActivity.this, "已卸载「" + app.label + "」", Toast.LENGTH_SHORT).show();
                                    } else {
                                        try {
                                            Intent del = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.packageName));
                                            del.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                            startActivity(del);
                                        } catch (Throwable t) {
                                            Toast.makeText(LauncherActivity.this, "卸载失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                }
                            });
                        }
                    });
                }
            }
        ));
        h.show();
    }

    private void showLauncherSettingsDialog() {
        String auto = prefs.getAutoLaunchPackage();
        final String autoLabel = TextUtils.isEmpty(auto) ? "应用抽屉" : UiUtil.getAppLabel(this, auto);
        final String curPkg = viewport != null ? viewport.getCurrentAppPackage() : "";
        final String curLabel = !TextUtils.isEmpty(curPkg) ? UiUtil.getAppLabel(this, curPkg) : "";
        int curDpi = !TextUtils.isEmpty(curPkg)
            ? prefs.getEffectiveAppDpi(curPkg, LauncherPrefs.DEFAULT_DPI) : LauncherPrefs.DEFAULT_DPI;

        final CockpitDialog.Handle h = CockpitDialog.create(
            this, 710, null, CockpitDialog.ICON_GEAR, UiUtil.ACCENT_BLUE,
            "车机桌面设置",
            "1920×720 16:9 驾舱桌面 · 长按时钟随时打开",
            null
        );

        h.body.addView(CockpitDialog.makeSegmentedRow(
            this, CockpitDialog.ICON_TEMP, 0xFF38BDF8,
            "CPU 温度显示",
            "实时监测盒子芯片温度",
            new String[]{"关闭", "左侧 Dock", "导航卡片"},
            prefs.getCpuTempMode(),
            new CockpitDialog.SegmentCallback() {
                @Override
                public void onSegmentSelected(int index) {
                    prefs.setCpuTempMode(index);
                    refreshCpuTempMonitor();
                }
            }
        ));

        h.body.addView(CockpitDialog.makeSegmentedRow(
            this, CockpitDialog.ICON_SCALE, 0xFF34D399,
            "CarLife 投屏帧率",
            "60fps 更流畅但发热略高 · 下次重连 CarLife 生效",
            new String[]{"30 fps", "60 fps"},
            getCarLifeFps() == 60 ? 1 : 0,
            new CockpitDialog.SegmentCallback() {
                @Override
                public void onSegmentSelected(int index) {
                    int fps = (index == 1) ? 60 : 30;
                    applyCarLifeFps(fps);
                    Toast.makeText(LauncherActivity.this,
                        "已设为 CarLife " + fps + " fps，下次重连或重启生效", Toast.LENGTH_SHORT).show();
                }
            }
        ));

        if (!TextUtils.isEmpty(curPkg)) {
            h.body.addView(CockpitDialog.makeActionRow(
                this, CockpitDialog.ICON_SCALE, UiUtil.ACCENT_BLUE,
                "调整「" + curLabel + "」显示缩放",
                "单独设置当前主区应用的界面字体与显示比例",
                curDpi + " dpi", UiUtil.ACCENT_BLUE, false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        h.dismiss();
                        showAppDpiDialog(curPkg, curLabel, null);
                    }
                }
            ));
        }

        h.body.addView(CockpitDialog.makeActionRow(
            this, CockpitDialog.ICON_BOOT, UiUtil.ACCENT_AMBER,
            "开机默认主区显示",
            "点击在「高德地图」与「应用抽屉」之间切换",
            autoLabel, UiUtil.ACCENT_AMBER, false,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    h.dismiss();
                    if (TextUtils.isEmpty(prefs.getAutoLaunchPackage())) {
                        prefs.setAutoLaunchPackage(AMapBridge.AMAP_PKG);
                        Toast.makeText(LauncherActivity.this, "开机默认打开：高德地图", Toast.LENGTH_SHORT).show();
                    } else {
                        prefs.setAutoLaunchPackage("");
                        Toast.makeText(LauncherActivity.this, "开机默认显示：应用抽屉", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        ));

        h.body.addView(CockpitDialog.makeActionRow(
            this, CockpitDialog.ICON_USB, 0xFFA78BFA,
            "USB 模式与音频通道切换",
            "打开 TboxUsbSwitch（CarLife / CarPlay / 音频分流）",
            null, 0, false,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    h.dismiss();
                    openAppInViewport("com.tbox.usbswitch");
                }
            }
        ));

        h.body.addView(CockpitDialog.makeActionRow(
            this, CockpitDialog.ICON_SLIDERS, UiUtil.ACCENT_GREEN,
            "Android 系统设置",
            "管理 Wi-Fi、蓝牙、应用与设备系统选项",
            null, 0, false,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    h.dismiss();
                    openAppInViewport("com.android.settings");
                }
            }
        ));

        h.show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        applyImmersiveMode();
        // Pressing system HOME while already in launcher toggles App Drawer cleanly
        if (Intent.ACTION_MAIN.equals(intent != null ? intent.getAction() : null)) {
            if (!drawerVisible) {
                setDrawerVisible(true);
            } else if (hasLaunchedAnyApp) {
                setDrawerVisible(false);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStopped = false;
        updateViewportTouchProxy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        if (appDrawerView != null) appDrawerView.reloadApps(prefs);
        if (dockView != null) {
            dockView.setDockPackages(prefs.getDockPackages());
            dockView.refreshClockAndStatus();
        }
        if (navWidgetView != null) navWidgetView.refreshDate();
        if (viewport != null) {
            viewport.setActive(true);
            viewport.onLocationChanged();
        }
        updateViewportTouchProxy();
        refreshCpuTempMonitor();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(cpuTempPollRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        activityStopped = true;
        updateViewportTouchProxy();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        CockpitDialog.setVisibilityListener(null);
        handler.removeCallbacksAndMessages(null);
        bgExecutor.shutdownNow();
        try { unregisterReceiver(sysReceiver); } catch (Throwable ignored) {}
        try { unregisterReceiver(pkgReceiver); } catch (Throwable ignored) {}
        if (amapBridge != null) amapBridge.stop();
        if (mediaHub != null) mediaHub.stop();
        if (musicWidgetView != null) musicWidgetView.release();
        if (recentsView != null) recentsView.release();
        if (viewport != null) viewport.release();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
            if (viewport != null) viewport.onLocationChanged();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event != null && (event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
                // Never re-forward synthetic Back keys injected by VdInputBridge back into VdInputBridge!
                return true;
            }
            if (recentsVisible) {
                setRecentsVisible(false);
                if (!hasLaunchedAnyApp) setDrawerVisible(true);
            } else if (drawerVisible && hasLaunchedAnyApp) {
                setDrawerVisible(false);
            } else if (!drawerVisible && !hasLaunchedAnyApp) {
                setDrawerVisible(true);
            } else if (!drawerVisible && viewport != null) {
                viewport.sendBackKey();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void applyImmersiveMode() {
        setGlobalImmersive(true);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    private int getCarLifeFps() {
        try {
            String cfg = Settings.Global.getString(getContentResolver(), "carlife_video_config");
            if (cfg != null && cfg.endsWith(":60")) return 60;
            if (cfg != null && cfg.endsWith(":30")) return 30;
        } catch (Throwable ignored) {}
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class, String.class)
                .invoke(null, "persist.tbox.carlife.fps", "30");
            if ("60".equals(v)) return 60;
        } catch (Throwable ignored) {}
        return 30;
    }

    private void applyCarLifeFps(int fps) {
        int clean = (fps == 60) ? 60 : 30;
        try {
            Settings.Global.putString(getContentResolver(), "carlife_video_config", "v1:auto:" + clean);
        } catch (Throwable ignored) {}
        if (viewport != null) {
            viewport.setCarLifeFps(clean);
        }
    }

    private void setGlobalImmersive(boolean enable) {
        try {
            Settings.Global.putString(getContentResolver(), "policy_control",
                enable ? "immersive.full=*" : "");
        } catch (Throwable ignored) {}
    }
}
