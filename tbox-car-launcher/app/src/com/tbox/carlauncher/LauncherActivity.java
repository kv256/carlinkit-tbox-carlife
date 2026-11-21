package com.tbox.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.List;

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
    private LauncherPrefs prefs;
    private AMapBridge amapBridge;
    private MediaHub mediaHub;
    private AppViewportManager viewport;

    private DockView dockView;
    private FrameLayout centerContainer;
    private AppDrawerView appDrawerView;
    private NavWidgetView navWidgetView;
    private MusicWidgetView musicWidgetView;

    private boolean drawerVisible = true;
    private boolean hasLaunchedAnyApp = false;

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
                if (drawerVisible && hasLaunchedAnyApp) {
                    setDrawerVisible(false);
                } else {
                    setDrawerVisible(true);
                }
            }

            @Override
            public void onBackClicked() {
                if (drawerVisible) {
                    if (hasLaunchedAnyApp) setDrawerVisible(false);
                } else if (viewport != null) {
                    viewport.sendBackKey();
                }
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

        viewport = new AppViewportManager(this, centerContainer, new AppViewportManager.Listener() {
            @Override
            public void onViewportReady(int virtualDisplayId) {
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
    }

    private void openAppInViewport(String pkg) {
        if (TextUtils.isEmpty(pkg) || viewport == null) return;
        hasLaunchedAnyApp = true;
        setDrawerVisible(false);
        boolean ok = viewport.launchPackage(pkg);
        if (!ok && viewport.isReady()) {
            Toast.makeText(this, "未找到应用：" + pkg, Toast.LENGTH_SHORT).show();
        }
    }

    private void setDrawerVisible(boolean show) {
        drawerVisible = show;
        if (appDrawerView != null) {
            appDrawerView.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (dockView != null) {
            dockView.setActiveState(viewport != null ? viewport.getCurrentAppPackage() : "", show);
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
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            names[i] = all.get(i).label;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("设置左侧 Dock 第 " + (slot + 1) + " 个快捷应用")
            .setItems(names, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    prefs.setDockPackage(slot, all.get(which).packageName);
                    dockView.setDockPackages(prefs.getDockPackages());
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showAppOptionsDialog(final AppDrawerView.AppEntry app) {
        final boolean canUninstall = isUserInstalledApp(app.packageName);
        String[] items = canUninstall
            ? new String[]{
                "固定到左侧 Dock 第 1 位",
                "固定到左侧 Dock 第 2 位",
                "固定到左侧 Dock 第 3 位",
                "固定到左侧 Dock 第 4 位",
                "设为开机默认在主区打开",
                "卸载应用（删除）"
            }
            : new String[]{
                "固定到左侧 Dock 第 1 位",
                "固定到左侧 Dock 第 2 位",
                "固定到左侧 Dock 第 3 位",
                "固定到左侧 Dock 第 4 位",
                "设为开机默认在主区打开"
            };
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(app.label)
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    if (which < 4) {
                        prefs.setDockPackage(which, app.packageName);
                        dockView.setDockPackages(prefs.getDockPackages());
                        Toast.makeText(LauncherActivity.this, "已固定 " + app.label + " 到 Dock " + (which + 1), Toast.LENGTH_SHORT).show();
                    } else if (which == 4) {
                        prefs.setAutoLaunchPackage(app.packageName);
                        Toast.makeText(LauncherActivity.this, "开机将自动在 16:9 主区打开 " + app.label, Toast.LENGTH_SHORT).show();
                    } else if (which == 5 && canUninstall) {
                        confirmUninstallApp(app);
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
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
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("卸载应用")
            .setMessage("确定要删除「" + app.label + "」(" + app.packageName + ") 吗？")
            .setPositiveButton("确定卸载", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    boolean ok = viewport != null && viewport.uninstallUserPackage(app.packageName);
                    if (ok) {
                        Toast.makeText(LauncherActivity.this, "正在卸载 " + app.label + "…", Toast.LENGTH_SHORT).show();
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (appDrawerView != null) appDrawerView.reloadApps(prefs);
                                if (dockView != null) dockView.setDockPackages(prefs.getDockPackages());
                            }
                        }, 1800L);
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
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showLauncherSettingsDialog() {
        String auto = prefs.getAutoLaunchPackage();
        String autoLabel = TextUtils.isEmpty(auto) ? "显示应用网格" : UiUtil.getAppLabel(this, auto);
        String[] items = new String[]{
            "开机默认主区：" + autoLabel + "（点击切换为应用网格 / 高德）",
            "打开 USB / 音频切换工具 (TboxUsbSwitch)",
            "打开系统设置"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("CarPlay 车机桌面设置")
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    if (which == 0) {
                        if (TextUtils.isEmpty(prefs.getAutoLaunchPackage())) {
                            prefs.setAutoLaunchPackage(AMapBridge.AMAP_PKG);
                            Toast.makeText(LauncherActivity.this, "开机默认打开：高德地图", Toast.LENGTH_SHORT).show();
                        } else {
                            prefs.setAutoLaunchPackage("");
                            Toast.makeText(LauncherActivity.this, "开机默认显示：应用抽屉", Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == 1) {
                        openAppInViewport("com.tbox.usbswitch");
                    } else if (which == 2) {
                        openAppInViewport("com.android.settings");
                    }
                }
            })
            .setNegativeButton("关闭", null)
            .show();
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
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        if (dockView != null) dockView.refreshClockAndStatus();
        if (navWidgetView != null) navWidgetView.refreshDate();
        if (viewport != null) {
            viewport.setActive(true);
            viewport.onLocationChanged();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(sysReceiver); } catch (Throwable ignored) {}
        try { unregisterReceiver(pkgReceiver); } catch (Throwable ignored) {}
        if (amapBridge != null) amapBridge.stop();
        if (mediaHub != null) mediaHub.stop();
        if (musicWidgetView != null) musicWidgetView.release();
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
            if (drawerVisible && hasLaunchedAnyApp) {
                setDrawerVisible(false);
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

    private void setGlobalImmersive(boolean enable) {
        try {
            Settings.Global.putString(getContentResolver(), "policy_control",
                enable ? "immersive.full=*" : "");
        } catch (Throwable ignored) {}
    }
}
