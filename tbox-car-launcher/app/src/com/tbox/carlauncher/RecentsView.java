package com.tbox.carlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CarPlay-style 16:9 Recent Apps Switcher inside the 1248x702 center viewport.
 * Triggered by long-pressing the Left Dock Back button; displays live TaskSnapshot
 * previews, single-task close buttons, and one-tap Clear All without covering the Dock/Widgets.
 */
public class RecentsView extends LinearLayout {
    public interface Callbacks {
        void onAppChosen(String packageName);
        void onTaskRemoved(int taskId, String packageName);
        void onClearAll(List<AppViewportManager.RecentTaskItem> items);
        void onCloseRequested();
        void onOpenDrawerRequested();
    }

    private final Callbacks callbacks;
    private final LauncherPrefs prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newFixedThreadPool(2);

    private final TextView tvCount;
    private final View btnClearAll;
    private final HorizontalScrollView scroller;
    private final LinearLayout cardsRow;
    private final LinearLayout emptyBox;

    private final List<AppViewportManager.RecentTaskItem> currentItems = new ArrayList<AppViewportManager.RecentTaskItem>();
    private int loadGeneration = 0;

    public RecentsView(Context context, LauncherPrefs prefs, Callbacks cb) {
        super(context);
        this.prefs = prefs;
        this.callbacks = cb;
        setOrientation(VERTICAL);
        setPadding(34, 24, 34, 24);
        setClickable(true);

        LayerDrawable bg = new LayerDrawable(new Drawable[]{
            new ColorDrawable(UiUtil.BG_CANVAS),
            UiUtil.cardDrawable(0xFF121722, 0xFF0B0F17, 20f, UiUtil.CARD_BORDER, 1)
        });
        setBackground(bg);

        // Header bar
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(context);
        tvTitle.setText("最近任务");
        tvTitle.setTextColor(UiUtil.TEXT_PRIMARY);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.addView(tvTitle);

        tvCount = new TextView(context);
        tvCount.setTextColor(UiUtil.TEXT_SECONDARY);
        tvCount.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f);
        tvCount.setPadding(12, 4, 12, 4);
        tvCount.setBackground(UiUtil.solidRounded(0x14FFFFFF, 12f, 0, 0));
        LayoutParams cntLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        cntLp.leftMargin = 14;
        header.addView(tvCount, cntLp);

        header.addView(new View(context), new LayoutParams(0, 1, 1f));

