package com.tbox.carlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * CarPlay-style paginated 5x2 App Drawer displayed inside the 1248x702 16:9 center viewport.
 * Supports horizontal swipe AND tactile page arrows for effortless operation while driving.
 */
public class AppDrawerView extends LinearLayout {
    public static final class AppEntry {
        public final String packageName;
        public final String label;
        public final Bitmap icon;

        AppEntry(String pkg, String label, Bitmap icon) {
            this.packageName = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    public interface Callbacks {
        void onAppSelected(AppEntry app);
        void onAppLongPressed(AppEntry app);
        void onOpenSettings();
    }

    private static final int COLS = 5;
    private static final int ROWS = 2;
    private static final int PAGE_SIZE = COLS * ROWS;

    private final Callbacks callbacks;
    private final List<AppEntry> apps = new ArrayList<AppEntry>();
    private final LinearLayout gridHost;
    private final LinearLayout dotsRow;
    private final TextView tvPageInfo;
    private final TextView btnPrevPage;
    private final TextView btnNextPage;

    private int currentPage = 0;
    private float downX = 0f;

    public AppDrawerView(Context context, Callbacks cb) {
        super(context);
        this.callbacks = cb;
        setOrientation(VERTICAL);
        setPadding(34, 24, 34, 22);
        setBackground(UiUtil.cardDrawable(0xFF121824, 0xFF0B0F17, 22f, UiUtil.CARD_BORDER, 2));

        // Top header bar
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(context);
        tvTitle.setText("全部应用");
        tvTitle.setTextColor(0xFFF8FAFC);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.addView(tvTitle);

        tvPageInfo = new TextView(context);
        tvPageInfo.setTextColor(UiUtil.TEXT_SECONDARY);
        tvPageInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f);
        LayoutParams pInfoLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        pInfoLp.leftMargin = 16;
        header.addView(tvPageInfo, pInfoLp);

        TextView tvHint = new TextView(context);
        tvHint.setText("长按图标可固定到左侧 Dock");
        tvHint.setTextColor(UiUtil.TEXT_MUTED);
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f);
        LayoutParams hintLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        hintLp.rightMargin = 18;
        header.addView(tvHint, hintLp);

