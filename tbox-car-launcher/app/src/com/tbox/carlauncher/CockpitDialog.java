package com.tbox.carlauncher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Unified CarPlay-inspired dark-glass modal dialog builder for TboxCarLauncher.
 * Designed specifically for 1920x720 automotive touchscreens with crisp vector glyphs,
 * large touch targets, and zero visual mismatch against the dark cockpit theme.
 */
public final class CockpitDialog {
    public static final int ICON_GEAR = 1;
    public static final int ICON_SCALE = 2;
    public static final int ICON_BOOT = 3;
    public static final int ICON_USB = 4;
    public static final int ICON_SLIDERS = 5;
    public static final int ICON_TRASH = 6;
    public static final int ICON_CLOSE = 7;
    public static final int ICON_CHEVRON = 8;
    public static final int ICON_CHECK = 9;
    public static final int ICON_TEMP = 10;

    private static final int CARD_TOP = 0xFF182030;
    private static final int CARD_BOT = 0xFF0E131D;
    private static final int CARD_STROKE = 0x24FFFFFF;
    private static final int ROW_BG = 0x12FFFFFF;
    private static final int ROW_STROKE = 0x16FFFFFF;
    private static final int DANGER_BG = 0x1DEF4444;
    private static final int DANGER_STROKE = 0x45EF4444;
    private static final int DANGER_TEXT = 0xFFFCA5A5;
    private static final int DANGER_ICON = 0xFFF87171;

    private CockpitDialog() {}

    public static final class Handle {
        public final Dialog dialog;
        public final LinearLayout body;

        Handle(Dialog dialog, LinearLayout body) {
            this.dialog = dialog;
            this.body = body;
        }

        public void show() {
            Window w = dialog.getWindow();
            if (w != null) {
                w.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            }
            dialog.show();
            if (w != null) {
                View decor = w.getDecorView();
                decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
                w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            }
        }

        public void dismiss() {
            if (dialog.isShowing()) dialog.dismiss();
        }
    }

    public static Handle create(Context ctx, int widthPx, Bitmap appIcon, int vectorIcon,
                                int vectorTint, String title, String subtitle, String headerBadge) {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(28, 22, 28, 24);
        card.setBackground(UiUtil.cardDrawable(CARD_TOP, CARD_BOT, 26f, CARD_STROKE, 1));
        card.setElevation(24f);

        // Header row
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        if (appIcon != null) {
            FrameLayout iconBox = new FrameLayout(ctx);
            iconBox.setBackground(UiUtil.solidRounded(0x16FFFFFF, 16f, 0x1FFFFFFF, 1));
            iconBox.setPadding(5, 5, 5, 5);
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(appIcon);
            iconBox.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(58, 58);
            ilp.rightMargin = 16;
            header.addView(iconBox, ilp);
        } else if (vectorIcon > 0) {
            View badge = makeIconBadge(ctx, vectorIcon, vectorTint, 50, 15f);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(50, 50);
            ilp.rightMargin = 16;
            header.addView(badge, ilp);
        }

        LinearLayout titleCol = new LinearLayout(ctx);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleLine = new LinearLayout(ctx);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title != null ? title : "");
        tvTitle.setTextColor(UiUtil.TEXT_PRIMARY);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        titleLine.addView(tvTitle, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!TextUtils.isEmpty(headerBadge)) {
            TextView tvBadge = makePill(ctx, headerBadge, UiUtil.ACCENT_BLUE);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = 12;
            titleLine.addView(tvBadge, blp);
        }
        titleCol.addView(titleLine);

