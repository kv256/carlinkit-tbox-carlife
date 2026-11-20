package com.tbox.carlauncher;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Left 112x702 CarPlay-inspired vertical Dock:
 * - Top: Crisp clock + Wi-Fi & BT status dots
 * - Middle: 4 customizable quick-launch app slots with active left pill indicator
 * - Bottom: Home/Grid toggle, Back button, Voice/Settings button
 */
public class DockView extends LinearLayout {
    public interface Callbacks {
        void onDockAppClicked(int slot, String pkg);
        void onDockAppLongClicked(int slot);
        void onHomeToggleClicked();
        void onBackClicked();
        void onVoiceClicked();
        void onSettingsLongClicked();
    }

    private final Callbacks callbacks;
    private final TextView tvClock;
    private final View wifiDot;
    private final View btDot;

    private final FrameLayout[] slotContainers = new FrameLayout[LauncherPrefs.DOCK_SLOT_COUNT];
    private final View[] slotIndicators = new View[LauncherPrefs.DOCK_SLOT_COUNT];
    private final ImageView[] slotIcons = new ImageView[LauncherPrefs.DOCK_SLOT_COUNT];
    private String[] slotPackages = new String[LauncherPrefs.DOCK_SLOT_COUNT];

    private final DockGlyphView homeGlyph;
    private final FrameLayout homeBtn;

    private String activePkg = "";
    private boolean drawerVisible = false;