        TextView btnSettings = new TextView(context);
        btnSettings.setText("桌面设置");
        btnSettings.setTextColor(0xFFE2E8F0);
        btnSettings.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f);
        btnSettings.setPadding(22, 10, 22, 10);
        btnSettings.setBackground(UiUtil.solidRounded(0x1EFFFFFF, 16f, 0x28FFFFFF, 1));
        btnSettings.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callbacks != null) callbacks.onOpenSettings();
            }
        });
        UiUtil.addPressScale(btnSettings);
        header.addView(btnSettings);

        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Center grid container (5x2)
        gridHost = new LinearLayout(context);
        gridHost.setOrientation(VERTICAL);
        gridHost.setGravity(Gravity.CENTER);
        LayoutParams gridLp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        gridLp.topMargin = 18;
        gridLp.bottomMargin = 12;
        addView(gridHost, gridLp);

        // Bottom pagination bar
        LinearLayout pager = new LinearLayout(context);
        pager.setOrientation(HORIZONTAL);
        pager.setGravity(Gravity.CENTER);

        btnPrevPage = makePageBtn(context, "‹  上一页", new OnClickListener() {
            @Override
            public void onClick(View v) {
                goToPage(currentPage - 1);
            }
        });
        pager.addView(btnPrevPage);

        dotsRow = new LinearLayout(context);
        dotsRow.setOrientation(HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        LayoutParams dotsLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        dotsLp.leftMargin = 28;
        dotsLp.rightMargin = 28;
        pager.addView(dotsRow, dotsLp);

        btnNextPage = makePageBtn(context, "下一页  ›", new OnClickListener() {
            @Override
            public void onClick(View v) {
                goToPage(currentPage + 1);
            }
        });
        pager.addView(btnNextPage);

        addView(pager, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = ev.getX();
        } else if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
            float dx = ev.getX() - downX;
            if (Math.abs(dx) > 90f) {
                if (dx < 0) goToPage(currentPage + 1);
                else goToPage(currentPage - 1);
                return true;
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    public void reloadApps(LauncherPrefs prefs) {
        apps.clear();
        PackageManager pm = getContext().getPackageManager();
        Set<String> hidden = prefs.getHiddenPackages();

        Intent q = new Intent(Intent.ACTION_MAIN, null);
        q.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(q, 0);

        List<String> seen = new ArrayList<String>();
        for (ResolveInfo ri : resolved) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (hidden.contains(pkg) || seen.contains(pkg)) continue;
            seen.add(pkg);
            CharSequence cs = ri.loadLabel(pm);
            String label = cs != null ? cs.toString() : pkg;
            Bitmap bmp = null;
            try {
                Drawable d = ri.loadIcon(pm);
                bmp = UiUtil.drawableToRoundedBitmap(d, 108, 26f);
            } catch (Throwable ignored) {}
            apps.add(new AppEntry(pkg, label, bmp));
        }

        // Priority sort: navigation / music / video / switch tools first, then alphabetical
        Collections.sort(apps, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                int pa = priorityOf(a.packageName);
                int pb = priorityOf(b.packageName);
                if (pa != pb) return pa - pb;
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        currentPage = 0;
        renderPage();
    }

    public List<AppEntry> getApps() {
        return apps;
    }

    private int pageCount() {
        return Math.max(1, (apps.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void goToPage(int p) {
        int count = pageCount();
        if (p < 0) p = 0;
        if (p >= count) p = count - 1;
        if (p != currentPage) {
            currentPage = p;
            renderPage();
        }
    }

    private void renderPage() {
        gridHost.removeAllViews();
        Context ctx = getContext();
        int totalPages = pageCount();
        int start = currentPage * PAGE_SIZE;

        for (int r = 0; r < ROWS; r++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LayoutParams rowLp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
            for (int c = 0; c < COLS; c++) {
                int idx = start + r * COLS + c;
                if (idx < apps.size()) {
                    row.addView(makeAppTile(ctx, apps.get(idx)), new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
                } else {
                    row.addView(new View(ctx), new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
                }
            }
            gridHost.addView(row, rowLp);
        }

        tvPageInfo.setText((currentPage + 1) + " / " + totalPages + " 页 · 共 " + apps.size() + " 个应用");

        dotsRow.removeAllViews();
        for (int i = 0; i < totalPages; i++) {
            View dot = new View(ctx);
            boolean active = (i == currentPage);
            dot.setBackground(UiUtil.solidRounded(active ? UiUtil.ACCENT_BLUE : 0x44FFFFFF, 6f, 0, 0));
            LayoutParams dlp = new LayoutParams(active ? 26 : 10, 10);
            dlp.leftMargin = 5;
            dlp.rightMargin = 5;
            dotsRow.addView(dot, dlp);
        }

        btnPrevPage.setAlpha(currentPage > 0 ? 1f : 0.30f);
        btnNextPage.setAlpha(currentPage < totalPages - 1 ? 1f : 0.30f);
    }

    private View makeAppTile(Context ctx, final AppEntry app) {
        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(12, 10, 12, 10);

        FrameLayout iconWrap = new FrameLayout(ctx);
        iconWrap.setBackground(UiUtil.solidRounded(0x16FFFFFF, 28f, 0x1FFFFFFF, 1));
        iconWrap.setPadding(6, 6, 6, 6);
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (app.icon != null) iv.setImageBitmap(app.icon);
        iconWrap.addView(iv, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        tile.addView(iconWrap, new LayoutParams(116, 116));

        TextView tv = new TextView(ctx);
        tv.setText(app.label);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 21f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams tvLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        tvLp.topMargin = 12;
        tile.addView(tv, tvLp);

        tile.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callbacks != null) callbacks.onAppSelected(app);
            }
        });
        tile.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (callbacks != null) callbacks.onAppLongPressed(app);
                return true;
            }
        });
        UiUtil.addPressScale(tile);
        return tile;
    }

    private TextView makePageBtn(Context ctx, String text, OnClickListener click) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f);
        tv.setPadding(24, 10, 24, 10);
        tv.setBackground(UiUtil.solidRounded(0x18FFFFFF, 16f, 0x22FFFFFF, 1));
        tv.setOnClickListener(click);
        UiUtil.addPressScale(tv);
        return tv;
    }

    private static int priorityOf(String pkg) {
        if ("com.autonavi.amapauto".equals(pkg)) return 1;
        if ("com.netease.cloudmusic.iot".equals(pkg)) return 2;
        if ("cn.aqzscn.stream_music".equals(pkg)) return 3;
        if ("cn.kuwo.kwmusiccar".equals(pkg)) return 4;
        if ("com.bilibili.tv".equals(pkg) || "com.bilibili.bilithings".equals(pkg)) return 5;
        if ("com.tbox.usbswitch".equals(pkg)) return 6;
        if ("com.android.settings".equals(pkg)) return 7;
        if ("dev.jdtech.jellyfin".equals(pkg) || "org.jellyfin.mobile".equals(pkg)) return 8;
        if ("com.suding.onstepbtmusic".equals(pkg)) return 9;
        return 50;
    }
}