        // Clear All button
        LinearLayout clearBtn = new LinearLayout(context);
        clearBtn.setOrientation(HORIZONTAL);
        clearBtn.setGravity(Gravity.CENTER_VERTICAL);
        clearBtn.setPadding(18, 0, 20, 0);
        clearBtn.setBackground(UiUtil.solidRounded(0x22EF4444, 14f, 0x48EF4444, 1));
        clearBtn.addView(new SimpleGlyphView(context, SimpleGlyphView.TRASH, 0xFFFCA5A5),
            new LayoutParams(20, 20));
        TextView tvClear = new TextView(context);
        tvClear.setText("全部清理");
        tvClear.setTextColor(0xFFFCA5A5);
        tvClear.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f);
        tvClear.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LayoutParams tclLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tclLp.leftMargin = 8;
        clearBtn.addView(tvClear, tclLp);
        clearBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentItems.isEmpty()) return;
                List<AppViewportManager.RecentTaskItem> snapshot =
                    new ArrayList<AppViewportManager.RecentTaskItem>(currentItems);
                currentItems.clear();
                cardsRow.removeAllViews();
                updateEmptyAndCount();
                if (callbacks != null) callbacks.onClearAll(snapshot);
            }
        });
        UiUtil.addPressScale(clearBtn);
        btnClearAll = clearBtn;
        LayoutParams clrLp = new LayoutParams(LayoutParams.WRAP_CONTENT, 46);
        clrLp.rightMargin = 14;
        header.addView(clearBtn, clrLp);

        // Close button
        FrameLayout btnClose = new FrameLayout(context);
        btnClose.setBackground(UiUtil.solidRounded(0x16FFFFFF, 14f, 0x16FFFFFF, 1));
        btnClose.addView(new SimpleGlyphView(context, SimpleGlyphView.CLOSE, 0xFFE2E8F0),
            new FrameLayout.LayoutParams(20, 20, Gravity.CENTER));
        btnClose.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callbacks != null) callbacks.onCloseRequested();
            }
        });
        UiUtil.addPressScale(btnClose);
        header.addView(btnClose, new LayoutParams(46, 46));

        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Center content area (Carousel + Empty state stacked)
        FrameLayout content = new FrameLayout(context);
        LayoutParams contentLp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        contentLp.topMargin = 20;

        scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(OVER_SCROLL_ALWAYS);

        cardsRow = new LinearLayout(context);
        cardsRow.setOrientation(HORIZONTAL);
        cardsRow.setGravity(Gravity.CENTER_VERTICAL);
        cardsRow.setPadding(6, 12, 6, 12);
        scroller.addView(cardsRow, new FrameLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        content.addView(scroller, new FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Empty state
        emptyBox = new LinearLayout(context);
        emptyBox.setOrientation(VERTICAL);
        emptyBox.setGravity(Gravity.CENTER);
        emptyBox.setVisibility(GONE);

        TextView tvEmpty = new TextView(context);
        tvEmpty.setText("暂无最近打开的应用");
        tvEmpty.setTextColor(UiUtil.TEXT_SECONDARY);
        tvEmpty.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f);
        emptyBox.addView(tvEmpty);

        TextView btnOpenDrawer = new TextView(context);
        btnOpenDrawer.setText("打开全部应用");
        btnOpenDrawer.setTextColor(0xFF070A0F);
        btnOpenDrawer.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f);
        btnOpenDrawer.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnOpenDrawer.setPadding(28, 12, 28, 12);
        btnOpenDrawer.setBackground(UiUtil.solidRounded(UiUtil.ACCENT_BLUE, 16f, 0, 0));
        btnOpenDrawer.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callbacks != null) callbacks.onOpenDrawerRequested();
            }
        });
        UiUtil.addPressScale(btnOpenDrawer);
        LayoutParams odLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        odLp.topMargin = 20;
        emptyBox.addView(btnOpenDrawer, odLp);

        content.addView(emptyBox, new FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        addView(content, contentLp);
    }

    public void reload(final AppViewportManager viewport, final String activePkg) {
        final int gen = ++loadGeneration;
        cardsRow.removeAllViews();
        currentItems.clear();
        tvCount.setText("加载中…");
        btnClearAll.setVisibility(GONE);
        emptyBox.setVisibility(GONE);
        scroller.setVisibility(VISIBLE);
        scroller.scrollTo(0, 0);

        loader.execute(new Runnable() {
            @Override
            public void run() {
                final List<AppViewportManager.RecentTaskItem> items = viewport != null
                    ? viewport.getRecentTasks() : new ArrayList<AppViewportManager.RecentTaskItem>();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != loadGeneration) return;
                        populateCards(viewport, items, activePkg, gen);
                    }
                });
            }
        });
    }

    private void populateCards(final AppViewportManager viewport,
                               List<AppViewportManager.RecentTaskItem> items,
                               String activePkg, final int gen) {
        currentItems.clear();
        cardsRow.removeAllViews();
        if (items != null) currentItems.addAll(items);

        Context ctx = getContext();
        for (int i = 0; i < currentItems.size(); i++) {
            final AppViewportManager.RecentTaskItem item = currentItems.get(i);
            boolean isCurrent = !TextUtils.isEmpty(activePkg) && activePkg.equals(item.packageName);
            View card = buildTaskCard(ctx, viewport, item, isCurrent, gen);
            // 456x382 gives an exact 16:9 (424x238) landscape preview box with zero side cropping
            LayoutParams lp = new LayoutParams(456, 382);
            if (i > 0) lp.leftMargin = 22;
            cardsRow.addView(card, lp);
        }
        updateEmptyAndCount();
    }

    private View buildTaskCard(Context ctx, final AppViewportManager viewport,
                               final AppViewportManager.RecentTaskItem item,
                               boolean isCurrent, final int gen) {
        final LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(VERTICAL);
        card.setPadding(16, 14, 16, 14);
        card.setBackground(UiUtil.cardDrawable(
            isCurrent ? 0xFF18293D : 0xFF182030,
            isCurrent ? 0xFF101B29 : 0xFF101520,
            20f,
            isCurrent ? 0xAA38BDF8 : 0x1EFFFFFF,
            isCurrent ? 2 : 1
        ));

        String label = UiUtil.getAppLabel(ctx, item.packageName);
        if (TextUtils.isEmpty(label)) label = item.packageName;
        Bitmap iconBmp = CockpitDialog.loadRoundedIcon(ctx, item.packageName);

        // Card header: Icon + Label + single close button
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        ImageView ivIcon = new ImageView(ctx);
        ivIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (iconBmp != null) ivIcon.setImageBitmap(iconBmp);
        LayoutParams icLp = new LayoutParams(38, 38);
        icLp.rightMargin = 12;
        top.addView(ivIcon, icLp);

        TextView tvName = new TextView(ctx);
        tvName.setText(label);
        tvName.setTextColor(UiUtil.TEXT_PRIMARY);
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f);
        tvName.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvName.setSingleLine(true);
        tvName.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams nmLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        nmLp.rightMargin = 8;
        top.addView(tvName, nmLp);

        FrameLayout btnDel = new FrameLayout(ctx);
        btnDel.setBackground(UiUtil.solidRounded(0x18FFFFFF, 18f, 0, 0));
        btnDel.addView(new SimpleGlyphView(ctx, SimpleGlyphView.CLOSE, 0xFFCBD5E1),
            new FrameLayout.LayoutParams(15, 15, Gravity.CENTER));
        btnDel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                currentItems.remove(item);
                card.animate().alpha(0f).scaleX(0.88f).scaleY(0.88f).setDuration(140)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            cardsRow.removeView(card);
                            updateEmptyAndCount();
                        }
                    }).start();
                if (callbacks != null) callbacks.onTaskRemoved(item.taskId, item.packageName);
            }
        });
        UiUtil.addPressScale(btnDel);
        top.addView(btnDel, new LayoutParams(36, 36));

        card.addView(top, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Middle: 16:9 Live Preview Snapshot frame
        FrameLayout previewBox = new FrameLayout(ctx);
        int tint = UiUtil.extractDarkTint(iconBmp, 0xFF151C28);
        previewBox.setBackground(UiUtil.cardDrawable(tint, 0xFF0C1018, 14f, 0x16FFFFFF, 1));
        previewBox.setClipToOutline(true);

        // Fallback large centered app icon
        final ImageView fallbackIcon = new ImageView(ctx);
        fallbackIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (iconBmp != null) fallbackIcon.setImageBitmap(iconBmp);
        fallbackIcon.setAlpha(0.92f);
        previewBox.addView(fallbackIcon, new FrameLayout.LayoutParams(92, 92, Gravity.CENTER));

        // Real task snapshot ImageView
        final ImageView ivSnap = new ImageView(ctx);
        ivSnap.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivSnap.setAlpha(0f);
        previewBox.addView(ivSnap, new FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LayoutParams pvLp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        pvLp.topMargin = 14;
        pvLp.bottomMargin = 12;
        card.addView(previewBox, pvLp);

        // Load TaskSnapshot asynchronously
        if (viewport != null && item.taskId > 0) {
            loader.execute(new Runnable() {
                @Override
                public void run() {
                    if (gen != loadGeneration) return;
                    final Bitmap snap = viewport.getTaskSnapshot(item.taskId);
                    if (snap == null) return;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != loadGeneration) {
                                try { snap.recycle(); } catch (Throwable ignored) {}
                                return;
                            }
                            ivSnap.setImageBitmap(snap);
                            ivSnap.animate().alpha(1f).setDuration(150).start();
                            fallbackIcon.setVisibility(GONE);
                        }
                    });
                }
            });
        }

        // Card footer: running status + optional DPI pill
        LinearLayout footer = new LinearLayout(ctx);
        footer.setOrientation(HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(ctx);
        int dotColor = isCurrent ? UiUtil.ACCENT_BLUE : (item.running ? UiUtil.ACCENT_GREEN : UiUtil.TEXT_MUTED);
        dot.setBackground(UiUtil.solidRounded(dotColor, 5f, 0, 0));
        LayoutParams dotLp = new LayoutParams(9, 9);
        dotLp.rightMargin = 8;
        footer.addView(dot, dotLp);

        TextView tvStatus = new TextView(ctx);
        tvStatus.setText(isCurrent ? "当前运行" : (item.running ? "后台常驻" : "最近打开"));
        tvStatus.setTextColor(isCurrent ? UiUtil.ACCENT_BLUE : UiUtil.TEXT_SECONDARY);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15.5f);
        footer.addView(tvStatus, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        int dpiOv = prefs != null ? prefs.getAppDpiOverride(item.packageName) : 0;
        if (dpiOv > 0) {
            footer.addView(CockpitDialog.makePill(ctx, dpiOv + " dpi", UiUtil.ACCENT_BLUE));
        }

        card.addView(footer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        card.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callbacks != null) callbacks.onAppChosen(item.packageName);
            }
        });
        UiUtil.addPressScale(card);
        return card;
    }

    private void updateEmptyAndCount() {
        int n = currentItems.size();
        tvCount.setText(n + " 个应用");
        if (n == 0) {
            btnClearAll.setVisibility(GONE);
            scroller.setVisibility(GONE);
            emptyBox.setVisibility(VISIBLE);
        } else {
            btnClearAll.setVisibility(VISIBLE);
            scroller.setVisibility(VISIBLE);
            emptyBox.setVisibility(GONE);
        }
    }

    public void release() {
        loader.shutdownNow();
    }

    private static final class SimpleGlyphView extends View {
        static final int CLOSE = 1;
        static final int TRASH = 2;

        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();

        SimpleGlyphView(Context ctx, int kind, int color) {
            super(ctx);
            this.kind = kind;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float s = Math.min(w, h);
            if (s <= 0) return;
            float cx = w * 0.5f;
            float cy = h * 0.5f;
            paint.setStrokeWidth(Math.max(2f, s * 0.12f));
            if (kind == CLOSE) {
                float r = s * 0.30f;
                c.drawLine(cx - r, cy - r, cx + r, cy + r, paint);
                c.drawLine(cx + r, cy - r, cx - r, cy + r, paint);
            } else if (kind == TRASH) {
                float top = cy - s * 0.22f;
                float bot = cy + s * 0.36f;
                float hw = s * 0.28f;
                c.drawLine(cx - hw - s * 0.06f, top, cx + hw + s * 0.06f, top, paint);
                c.drawLine(cx - s * 0.12f, top - s * 0.13f, cx + s * 0.12f, top - s * 0.13f, paint);
                rf.set(cx - hw, top, cx + hw, bot);
                c.drawRoundRect(rf, s * 0.08f, s * 0.08f, paint);
                c.drawLine(cx - s * 0.09f, top + s * 0.15f, cx - s * 0.09f, bot - s * 0.12f, paint);
                c.drawLine(cx + s * 0.09f, top + s * 0.15f, cx + s * 0.09f, bot - s * 0.12f, paint);
            }
        }
    }
}