    public DockView(Context context, Callbacks cb) {
        super(context);
        this.callbacks = cb;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(8, 16, 8, 14);
        setBackground(UiUtil.cardDrawable(0xFF131924, 0xFF0E131B, 24f, UiUtil.CARD_BORDER, 2));

        // 1. Clock + status dots
        tvClock = new TextView(context);
        tvClock.setTextColor(0xFFFFFFFF);
        tvClock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 27f);
        tvClock.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvClock.setGravity(Gravity.CENTER);
        tvClock.setIncludeFontPadding(false);
        addView(tvClock, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout statusRow = new LinearLayout(context);
        statusRow.setOrientation(HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER);
        LayoutParams stLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        stLp.topMargin = 8;
        stLp.bottomMargin = 10;

        wifiDot = makeStatusBadge(context, "W", 0xFF38BDF8);
        btDot = makeStatusBadge(context, "B", 0xFF818CF8);
        statusRow.addView(wifiDot);
        LayoutParams btLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        btLp.leftMargin = 8;
        statusRow.addView(btDot, btLp);
        addView(statusRow, stLp);

        addDivider(context);

        // 2. Four quick-launch app slots
        LinearLayout slotsCol = new LinearLayout(context);
        slotsCol.setOrientation(VERTICAL);
        slotsCol.setGravity(Gravity.CENTER);
        LayoutParams scLp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        scLp.topMargin = 6;
        scLp.bottomMargin = 6;

        for (int i = 0; i < LauncherPrefs.DOCK_SLOT_COUNT; i++) {
            final int slot = i;
            FrameLayout item = new FrameLayout(context);
            LayoutParams itemLp = new LayoutParams(LayoutParams.MATCH_PARENT, 82);
            itemLp.topMargin = (i == 0) ? 0 : 8;
            item.setLayoutParams(itemLp);

            View indicator = new View(context);
            indicator.setBackground(UiUtil.solidRounded(UiUtil.ACCENT_BLUE, 4f, 0, 0));
            indicator.setVisibility(INVISIBLE);
            FrameLayout.LayoutParams indLp = new FrameLayout.LayoutParams(6, 36);
            indLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            item.addView(indicator, indLp);

            ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams icLp = new FrameLayout.LayoutParams(68, 68);
            icLp.gravity = Gravity.CENTER;
            item.addView(icon, icLp);

            item.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (callbacks != null) callbacks.onDockAppClicked(slot, slotPackages[slot]);
                }
            });
            item.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (callbacks != null) callbacks.onDockAppLongClicked(slot);
                    return true;
                }
            });
            UiUtil.addPressScale(item);

            slotContainers[i] = item;
            slotIndicators[i] = indicator;
            slotIcons[i] = icon;
            slotsCol.addView(item);
        }
        addView(slotsCol, scLp);

        addDivider(context);

        // 3. Bottom system controls: Back, Voice/Settings, Home/Grid
        View backBtn = makeUtilButton(context, new DockGlyphView(context, DockGlyphView.BACK, 0xFFE2E8F0),
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (callbacks != null) callbacks.onBackClicked();
                }
            }, null);
        LayoutParams utilLp1 = new LayoutParams(76, 56);
        utilLp1.topMargin = 10;
        addView(backBtn, utilLp1);

        View voiceBtn = makeUtilButton(context, new DockGlyphView(context, DockGlyphView.MIC, 0xFF38BDF8),
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (callbacks != null) callbacks.onVoiceClicked();
                }
            },
            new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (callbacks != null) callbacks.onSettingsLongClicked();
                    return true;
                }
            });
        LayoutParams utilLp2 = new LayoutParams(76, 56);
        utilLp2.topMargin = 8;
        addView(voiceBtn, utilLp2);

        homeGlyph = new DockGlyphView(context, DockGlyphView.GRID, 0xFFFFFFFF);
        homeBtn = makeUtilButton(context, homeGlyph,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (callbacks != null) callbacks.onHomeToggleClicked();
                }
            },
            new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (callbacks != null) callbacks.onSettingsLongClicked();
                    return true;
                }
            });
        LayoutParams utilLp3 = new LayoutParams(76, 60);
        utilLp3.topMargin = 8;
        addView(homeBtn, utilLp3);

        refreshClockAndStatus();
    }

    private void addDivider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(0x1EFFFFFF);
        LayoutParams lp = new LayoutParams(56, 2);
        addView(v, lp);
    }

    private View makeStatusBadge(Context ctx, String letter, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(letter);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(7, 2, 7, 2);
        tv.setBackground(UiUtil.solidRounded(0x1EFFFFFF, 8f, 0, 0));
        return tv;
    }

    private FrameLayout makeUtilButton(Context ctx, View glyph, OnClickListener click, OnLongClickListener longClick) {
        FrameLayout f = new FrameLayout(ctx);
        f.setBackground(UiUtil.solidRounded(0x16FFFFFF, 16f, 0x1AFFFFFF, 1));
        f.setPadding(16, 10, 16, 10);
        f.addView(glyph, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        f.setOnClickListener(click);
        if (longClick != null) f.setOnLongClickListener(longClick);
        UiUtil.addPressScale(f);
        return f;
    }

    public void setDockPackages(String[] pkgs) {
        PackageManager pm = getContext().getPackageManager();
        for (int i = 0; i < LauncherPrefs.DOCK_SLOT_COUNT; i++) {
            String pkg = (pkgs != null && i < pkgs.length) ? pkgs[i] : null;
            slotPackages[i] = pkg;
            Bitmap bmp = null;
            if (pkg != null) {
                try {
                    Drawable d = pm.getApplicationIcon(pkg);
                    bmp = UiUtil.drawableToRoundedBitmap(d, 68, 16f);
                } catch (Throwable ignored) {}
            }
            if (bmp != null) {
                slotIcons[i].setImageBitmap(bmp);
            } else {
                slotIcons[i].setImageDrawable(null);
            }
        }
        updateHighlights();
    }

    public void setActiveState(String pkg, boolean showingDrawer) {
        this.activePkg = pkg != null ? pkg : "";
        this.drawerVisible = showingDrawer;
        updateHighlights();
    }

    private void updateHighlights() {
        for (int i = 0; i < LauncherPrefs.DOCK_SLOT_COUNT; i++) {
            boolean active = !drawerVisible && slotPackages[i] != null && slotPackages[i].equals(activePkg);
            slotIndicators[i].setVisibility(active ? VISIBLE : INVISIBLE);
            slotContainers[i].setBackground(active
                ? UiUtil.solidRounded(0x2438BDF8, 18f, 0x4438BDF8, 1)
                : null);
        }
        homeBtn.setBackground(drawerVisible
            ? UiUtil.solidRounded(0x3838BDF8, 16f, 0x8838BDF8, 2)
            : UiUtil.solidRounded(0x16FFFFFF, 16f, 0x1AFFFFFF, 1));
        homeGlyph.setKind(drawerVisible ? DockGlyphView.APP_RETURN : DockGlyphView.GRID);
    }

    public void refreshClockAndStatus() {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);
        tvClock.setText(fmt.format(new Date()));

        boolean wifiUp = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                wifiUp = ni != null && ni.isConnected();
            }
        } catch (Throwable ignored) {}
        wifiDot.setAlpha(wifiUp ? 1.0f : 0.30f);

        boolean btOn = false;
        try {
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            btOn = ba != null && ba.isEnabled();
        } catch (Throwable ignored) {}
        btDot.setAlpha(btOn ? 1.0f : 0.30f);
    }

    private static final class DockGlyphView extends View {
        static final int GRID = 1;
        static final int APP_RETURN = 2;
        static final int BACK = 3;
        static final int MIC = 4;

        private int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        DockGlyphView(Context ctx, int kind, int color) {
            super(ctx);
            this.kind = kind;
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setKind(int k) {
            if (this.kind != k) {
                this.kind = k;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float s = Math.min(w, h);
            if (s <= 0) return;
            c.save();
            c.translate((w - s) * 0.5f, (h - s) * 0.5f);
            if (kind == GRID) {
                paint.setStyle(Paint.Style.FILL);
                float r = s * 0.11f;
                float[] pts = {s * 0.22f, s * 0.50f, s * 0.78f};
                for (float y : pts) {
                    for (float x : pts) {
                        c.drawRoundRect(new RectF(x - r, y - r, x + r, y + r), r * 0.45f, r * 0.45f, paint);
                    }
                }
            } else if (kind == APP_RETURN) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(s * 0.11f);
                c.drawRoundRect(new RectF(s * 0.14f, s * 0.18f, s * 0.86f, s * 0.82f), s * 0.14f, s * 0.14f, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(s * 0.26f, s * 0.32f, s * 0.60f, s * 0.68f), s * 0.08f, s * 0.08f, paint);
            } else if (kind == BACK) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(s * 0.12f);
                path.reset();
                path.moveTo(s * 0.64f, s * 0.18f);
                path.lineTo(s * 0.30f, s * 0.50f);
                path.lineTo(s * 0.64f, s * 0.82f);
                c.drawPath(path, paint);
            } else if (kind == MIC) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(s * 0.10f);
                c.drawRoundRect(new RectF(s * 0.36f, s * 0.12f, s * 0.64f, s * 0.56f), s * 0.14f, s * 0.14f, paint);
                c.drawArc(new RectF(s * 0.24f, s * 0.28f, s * 0.76f, s * 0.70f), 0f, 180f, false, paint);
                c.drawLine(s * 0.50f, s * 0.70f, s * 0.50f, s * 0.88f, paint);
                c.drawLine(s * 0.34f, s * 0.88f, s * 0.66f, s * 0.88f, paint);
            }
            c.restore();
        }
    }
}
