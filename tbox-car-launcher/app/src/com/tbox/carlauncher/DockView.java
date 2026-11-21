package com.tbox.carlauncher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
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
 * - Top: Crisp clock (long-press opens Settings) + vector Wi-Fi & Bluetooth status icons
 * - Middle: 4 customizable quick-launch app slots with active left pill indicator
 * - Bottom: Back button + Home/App-Drawer toggle (long-press triggers Voice Assistant)
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
    private final StatusVectorView wifiIcon;
    private final StatusVectorView btIcon;

    private final FrameLayout[] slotContainers = new FrameLayout[LauncherPrefs.DOCK_SLOT_COUNT];
    private final View[] slotIndicators = new View[LauncherPrefs.DOCK_SLOT_COUNT];
    private final ImageView[] slotIcons = new ImageView[LauncherPrefs.DOCK_SLOT_COUNT];
    private String[] slotPackages = new String[LauncherPrefs.DOCK_SLOT_COUNT];

    private final DockGlyphView homeGlyph;
    private final FrameLayout homeBtn;

    private String activePkg = "";
    private boolean drawerVisible = false;
    private boolean btA2dpConnected = false;

    public DockView(Context context, Callbacks cb) {
        super(context);
        this.callbacks = cb;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(8, 18, 8, 16);
        setBackground(UiUtil.cardDrawable(0xFF131823, 0xFF0E131B, 24f, UiUtil.CARD_BORDER, 1));

        // 1. Clock (long-press opens Launcher Settings) + vector Wi-Fi / BT status icons
        tvClock = new TextView(context);
        tvClock.setTextColor(0xFFFFFFFF);
        tvClock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 27f);
        tvClock.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvClock.setGravity(Gravity.CENTER);
        tvClock.setIncludeFontPadding(false);
        tvClock.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (callbacks != null) callbacks.onSettingsLongClicked();
                return true;
            }
        });
        addView(tvClock, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout statusRow = new LinearLayout(context);
        statusRow.setOrientation(HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER);
        LayoutParams stLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        stLp.topMargin = 10;
        stLp.bottomMargin = 14;

        wifiIcon = new StatusVectorView(context, StatusVectorView.WIFI);
        btIcon = new StatusVectorView(context, StatusVectorView.BT);
        statusRow.addView(wifiIcon, new LayoutParams(26, 24));
        LayoutParams btLp = new LayoutParams(24, 24);
        btLp.leftMargin = 12;
        statusRow.addView(btIcon, btLp);
        addView(statusRow, stLp);

        addDivider(context);

        // 2. Four quick-launch app slots
        LinearLayout slotsCol = new LinearLayout(context);
        slotsCol.setOrientation(VERTICAL);
        slotsCol.setGravity(Gravity.CENTER);
        LayoutParams scLp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        scLp.topMargin = 10;
        scLp.bottomMargin = 10;

        for (int i = 0; i < LauncherPrefs.DOCK_SLOT_COUNT; i++) {
            final int slot = i;
            FrameLayout item = new FrameLayout(context);
            LayoutParams itemLp = new LayoutParams(LayoutParams.MATCH_PARENT, 86);
            itemLp.topMargin = (i == 0) ? 0 : 12;
            item.setLayoutParams(itemLp);

            View indicator = new View(context);
            indicator.setBackground(UiUtil.solidRounded(UiUtil.ACCENT_BLUE, 3f, 0, 0));
            indicator.setVisibility(INVISIBLE);
            FrameLayout.LayoutParams indLp = new FrameLayout.LayoutParams(5, 34);
            indLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            item.addView(indicator, indLp);

            ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams icLp = new FrameLayout.LayoutParams(70, 70);
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

        // 3. Bottom system controls: Back + merged App Drawer (short press) / Voice Assistant (long press)
        View backBtn = makeUtilButton(context, new DockGlyphView(context, DockGlyphView.BACK, 0xFFE2E8F0),
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (callbacks != null) callbacks.onBackClicked();
                }
            }, null);
        LayoutParams utilLp1 = new LayoutParams(78, 58);
        utilLp1.topMargin = 14;
        addView(backBtn, utilLp1);

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
                    if (callbacks != null) callbacks.onVoiceClicked();
                    return true;
                }
            });
        LayoutParams utilLp2 = new LayoutParams(78, 62);
        utilLp2.topMargin = 12;
        addView(homeBtn, utilLp2);

        refreshClockAndStatus();
    }

    private void addDivider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(0x12FFFFFF);
        LayoutParams lp = new LayoutParams(52, 1);
        addView(v, lp);
    }

    private FrameLayout makeUtilButton(Context ctx, View glyph, OnClickListener click, OnLongClickListener longClick) {
        FrameLayout f = new FrameLayout(ctx);
        f.setBackground(UiUtil.solidRounded(0x12FFFFFF, 16f, 0, 0));
        f.setPadding(18, 12, 18, 12);
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
                    bmp = UiUtil.drawableToRoundedBitmap(d, 70, 17f);
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

    public void setBtConnected(boolean connected) {
        if (this.btA2dpConnected != connected) {
            this.btA2dpConnected = connected;
            refreshClockAndStatus();
        }
    }

    private void updateHighlights() {
        for (int i = 0; i < LauncherPrefs.DOCK_SLOT_COUNT; i++) {
            boolean active = !drawerVisible && slotPackages[i] != null && slotPackages[i].equals(activePkg);
            slotIndicators[i].setVisibility(active ? VISIBLE : INVISIBLE);
            slotContainers[i].setBackground(active
                ? UiUtil.solidRounded(0x1C38BDF8, 18f, 0, 0)
                : null);
        }
        homeBtn.setBackground(drawerVisible
            ? UiUtil.solidRounded(0x2E38BDF8, 16f, 0, 0)
            : UiUtil.solidRounded(0x12FFFFFF, 16f, 0, 0));
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
        wifiIcon.setActive(wifiUp);

        boolean btActive = btA2dpConnected;
        if (!btActive) {
            try {
                BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                btActive = ba != null && ba.isEnabled()
                    && (ba.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
                        || ba.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED);
            } catch (Throwable ignored) {}
        }
        btIcon.setActive(btActive);
    }

    /**
     * Crisp vector Wi-Fi and Bluetooth connection icons for Dock header.
     */
    private static final class StatusVectorView extends View {
        static final int WIFI = 1;
        static final int BT = 2;

        private final int kind;
        private boolean active = false;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        StatusVectorView(Context ctx, int kind) {
            super(ctx);
            this.kind = kind;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setActive(boolean a) {
            if (this.active != a) {
                this.active = a;
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

            int color = active
                ? (kind == WIFI ? 0xFFE2E8F0 : 0xFF38BDF8)
                : 0x4494A3B8;
            paint.setColor(color);

            if (kind == WIFI) {
                // Crisp concentric 3-arc + round dot Wi-Fi symbol (CarPlay/SF-Symbols proportion)
                float cx = s * 0.50f;
                float cy = s * 0.77f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(s * 0.092f);
                float r3 = s * 0.53f;
                float r2 = s * 0.36f;
                float r1 = s * 0.20f;
                c.drawArc(new RectF(cx - r3, cy - r3, cx + r3, cy + r3), 226f, 88f, false, paint);
                c.drawArc(new RectF(cx - r2, cy - r2, cx + r2, cy + r2), 226f, 88f, false, paint);
                c.drawArc(new RectF(cx - r1, cy - r1, cx + r1, cy + r1), 226f, 88f, false, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, s * 0.072f, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(s * 0.105f);
                path.reset();
                path.moveTo(s * 0.28f, s * 0.30f);
                path.lineTo(s * 0.72f, s * 0.68f);
                path.lineTo(s * 0.50f, s * 0.88f);
                path.lineTo(s * 0.50f, s * 0.12f);
                path.lineTo(s * 0.72f, s * 0.32f);
                path.lineTo(s * 0.28f, s * 0.70f);
                c.drawPath(path, paint);
            }
            c.restore();
        }
    }

    private static final class DockGlyphView extends View {
        static final int GRID = 1;
        static final int APP_RETURN = 2;
        static final int BACK = 3;

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
            }
            c.restore();
        }
    }
}
