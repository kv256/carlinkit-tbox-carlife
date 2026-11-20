package com.tbox.usbswitch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int MODE_CARPLAY = 0;
    private static final int MODE_CARLIFE = 1;
    private static final int MODE_USB_ADB = 2;

    private static final String PROP_AUDIO_MEDIA = "persist.tbox.carlife.audio.media";
    private static final String PROP_AUDIO_NAVI = "persist.tbox.carlife.audio.navi";
    private static final String PROP_AUDIO_CALL = "persist.tbox.carlife.audio.call";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView modeBadge;
    private TextView statusDetails;
    private TextView rootStatusView;
    private TextView wifiBtnTitle;
    private TextView wifiBtnSub;
    private LinearLayout wifiCard;

    private TextView mediaUsbBtn, mediaBtBtn;
    private TextView naviUsbBtn, naviBtBtn;
    private TextView callUsbBtn, callBtBtn;

    private volatile int currentMode = MODE_CARPLAY;
    private volatile boolean currentWifiAdbOn = false;
    private volatile boolean hasRoot = false;
    private volatile boolean carlifeInstalled = false;
    private volatile String currentWifiIp = "";
    private volatile boolean mediaIsUsb = true;
    private volatile boolean naviIsUsb = true;
    private volatile boolean callIsUsb = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable roundRect(int fillColor, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            d.setStroke(dp(strokeDp), strokeColor);
        }
        return d;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#0F172A"));
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        // ===== LEFT PANEL: Live Status Card =====
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackground(roundRect(Color.parseColor("#1E293B"), 16, Color.parseColor("#334155"), 1));
        left.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.35f);
        leftLp.setMargins(0, 0, dp(12), 0);
        left.setLayoutParams(leftLp);

        TextView title = new TextView(this);
        title.setText("USB 模式切换");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Carlinkit Tbox 三模式与音频路由 · v1.3");
        sub.setTextColor(Color.parseColor("#94A3B8"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setPadding(0, dp(2), 0, dp(10));
        left.addView(sub);

        modeBadge = new TextView(this);
        modeBadge.setText("读取状态中...");
        modeBadge.setTextColor(Color.WHITE);
        modeBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        modeBadge.setTypeface(Typeface.DEFAULT_BOLD);
        modeBadge.setGravity(Gravity.CENTER);
        modeBadge.setPadding(dp(10), dp(8), dp(10), dp(8));
        modeBadge.setBackground(roundRect(Color.parseColor("#334155"), 10, 0, 0));
        left.addView(modeBadge);

        statusDetails = new TextView(this);
        statusDetails.setText("正在读取系统属性...");
        statusDetails.setTextColor(Color.parseColor("#E2E8F0"));
        statusDetails.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        statusDetails.setLineSpacing(dp(3), 1.0f);
        statusDetails.setPadding(0, dp(10), 0, dp(6));
        left.addView(statusDetails);

        rootStatusView = new TextView(this);
        rootStatusView.setText("Root 权限：检测中...");
        rootStatusView.setTextColor(Color.parseColor("#FBBF24"));
        rootStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        rootStatusView.setLineSpacing(dp(2), 1.0f);
        rootStatusView.setPadding(0, dp(2), 0, dp(8));
        left.addView(rootStatusView);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1.0f));
        left.addView(spacer);

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        left.addView(bottomRow);

        TextView refreshBtn = makeSmallButton("刷新状态", Color.parseColor("#334155"));
        refreshBtn.setOnClickListener(v -> refreshStatus());
        LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b1Lp.setMargins(0, 0, dp(6), 0);
        refreshBtn.setLayoutParams(b1Lp);
        bottomRow.addView(refreshBtn);

        TextView magiskBtn = makeSmallButton("打开 Magisk", Color.parseColor("#0F766E"));
        magiskBtn.setOnClickListener(v -> openMagiskApp());
        LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b2Lp.setMargins(dp(6), 0, 0, 0);
        magiskBtn.setLayoutParams(b2Lp);
        bottomRow.addView(magiskBtn);

        root.addView(left);

        // ===== RIGHT PANEL: 3 Rows =====
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.65f));

        // Row 1: 3 USB Mode Cards
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row1Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.00f);
        row1Lp.setMargins(0, 0, 0, dp(10));
        row1.setLayoutParams(row1Lp);

        LinearLayout carplayCard = makeActionCard(
                "CarPlay 模式并重启",
                "原厂 Apple CarPlay 协议\n自动启用 Wi-Fi ADB",
                Color.parseColor("#1D4ED8"),
                Color.parseColor("#3B82F6")
        );
        carplayCard.setOnClickListener(v -> confirmAndSwitchMode(MODE_CARPLAY));
        LinearLayout.LayoutParams c1Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        c1Lp.setMargins(0, 0, dp(8), 0);
        carplayCard.setLayoutParams(c1Lp);
        row1.addView(carplayCard);

        LinearLayout carlifeCard = makeActionCard(
                "CarLife 模式并重启",
                "百度 CarLife USB 互联\n保留车机蓝牙 · 音频可分流",
                Color.parseColor("#6D28D9"),
                Color.parseColor("#8B5CF6")
        );
        carlifeCard.setOnClickListener(v -> confirmAndSwitchMode(MODE_CARLIFE));
        LinearLayout.LayoutParams c2Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        c2Lp.setMargins(0, 0, dp(8), 0);
        carlifeCard.setLayoutParams(c2Lp);
        row1.addView(carlifeCard);

        LinearLayout adbCard = makeActionCard(
                "USB ADB 模式并重启",
                "电脑有线 ADB / scrcpy\n暂停车机互联",
                Color.parseColor("#15803D"),
                Color.parseColor("#22C55E")
        );
        adbCard.setOnClickListener(v -> confirmAndSwitchMode(MODE_USB_ADB));
        LinearLayout.LayoutParams c3Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        adbCard.setLayoutParams(c3Lp);
        row1.addView(adbCard);

        right.addView(row1);

        // Row 2: CarLife Audio Routing Card (Media / Navi / Call -> USB vs Bluetooth, live hot-reload!)
        LinearLayout audioPanel = new LinearLayout(this);
        audioPanel.setOrientation(LinearLayout.VERTICAL);
        audioPanel.setPadding(dp(14), dp(10), dp(14), dp(10));
        audioPanel.setBackground(roundRect(Color.parseColor("#1E293B"), 14, Color.parseColor("#7C3AED"), 1));
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.92f);
        row2Lp.setMargins(0, 0, 0, dp(10));
        audioPanel.setLayoutParams(row2Lp);

        TextView audioTitle = new TextView(this);
        audioTitle.setText("CarLife 音频通道选择（点选后免重启实时生效）");
        audioTitle.setTextColor(Color.parseColor("#DDD6FE"));
        audioTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        audioTitle.setTypeface(Typeface.DEFAULT_BOLD);
        audioTitle.setPadding(0, 0, 0, dp(8));
        audioPanel.addView(audioTitle);

        LinearLayout segRow = new LinearLayout(this);
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        segRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        TextView[] mediaBtns = new TextView[2];
        segRow.addView(makeAudioRouteColumn("媒体音频", "音乐 / 视频 / 提示", PROP_AUDIO_MEDIA, "媒体音频", mediaBtns, true));
        mediaUsbBtn = mediaBtns[0];
        mediaBtBtn = mediaBtns[1];

        TextView[] naviBtns = new TextView[2];
        segRow.addView(makeAudioRouteColumn("导航音频", "高德 / 地图播报", PROP_AUDIO_NAVI, "导航音频", naviBtns, true));
        naviUsbBtn = naviBtns[0];
        naviBtBtn = naviBtns[1];

        TextView[] callBtns = new TextView[2];
        segRow.addView(makeAudioRouteColumn("电话音频", "通话声音 + 通话麦", PROP_AUDIO_CALL, "电话音频", callBtns, false));
        callUsbBtn = callBtns[0];
        callBtBtn = callBtns[1];

        audioPanel.addView(segRow);
        right.addView(audioPanel);

        // Row 3: Wi-Fi ADB toggle | Reboot Bootloader
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row3Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.72f);
        row3.setLayoutParams(row3Lp);

        wifiCard = new LinearLayout(this);
        wifiCard.setOrientation(LinearLayout.VERTICAL);
        wifiCard.setGravity(Gravity.CENTER_VERTICAL);
        wifiCard.setPadding(dp(16), dp(10), dp(16), dp(10));
        wifiCard.setBackground(roundRect(Color.parseColor("#0F766E"), 14, Color.parseColor("#14B8A6"), 1));
        wifiBtnTitle = new TextView(this);
        wifiBtnTitle.setText("立即开启 Wi-Fi ADB (:5555)");
        wifiBtnTitle.setTextColor(Color.WHITE);
        wifiBtnTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        wifiBtnTitle.setTypeface(Typeface.DEFAULT_BOLD);
        wifiCard.addView(wifiBtnTitle);
        wifiBtnSub = new TextView(this);
        wifiBtnSub.setText("免重启即时切换无线调试");
        wifiBtnSub.setTextColor(Color.parseColor("#CCFBF1"));
        wifiBtnSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        wifiBtnSub.setPadding(0, dp(4), 0, 0);
        wifiCard.addView(wifiBtnSub);
        wifiCard.setOnClickListener(v -> toggleWifiAdbNow());
        LinearLayout.LayoutParams c4Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        c4Lp.setMargins(0, 0, dp(8), 0);
        wifiCard.setLayoutParams(c4Lp);
        row3.addView(wifiCard);

        LinearLayout fbCard = makeActionCard(
                "重启到 Fastboot 模式",
                "线刷 / 恢复原版 boot（带二次确认）",
                Color.parseColor("#7C2D12"),
                Color.parseColor("#EA580C")
        );
        fbCard.setOnClickListener(v -> confirmAndRebootFastboot());
        LinearLayout.LayoutParams c5Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        fbCard.setLayoutParams(c5Lp);
        row3.addView(fbCard);

        right.addView(row3);
        root.addView(right);

        setContentView(root);
    }

    private LinearLayout makeAudioRouteColumn(
            String label, String hint, String propKey, String toastLabel,
            TextView[] outBtns, boolean rightMargin) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(dp(10), dp(6), dp(10), dp(6));
        col.setBackground(roundRect(Color.parseColor("#0F172A"), 10, Color.parseColor("#334155"), 1));
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        if (rightMargin) {
            colLp.setMargins(0, 0, dp(8), 0);
        }
        col.setLayoutParams(colLp);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);

        TextView s = new TextView(this);
        s.setText(hint);
        s.setTextColor(Color.parseColor("#94A3B8"));
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        s.setPadding(0, dp(1), 0, dp(6));
        col.addView(s);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView usbBtn = makeToggleBtn("USB");
        usbBtn.setOnClickListener(v -> setAudioRoute(propKey, "usb", toastLabel + " → USB (CarLife)"));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp1.setMargins(0, 0, dp(5), 0);
        usbBtn.setLayoutParams(lp1);
        btnRow.addView(usbBtn);

        TextView btBtn = makeToggleBtn("蓝牙");
        btBtn.setOnClickListener(v -> setAudioRoute(propKey, "bt", toastLabel + " → 蓝牙 (车机/QCOM-BTD)"));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btBtn.setLayoutParams(lp2);
        btnRow.addView(btBtn);

        col.addView(btnRow);
        outBtns[0] = usbBtn;
        outBtns[1] = btBtn;
        return col;
    }

    private TextView makeToggleBtn(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(7), dp(6), dp(7));
        b.setBackground(roundRect(Color.parseColor("#334155"), 8, Color.parseColor("#475569"), 1));
        return b;
    }

    private void styleTogglePair(TextView usbBtn, TextView btBtn, boolean isUsb) {
        if (isUsb) {
            usbBtn.setBackground(roundRect(Color.parseColor("#7C3AED"), 8, Color.parseColor("#A78BFA"), 1));
            usbBtn.setTextColor(Color.WHITE);
            btBtn.setBackground(roundRect(Color.parseColor("#1E293B"), 8, Color.parseColor("#334155"), 1));
            btBtn.setTextColor(Color.parseColor("#94A3B8"));
        } else {
            usbBtn.setBackground(roundRect(Color.parseColor("#1E293B"), 8, Color.parseColor("#334155"), 1));
            usbBtn.setTextColor(Color.parseColor("#94A3B8"));
            btBtn.setBackground(roundRect(Color.parseColor("#0284C7"), 8, Color.parseColor("#38BDF8"), 1));
            btBtn.setTextColor(Color.WHITE);
        }
    }

    private void setAudioRoute(String propKey, String value, String toastMsg) {
        if (!ensureRootOrPrompt()) return;
        runRootCommand("setprop " + propKey + " " + value, toastMsg + "（实时生效）");
    }

    private LinearLayout makeActionCard(String titleText, String subText, int bgColor, int strokeColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(roundRect(bgColor, 14, strokeColor, 1));

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(t);

        TextView s = new TextView(this);
        s.setText(subText);
        s.setTextColor(Color.parseColor("#E2E8F0"));
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        s.setPadding(0, dp(5), 0, 0);
        s.setLineSpacing(dp(2), 1.0f);
        card.addView(s);

        return card;
    }

    private TextView makeSmallButton(String text, int bgColor) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(10), dp(8), dp(10), dp(8));
        btn.setBackground(roundRect(bgColor, 10, Color.parseColor("#475569"), 1));
        return btn;
    }

    private static class ExecResult {
        final int rc;
        final String out;
        ExecResult(int rc, String out) {
            this.rc = rc;
            this.out = out;
        }
    }

    private ExecResult execCmd(String[] cmd) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
            }
            int rc = p.waitFor();
            return new ExecResult(rc, sb.toString().trim());
        } catch (Exception e) {
            return new ExecResult(-1, e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    private String getProp(String key) {
        ExecResult r = execCmd(new String[]{"/system/bin/getprop", key});
        return r.rc == 0 ? r.out.trim() : "";
    }

    private String getWlanIpv4() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    int ip = info.getIpAddress();
                    if (ip != 0) {
                        return String.format(Locale.US, "%d.%d.%d.%d",
                                (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName();
                if (name == null || !name.startsWith("wlan")) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        ExecResult r = execCmd(new String[]{"/system/bin/sh", "-c",
                "ip -4 addr show wlan0 2>/dev/null | sed -n 's/.*inet \\([0-9.]*\\)\\/.*/\\1/p' | head -n 1"});
        return r.rc == 0 ? r.out.trim() : "";
    }

    private void refreshStatus() {
        executor.execute(() -> {
            String revDisable = getProp("persist.sdcarplay.reverse.disable");
            String carlifeProp = getProp("persist.tbox.carlife");
            String pMedia = getProp(PROP_AUDIO_MEDIA);
            String pNavi = getProp(PROP_AUDIO_NAVI);
            String pCall = getProp(PROP_AUDIO_CALL);
            String usbState = getProp("sys.usb.state");
            String usbConfig = getProp("sys.usb.config");
            String tcpPort = getProp("service.adb.tcp.port");
            String adbdSvc = getProp("init.svc.adbd");
            String ip = getWlanIpv4();
            ExecResult idRes = execCmd(new String[]{"su", "-c", "id"});
            boolean rootOk = idRes.rc == 0 && idRes.out.contains("uid=0");

            String clPhase = "";
            boolean clAvail = false;
            if (rootOk) {
                ExecResult clRes = execCmd(new String[]{"su", "-c",
                        "[ -x /data/adb/carlife/carlife-daemon.sh ] && echo INSTALLED; sed -n 's/^phase=//p' /cache/carlife/status 2>/dev/null | head -n 1"});
                if (clRes.rc == 0) {
                    for (String line : clRes.out.split("\n")) {
                        String t = line.trim();
                        if ("INSTALLED".equals(t)) {
                            clAvail = true;
                        } else if (!t.isEmpty()) {
                            clPhase = t;
                        }
                    }
                }
            }

            int mode;
            if ("1".equals(revDisable)) {
                mode = MODE_USB_ADB;
            } else if ("1".equals(carlifeProp)) {
                mode = MODE_CARLIFE;
            } else {
                mode = MODE_CARPLAY;
            }
            boolean wifiOn = "5555".equals(tcpPort) && "running".equals(adbdSvc);
            boolean mUsb = !"bt".equalsIgnoreCase(pMedia);
            boolean nUsb = !"bt".equalsIgnoreCase(pNavi);
            boolean cUsb = !"bt".equalsIgnoreCase(pCall);

            currentMode = mode;
            currentWifiAdbOn = wifiOn;
            hasRoot = rootOk;
            carlifeInstalled = clAvail;
            currentWifiIp = ip;
            mediaIsUsb = mUsb;
            naviIsUsb = nUsb;
            callIsUsb = cUsb;

            final String finalClPhase = clPhase;
            final boolean finalClAvail = clAvail;

            mainHandler.post(() -> {
                if (mode == MODE_USB_ADB) {
                    modeBadge.setText("当前：USB ADB 模式（电脑调试）");
                    modeBadge.setBackground(roundRect(Color.parseColor("#15803D"), 10, 0, 0));
                } else if (mode == MODE_CARLIFE) {
                    modeBadge.setText("当前：CarLife 模式（百度 AOA）");
                    modeBadge.setBackground(roundRect(Color.parseColor("#6D28D9"), 10, 0, 0));
                } else {
                    modeBadge.setText("当前：CarPlay 模式（原厂协议）");
                    modeBadge.setBackground(roundRect(Color.parseColor("#1D4ED8"), 10, 0, 0));
                }

                styleTogglePair(mediaUsbBtn, mediaBtBtn, mUsb);
                styleTogglePair(naviUsbBtn, naviBtBtn, nUsb);
                styleTogglePair(callUsbBtn, callBtBtn, cUsb);

                String ipText = ip.isEmpty() ? "未连接 Wi-Fi" : ip;
                String wifiAdbText = wifiOn
                        ? ("已开启 (" + (ip.isEmpty() ? ":5555" : ip + ":5555") + ")")
                        : ("未开启（adbd=" + (adbdSvc.isEmpty() ? "unknown" : adbdSvc) + "）");
                String usbSummary = !usbState.isEmpty() ? usbState : (usbConfig.isEmpty() ? "-" : usbConfig);

                StringBuilder sb = new StringBuilder();
                sb.append("reverse.disable：").append(revDisable.isEmpty() ? "0" : revDisable)
                  .append("  |  carlife：").append(carlifeProp.isEmpty() ? "0" : carlifeProp).append('\n');
                if (mode == MODE_CARLIFE) {
                    sb.append("CarLife 状态：").append(finalClPhase.isEmpty() ? "未启动" : finalClPhase).append('\n');
                }
                sb.append("音频：媒体 ").append(mUsb ? "USB" : "蓝牙")
                  .append(" · 导航 ").append(nUsb ? "USB" : "蓝牙")
                  .append(" · 电话 ").append(cUsb ? "USB" : "蓝牙").append('\n');
                sb.append("USB 状态：").append(usbSummary).append('\n');
                sb.append("Wi-Fi IP：").append(ipText).append('\n');
                sb.append("Wi-Fi ADB：").append(wifiAdbText);
                statusDetails.setText(sb.toString());

                if (rootOk) {
                    rootStatusView.setText("Root 权限：已授权 (Magisk)" + (finalClAvail ? " · CarLife 模块就绪" : " · 未检测到 CarLife 模块"));
                    rootStatusView.setTextColor(Color.parseColor("#4ADE80"));
                } else {
                    rootStatusView.setText("Root 权限：未授权（点下方“打开 Magisk” → 超级用户 → 勾选“USB模式切换”）");
                    rootStatusView.setTextColor(Color.parseColor("#FBBF24"));
                }

                if (wifiOn) {
                    wifiBtnTitle.setText("关闭 Wi-Fi ADB (:5555)");
                    wifiBtnSub.setText(ip.isEmpty() ? "5555 已监听（连上 Wi-Fi 可连接）" : "adb connect " + ip + ":5555");
                    wifiCard.setBackground(roundRect(Color.parseColor("#B45309"), 14, Color.parseColor("#F59E0B"), 1));
                } else {
                    wifiBtnTitle.setText("立即开启 Wi-Fi ADB (:5555)");
                    wifiBtnSub.setText("免重启开启 5555 无线调试，与车机互联共存");
                    wifiCard.setBackground(roundRect(Color.parseColor("#0F766E"), 14, Color.parseColor("#14B8A6"), 1));
                }
            });
        });
    }

    private boolean ensureRootOrPrompt() {
        if (!hasRoot) {
            new AlertDialog.Builder(this)
                    .setTitle("需要先授予 Root 权限")
                    .setMessage("Magisk v31.1 需要在超级用户列表中提前授权：\n\n1. 点击“打开 Magisk”\n2. 进入底部“超级用户”标签\n3. 点击右上角/列表勾选“USB模式切换”\n4. 返回本应用即可生效")
                    .setPositiveButton("打开 Magisk", (d, w) -> openMagiskApp())
                    .setNegativeButton("取消", null)
                    .show();
            return false;
        }
        return true;
    }

    private void confirmAndSwitchMode(int targetMode) {
        if (!ensureRootOrPrompt()) return;
        if (targetMode == MODE_CARLIFE && !carlifeInstalled) {
            new AlertDialog.Builder(this)
                    .setTitle("未检测到 CarLife 运行组件")
                    .setMessage("缺少 /data/adb/carlife/carlife-daemon.sh，请先安装 tbox_carlife Magisk 模块。")
                    .setPositiveButton("确定", null)
                    .show();
            return;
        }

        final String targetName;
        final String desc;
        final String cmd;
        if (targetMode == MODE_CARPLAY) {
            targetName = "CarPlay 模式（原厂协议）";
            desc = "重启后 USB 恢复原厂 CarPlay 互联，且开机自动开启 Wi-Fi ADB (5555)。";
            cmd = "setprop persist.sdcarplay.reverse.disable 0; setprop persist.tbox.carlife 0; sync; sleep 1; (svc power reboot || reboot)";
        } else if (targetMode == MODE_CARLIFE) {
            targetName = "CarLife 模式（百度 USB AOA）";
            desc = "重启后 USB 以百度 CarLife AOA 协议连接车机：\n• 1920×720@30fps 原生画面 + 触控\n• 媒体 / 导航 / 电话音频可在本页实时选择走 USB 或蓝牙\n• 不关闭车机原生蓝牙，Wi-Fi ADB (5555) 照常开启";
            cmd = "setprop persist.sdcarplay.reverse.disable 0; setprop persist.tbox.carlife 1; sync; sleep 1; (svc power reboot || reboot)";
        } else {
            targetName = "USB ADB 模式（电脑调试）";
            desc = "重启后请将盒子接入电脑使用 USB ADB / scrcpy（此模式下暂停车机互联）。";
            cmd = "setprop persist.sdcarplay.reverse.disable 1; setprop persist.tbox.carlife 0; sync; sleep 1; (svc power reboot || reboot)";
        }

        new AlertDialog.Builder(this)
                .setTitle("确认切换到 " + targetName + "？")
                .setMessage(desc + "\n\n点击确定后将立即重启盒子。")
                .setPositiveButton("确定切换并重启", (d, w) -> runRootCommand(
                        cmd,
                        "正在切换为" + targetName + "并重启..."
                ))
                .setNegativeButton("取消", null)
                .show();
    }

    private void toggleWifiAdbNow() {
        if (!ensureRootOrPrompt()) return;
        if (currentWifiAdbOn) {
            runRootCommand(
                    "setprop service.adb.tcp.port -1; stop adbd; sleep 1; start adbd",
                    "已关闭 Wi-Fi ADB (:5555)"
            );
        } else {
            String msg = currentWifiIp.isEmpty()
                    ? "已开启 Wi-Fi ADB (:5555)"
                    : "已开启 Wi-Fi ADB：" + currentWifiIp + ":5555";
            runRootCommand(
                    "setprop service.adb.tcp.port 5555; stop adbd; sleep 1; start adbd",
                    msg
            );
        }
    }

    private void confirmAndRebootFastboot() {
        if (!ensureRootOrPrompt()) return;
        new AlertDialog.Builder(this)
                .setTitle("确认重启到 Fastboot？")
                .setMessage("盒子将重启进入 Bootloader (Fastboot) 模式，屏幕无画面，需通过电脑 fastboot 操作或重新拔插供电退出。")
                .setPositiveButton("进入 Fastboot", (d, w) -> runRootCommand(
                        "reboot bootloader",
                        "正在重启进入 Fastboot..."
                ))
                .setNegativeButton("取消", null)
                .show();
    }

    private void runRootCommand(String shellCmd, String successToast) {
        Toast.makeText(this, successToast, Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            ExecResult r = execCmd(new String[]{"su", "-c", shellCmd});
            mainHandler.post(() -> {
                if (r.rc != 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("执行失败 (rc=" + r.rc + ")")
                            .setMessage(r.out.isEmpty() ? "未获得 Root 权限或命令失败" : r.out)
                            .setPositiveButton("确定", null)
                            .show();
                }
                refreshStatus();
            });
        });
    }

    private void openMagiskApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.topjohnwu.magisk");
        if (intent != null) {
            startActivity(intent);
        } else {
            Toast.makeText(this, "未找到 Magisk App", Toast.LENGTH_SHORT).show();
        }
    }
}