        if (!TextUtils.isEmpty(subtitle)) {
            TextView tvSub = new TextView(ctx);
            tvSub.setText(subtitle);
            tvSub.setTextColor(UiUtil.TEXT_SECONDARY);
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15.5f);
            tvSub.setSingleLine(true);
            tvSub.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = 3;
            titleCol.addView(tvSub, slp);
        }

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = 12;
        header.addView(titleCol, colLp);

        // Close button
        FrameLayout btnClose = new FrameLayout(ctx);
        btnClose.setBackground(UiUtil.solidRounded(0x16FFFFFF, 21f, 0x18FFFFFF, 1));
        btnClose.addView(new VectorIconView(ctx, ICON_CLOSE, 0xFFCBD5E1),
            new FrameLayout.LayoutParams(20, 20, Gravity.CENTER));
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        UiUtil.addPressScale(btnClose);
        header.addView(btnClose, new LinearLayout.LayoutParams(42, 42));

        card.addView(header, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Subtle divider
        View div = new View(ctx);
        div.setBackgroundColor(0x16FFFFFF);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1);
        divLp.topMargin = 16;
        divLp.bottomMargin = 16;
        card.addView(div, divLp);

        // Body container
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        card.addView(body, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(card, new ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT));
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = widthPx;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            lp.dimAmount = 0.64f;
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setAttributes(lp);
        }
        return new Handle(dialog, body);
    }

    public static TextView makeSectionLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFF8395AE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 10;
        tv.setLayoutParams(lp);
        return tv;
    }

    public static TextView makePill(Context ctx, String text, int accentColor) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        int bg = (accentColor & 0x00FFFFFF) | 0x26000000;
        int stroke = (accentColor & 0x00FFFFFF) | 0x55000000;
        tv.setTextColor(accentColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setPadding(12, 4, 12, 4);
        tv.setBackground(UiUtil.solidRounded(bg, 10f, stroke, 1));
        tv.setSingleLine(true);
        return tv;
    }

    public static View makeActionRow(Context ctx, int iconType, int iconTint, String title,
                                     String subtitle, String pillText, int pillTint,
                                     boolean isDanger, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(16, 13, 16, 13);
        row.setBackground(UiUtil.solidRounded(
            isDanger ? DANGER_BG : ROW_BG, 16f, isDanger ? DANGER_STROKE : ROW_STROKE, 1));

        View badge = makeIconBadge(ctx, iconType, isDanger ? DANGER_ICON : iconTint, 42, 12f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(42, 42);
        blp.rightMargin = 14;
        row.addView(badge, blp);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextColor(isDanger ? DANGER_TEXT : UiUtil.TEXT_PRIMARY);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19.5f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(tvTitle);

        if (!TextUtils.isEmpty(subtitle)) {
            TextView tvSub = new TextView(ctx);
            tvSub.setText(subtitle);
            tvSub.setTextColor(isDanger ? 0xB8FCA5A5 : UiUtil.TEXT_SECONDARY);
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14.5f);
            tvSub.setSingleLine(true);
            tvSub.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = 2;
            textCol.addView(tvSub, slp);
        }

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.rightMargin = 10;
        row.addView(textCol, clp);

        if (!TextUtils.isEmpty(pillText)) {
            TextView pill = makePill(ctx, pillText, pillTint != 0 ? pillTint : UiUtil.ACCENT_BLUE);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.rightMargin = 8;
            row.addView(pill, plp);
        }

        if (!isDanger) {
            VectorIconView chev = new VectorIconView(ctx, ICON_CHEVRON, 0xFF64748B);
            row.addView(chev, new LinearLayout.LayoutParams(16, 16));
        }

        row.setOnClickListener(onClick);
        UiUtil.addPressScale(row);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = 10;
        row.setLayoutParams(rlp);
        return row;
    }

    public interface SlotCallback {
        void onSlotSelected(int slotIndex);
    }

    public static View makeDockSlotBar(Context ctx, String[] currentDockPkgs, String targetPkg,
                                       final SlotCallback cb) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.bottomMargin = 16;
        bar.setLayoutParams(barLp);

        for (int i = 0; i < 4; i++) {
            final int slot = i;
            String pinnedPkg = (currentDockPkgs != null && i < currentDockPkgs.length)
                ? currentDockPkgs[i] : "";
            boolean isSame = !TextUtils.isEmpty(targetPkg) && targetPkg.equals(pinnedPkg);

            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(10, 12, 10, 12);
            cell.setBackground(UiUtil.solidRounded(
                isSame ? 0x2638BDF8 : ROW_BG,
                16f,
                isSame ? 0xAA38BDF8 : ROW_STROKE,
                isSame ? 2 : 1
            ));

            TextView tvSlot = new TextView(ctx);
            tvSlot.setText("Dock " + (i + 1));
            tvSlot.setTextColor(isSame ? UiUtil.ACCENT_BLUE : UiUtil.TEXT_PRIMARY);
            tvSlot.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17.5f);
            tvSlot.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            cell.addView(tvSlot);

            String sub = isSame ? "已固定" : (!TextUtils.isEmpty(pinnedPkg)
                ? UiUtil.getAppLabel(ctx, pinnedPkg) : "空位");
            TextView tvSub = new TextView(ctx);
            tvSub.setText(sub);
            tvSub.setTextColor(isSame ? 0xFF7DD3FC : UiUtil.TEXT_SECONDARY);
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13.5f);
            tvSub.setSingleLine(true);
            tvSub.setEllipsize(TextUtils.TruncateAt.END);
            tvSub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = 4;
            cell.addView(tvSub, slp);

            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (cb != null) cb.onSlotSelected(slot);
                }
            });
            UiUtil.addPressScale(cell);

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < 3) clp.rightMargin = 10;
            bar.addView(cell, clp);
        }
        return bar;
    }

    public interface DpiCallback {
        void onDpiSelected(int dpiValue);
    }

    public static View makeDpiGrid(Context ctx, int currentDpiOverride, final DpiCallback cb) {
        final int[] values = new int[]{0, 260, 220, 200, 180, 160, 140, 120};
        final String[] titles = new String[]{
            "默认 238", "260 dpi",
            "220 dpi", "200 dpi",
            "180 dpi", "160 dpi",
            "140 dpi", "120 dpi"
        };
        final String[] descs = new String[]{
            "标准 · 1248×702", "更大字体 · 1136×638",
            "偏大 · 1344×756", "适中偏小 · 1488×836",
            "较小 · 1648×926", "TV 推荐 · 1856×1044",
            "超紧凑 · 2112×1188", "极小 · 2480×1396"
        };

        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);

        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 2; c++) {
                int idx = r * 2 + c;
                final int val = values[idx];
                boolean selected = (val == currentDpiOverride);

                LinearLayout tile = new LinearLayout(ctx);
                tile.setOrientation(LinearLayout.HORIZONTAL);
                tile.setGravity(Gravity.CENTER_VERTICAL);
                tile.setPadding(16, 13, 14, 13);
                tile.setBackground(UiUtil.solidRounded(
                    selected ? 0x2638BDF8 : ROW_BG,
                    16f,
                    selected ? 0xCC38BDF8 : ROW_STROKE,
                    selected ? 2 : 1
                ));

                LinearLayout col = new LinearLayout(ctx);
                col.setOrientation(LinearLayout.VERTICAL);

                TextView tvVal = new TextView(ctx);
                tvVal.setText(titles[idx]);
                tvVal.setTextColor(selected ? UiUtil.ACCENT_BLUE : UiUtil.TEXT_PRIMARY);
                tvVal.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f);
                tvVal.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                col.addView(tvVal);

                TextView tvDesc = new TextView(ctx);
                tvDesc.setText(descs[idx]);
                tvDesc.setTextColor(selected ? 0xFFBAE6FD : UiUtil.TEXT_SECONDARY);
                tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f);
                tvDesc.setSingleLine(true);
                tvDesc.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dlp.topMargin = 2;
                col.addView(tvDesc, dlp);

                tile.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                if (selected) {
                    FrameLayout chk = new FrameLayout(ctx);
                    chk.setBackground(UiUtil.solidRounded(UiUtil.ACCENT_BLUE, 13f, 0, 0));
                    chk.addView(new VectorIconView(ctx, ICON_CHECK, 0xFF070A0F),
                        new FrameLayout.LayoutParams(15, 15, Gravity.CENTER));
                    tile.addView(chk, new LinearLayout.LayoutParams(26, 26));
                }

                tile.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (cb != null) cb.onDpiSelected(val);
                    }
                });
                UiUtil.addPressScale(tile);

                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (c == 0) tlp.rightMargin = 10;
                row.addView(tile, tlp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r < 3) rlp.bottomMargin = 10;
            grid.addView(row, rlp);
        }
        return grid;
    }

    public interface AppPickCallback {
        void onAppPicked(AppDrawerView.AppEntry app);
    }

    public static View makeAppGridPicker(Context ctx, List<AppDrawerView.AppEntry> apps,
                                         String currentPkg, final AppPickCallback cb) {
        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);

        int count = apps.size();
        for (int i = 0; i < count; i += 2) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 2; c++) {
                int idx = i + c;
                if (idx < count) {
                    final AppDrawerView.AppEntry entry = apps.get(idx);
                    boolean active = !TextUtils.isEmpty(currentPkg) && currentPkg.equals(entry.packageName);

                    LinearLayout tile = new LinearLayout(ctx);
                    tile.setOrientation(LinearLayout.HORIZONTAL);
                    tile.setGravity(Gravity.CENTER_VERTICAL);
                    tile.setPadding(12, 10, 12, 10);
                    tile.setBackground(UiUtil.solidRounded(
                        active ? 0x2638BDF8 : ROW_BG,
                        14f,
                        active ? 0xAA38BDF8 : ROW_STROKE,
                        active ? 2 : 1
                    ));

                    ImageView iv = new ImageView(ctx);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    if (entry.icon != null) iv.setImageBitmap(entry.icon);
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(42, 42);
                    ilp.rightMargin = 12;
                    tile.addView(iv, ilp);

                    TextView tv = new TextView(ctx);
                    tv.setText(entry.label);
                    tv.setTextColor(active ? UiUtil.ACCENT_BLUE : UiUtil.TEXT_PRIMARY);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f);
                    tv.setTypeface(Typeface.create("sans-serif-medium", active ? Typeface.BOLD : Typeface.NORMAL));
                    tv.setSingleLine(true);
                    tv.setEllipsize(TextUtils.TruncateAt.END);
                    tile.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    tile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (cb != null) cb.onAppPicked(entry);
                        }
                    });
                    UiUtil.addPressScale(tile);

                    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (c == 0) tlp.rightMargin = 10;
                    row.addView(tile, tlp);
                } else {
                    row.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
                }
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = 10;
            list.addView(row, rlp);
        }

        scroll.addView(list, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 430);
        scroll.setLayoutParams(slp);
        return scroll;
    }

    public static View makeButtonBar(Context ctx, String cancelText, String confirmText,
                                     boolean isDanger, View.OnClickListener onCancel,
                                     View.OnClickListener onConfirm) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = 18;
        bar.setLayoutParams(barLp);

        TextView btnCancel = new TextView(ctx);
        btnCancel.setText(cancelText);
        btnCancel.setTextColor(0xFFE2E8F0);
        btnCancel.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f);
        btnCancel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btnCancel.setGravity(Gravity.CENTER);
        btnCancel.setBackground(UiUtil.solidRounded(0x16FFFFFF, 16f, 0x1EFFFFFF, 1));
        btnCancel.setOnClickListener(onCancel);
        UiUtil.addPressScale(btnCancel);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, 54, 1f);
        clp.rightMargin = 12;
        bar.addView(btnCancel, clp);

        TextView btnConfirm = new TextView(ctx);
        btnConfirm.setText(confirmText);
        btnConfirm.setTextColor(isDanger ? 0xFFFFFFFF : 0xFF070A0F);
        btnConfirm.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f);
        btnConfirm.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnConfirm.setGravity(Gravity.CENTER);
        btnConfirm.setBackground(UiUtil.solidRounded(
            isDanger ? 0xFFDC2626 : UiUtil.ACCENT_BLUE, 16f, 0, 0));
        btnConfirm.setOnClickListener(onConfirm);
        UiUtil.addPressScale(btnConfirm);
        bar.addView(btnConfirm, new LinearLayout.LayoutParams(0, 54, 1f));

        return bar;
    }

    public interface SegmentCallback {
        void onSegmentSelected(int index);
    }

    public static View makeSegmentedRow(Context ctx, int iconType, int iconTint, String title,
                                        String subtitle, final String[] options,
                                        int initialSelected, final SegmentCallback cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(16, 13, 14, 13);
        row.setBackground(UiUtil.solidRounded(ROW_BG, 16f, ROW_STROKE, 1));

        View badge = makeIconBadge(ctx, iconType, iconTint, 42, 12f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(42, 42);
        blp.rightMargin = 14;
        row.addView(badge, blp);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextColor(UiUtil.TEXT_PRIMARY);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19.5f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvTitle.setSingleLine(true);
        textCol.addView(tvTitle);

        if (!TextUtils.isEmpty(subtitle)) {
            TextView tvSub = new TextView(ctx);
            tvSub.setText(subtitle);
            tvSub.setTextColor(UiUtil.TEXT_SECONDARY);
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14.5f);
            tvSub.setSingleLine(true);
            tvSub.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = 2;
            textCol.addView(tvSub, slp);
        }

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.rightMargin = 12;
        row.addView(textCol, clp);

        LinearLayout segBox = new LinearLayout(ctx);
        segBox.setOrientation(LinearLayout.HORIZONTAL);
        segBox.setPadding(4, 4, 4, 4);
        segBox.setBackground(UiUtil.solidRounded(0x38000000, 13f, 0x18FFFFFF, 1));

        final TextView[] segBtns = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView btn = new TextView(ctx);
            btn.setText(options[i]);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(14, 8, 14, 8);
            btn.setSingleLine(true);
            segBtns[i] = btn;
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applySegStyle(segBtns, idx);
                    if (cb != null) cb.onSegmentSelected(idx);
                }
            });
            UiUtil.addPressScale(btn);
            segBox.addView(btn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        applySegStyle(segBtns, initialSelected);
        row.addView(segBox);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = 10;
        row.setLayoutParams(rlp);
        return row;
    }

    private static void applySegStyle(TextView[] btns, int selectedIdx) {
        for (int i = 0; i < btns.length; i++) {
            boolean active = (i == selectedIdx);
            btns[i].setTextColor(active ? 0xFF070A0F : 0xFFCBD5E1);
            btns[i].setTypeface(Typeface.create("sans-serif-medium", active ? Typeface.BOLD : Typeface.NORMAL));
            btns[i].setBackground(active
                ? UiUtil.solidRounded(UiUtil.ACCENT_BLUE, 10f, 0, 0)
                : null);
        }
    }

    public static Bitmap loadRoundedIcon(Context ctx, String pkg) {
        if (TextUtils.isEmpty(pkg)) return null;
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            return UiUtil.drawableToRoundedBitmap(d, 96, 22f);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static View makeIconBadge(Context ctx, int iconType, int tintColor, int sizePx, float radiusPx) {
        FrameLayout box = new FrameLayout(ctx);
        int bg = (tintColor & 0x00FFFFFF) | 0x24000000;
        int stroke = (tintColor & 0x00FFFFFF) | 0x44000000;
        box.setBackground(UiUtil.solidRounded(bg, radiusPx, stroke, 1));
        int glyphSize = Math.round(sizePx * 0.52f);
        box.addView(new VectorIconView(ctx, iconType, tintColor),
            new FrameLayout.LayoutParams(glyphSize, glyphSize, Gravity.CENTER));
        return box;
    }

    private static final class VectorIconView extends View {
        private final int type;
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rf = new RectF();

        VectorIconView(Context ctx, int type, int color) {
            super(ctx);
            this.type = type;
            strokePaint.setColor(color);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);
            fillPaint.setColor(color);
            fillPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(w, h);
            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float sw = Math.max(2.1f, s * 0.105f);
            strokePaint.setStrokeWidth(sw);

            if (type == ICON_CLOSE) {
                float r = s * 0.30f;
                c.drawLine(cx - r, cy - r, cx + r, cy + r, strokePaint);
                c.drawLine(cx + r, cy - r, cx - r, cy + r, strokePaint);
            } else if (type == ICON_CHEVRON) {
                path.reset();
                path.moveTo(cx - s * 0.14f, cy - s * 0.30f);
                path.lineTo(cx + s * 0.18f, cy);
                path.lineTo(cx - s * 0.14f, cy + s * 0.30f);
                c.drawPath(path, strokePaint);
            } else if (type == ICON_CHECK) {
                strokePaint.setStrokeWidth(Math.max(2.4f, s * 0.16f));
                path.reset();
                path.moveTo(cx - s * 0.30f, cy + s * 0.02f);
                path.lineTo(cx - s * 0.08f, cy + s * 0.24f);
                path.lineTo(cx + s * 0.32f, cy - s * 0.22f);
                c.drawPath(path, strokePaint);
            } else if (type == ICON_SCALE) {
                float r = s * 0.36f;
                float arm = s * 0.22f;
                // Top-left & bottom-right expand corners
                path.reset();
                path.moveTo(cx - r, cy - r + arm);
                path.lineTo(cx - r, cy - r);
                path.lineTo(cx - r + arm, cy - r);
                path.moveTo(cx + r, cy + r - arm);
                path.lineTo(cx + r, cy + r);
                path.lineTo(cx + r - arm, cy + r);
                // Diagonal connectors
                path.moveTo(cx - r, cy - r);
                path.lineTo(cx - s * 0.10f, cy - s * 0.10f);
                path.moveTo(cx + r, cy + r);
                path.lineTo(cx + s * 0.10f, cy + s * 0.10f);
                c.drawPath(path, strokePaint);
            } else if (type == ICON_BOOT) {
                // Clean power/home-launch icon (circle + top stem)
                float r = s * 0.33f;
                rf.set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(rf, -52f, 284f, false, strokePaint);
                c.drawLine(cx, cy - r * 1.18f, cx, cy - r * 0.15f, strokePaint);
            } else if (type == ICON_TRASH) {
                float top = cy - s * 0.24f;
                float bot = cy + s * 0.36f;
                float hw = s * 0.28f;
                c.drawLine(cx - hw - s * 0.06f, top, cx + hw + s * 0.06f, top, strokePaint);
                c.drawLine(cx - s * 0.12f, top - s * 0.12f, cx + s * 0.12f, top - s * 0.12f, strokePaint);
                rf.set(cx - hw, top, cx + hw, bot);
                c.drawRoundRect(rf, s * 0.08f, s * 0.08f, strokePaint);
                c.drawLine(cx - s * 0.09f, top + s * 0.16f, cx - s * 0.09f, bot - s * 0.12f, strokePaint);
                c.drawLine(cx + s * 0.09f, top + s * 0.16f, cx + s * 0.09f, bot - s * 0.12f, strokePaint);
            } else if (type == ICON_USB) {
                // Simple crisp USB trident
                c.drawLine(cx, cy + s * 0.34f, cx, cy - s * 0.30f, strokePaint);
                c.drawCircle(cx, cy + s * 0.34f, s * 0.09f, fillPaint);
                path.reset();
                path.moveTo(cx - s * 0.11f, cy - s * 0.22f);
                path.lineTo(cx, cy - s * 0.38f);
                path.lineTo(cx + s * 0.11f, cy - s * 0.22f);
                c.drawPath(path, strokePaint);
                c.drawLine(cx, cy + s * 0.10f, cx - s * 0.24f, cy - s * 0.06f, strokePaint);
                c.drawCircle(cx - s * 0.24f, cy - s * 0.08f, s * 0.07f, fillPaint);
                c.drawLine(cx, cy + s * 0.02f, cx + s * 0.24f, cy - s * 0.14f, strokePaint);
                c.drawRect(cx + s * 0.18f, cy - s * 0.21f, cx + s * 0.30f, cy - s * 0.09f, fillPaint);
            } else if (type == ICON_SLIDERS) {
                float x0 = cx - s * 0.34f;
                float x1 = cx + s * 0.34f;
                float[] ys = new float[]{cy - s * 0.24f, cy, cy + s * 0.24f};
                float[] ks = new float[]{cx - s * 0.12f, cx + s * 0.14f, cx - s * 0.06f};
                for (int i = 0; i < 3; i++) {
                    c.drawLine(x0, ys[i], x1, ys[i], strokePaint);
                    c.drawCircle(ks[i], ys[i], s * 0.09f, fillPaint);
                }
            } else if (type == ICON_GEAR) {
                float rIn = s * 0.15f;
                float rMid = s * 0.29f;
                float rOut = s * 0.40f;
                c.drawCircle(cx, cy, rIn, strokePaint);
                c.drawCircle(cx, cy, rMid, strokePaint);
                for (int i = 0; i < 8; i++) {
                    double rad = Math.toRadians(i * 45.0);
                    float dx = (float) Math.cos(rad);
                    float dy = (float) Math.sin(rad);
                    c.drawLine(cx + dx * rMid, cy + dy * rMid, cx + dx * rOut, cy + dy * rOut, strokePaint);
                }
            } else if (type == ICON_TEMP) {
                // Crisp thermometer glyph
                float stemW = s * 0.18f;
                float topY = cy - s * 0.36f;
                float bulbY = cy + s * 0.22f;
                float bulbR = s * 0.19f;
                rf.set(cx - stemW * 0.5f, topY, cx + stemW * 0.5f, bulbY);
                c.drawRoundRect(rf, stemW * 0.5f, stemW * 0.5f, strokePaint);
                c.drawCircle(cx, bulbY, bulbR, fillPaint);
                c.drawLine(cx, cy - s * 0.10f, cx, bulbY, strokePaint);
            }
        }
    }
}
