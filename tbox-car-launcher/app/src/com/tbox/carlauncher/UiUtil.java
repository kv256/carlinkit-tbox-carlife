package com.tbox.carlauncher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.MotionEvent;
import android.view.View;

public final class UiUtil {
    public static final int BG_CANVAS = 0xFF070A0F;
    public static final int CARD_BG_TOP = 0xFF151B26;
    public static final int CARD_BG_BOT = 0xFF10151E;
    public static final int CARD_BORDER = 0x22FFFFFF;
    public static final int ACCENT_BLUE = 0xFF38BDF8;
    public static final int ACCENT_GREEN = 0xFF22C55E;
    public static final int ACCENT_AMBER = 0xFFF59E0B;
    public static final int TEXT_PRIMARY = 0xFFF8FAFC;
    public static final int TEXT_SECONDARY = 0xFF94A3B8;
    public static final int TEXT_MUTED = 0xFF64748B;

    private UiUtil() {}

    public static GradientDrawable cardDrawable(int topColor, int botColor, float radiusPx, int borderColor, int borderPx) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{topColor, botColor});
        gd.setCornerRadius(radiusPx);
        if (borderPx > 0 && borderColor != 0) {
            gd.setStroke(borderPx, borderColor);
        }
        return gd;
    }

    public static GradientDrawable solidRounded(int color, float radiusPx, int borderColor, int borderPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radiusPx);
        if (borderPx > 0 && borderColor != 0) {
            gd.setStroke(borderPx, borderColor);
        }
        return gd;
    }

    public static GradientDrawable glassCard(float radiusPx) {
        return cardDrawable(CARD_BG_TOP, CARD_BG_BOT, radiusPx, CARD_BORDER, 2);
    }

    public static void addPressScale(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.88f).setDuration(90).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start();
                        break;
                }
                return false;
            }
        });
    }

    public static Bitmap drawableToRoundedBitmap(Drawable d, int sizePx, float radiusPx) {
        if (d == null || sizePx <= 0) return null;
        Bitmap raw = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas rc = new Canvas(raw);
        d.setBounds(0, 0, sizePx, sizePx);
        d.draw(rc);
        return roundBitmap(raw, sizePx, radiusPx);
    }

    public static Bitmap roundBitmap(Bitmap src, int sizePx, float radiusPx) {
        if (src == null || sizePx <= 0) return null;
        Bitmap scaled;
        if (src.getWidth() == sizePx && src.getHeight() == sizePx) {
            scaled = src;
        } else {
            // Center crop
            int sw = src.getWidth();
            int sh = src.getHeight();
            int side = Math.min(sw, sh);
            int x = (sw - side) / 2;
            int y = (sh - side) / 2;
            Bitmap cropped = Bitmap.createBitmap(src, x, y, side, side);
            scaled = Bitmap.createScaledBitmap(cropped, sizePx, sizePx, true);
            if (cropped != src && cropped != scaled) cropped.recycle();
        }
        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF r = new RectF(0, 0, sizePx, sizePx);
        c.drawRoundRect(r, radiusPx, radiusPx, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        c.drawBitmap(scaled, 0, 0, p);
        if (scaled != src) scaled.recycle();
        return out;
    }

    /**
     * Extract a tasteful, muted dark automotive tint from album art for the Music card background.
     */
    public static int extractDarkTint(Bitmap bmp, int fallbackColor) {
        if (bmp == null || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return fallbackColor;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        long rSum = 0, gSum = 0, bSum = 0, weightSum = 0;
        int stepX = Math.max(1, w / 16);
        int stepY = Math.max(1, h / 16);
        float[] hsv = new float[3];
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int px = bmp.getPixel(x, y);
                int a = Color.alpha(px);
                if (a < 128) continue;
                int r = Color.red(px);
                int g = Color.green(px);
                int b = Color.blue(px);
                Color.RGBToHSV(r, g, b, hsv);
                int wt = 1 + (int) (hsv[1] * 6f);
                if (hsv[2] < 0.12f || hsv[2] > 0.95f) wt = 1;
                rSum += (long) r * wt;
                gSum += (long) g * wt;
                bSum += (long) b * wt;
                weightSum += wt;
            }
        }
        if (weightSum == 0) return fallbackColor;
        int avgR = (int) (rSum / weightSum);
        int avgG = (int) (gSum / weightSum);
        int avgB = (int) (bSum / weightSum);
        Color.RGBToHSV(avgR, avgG, avgB, hsv);
        hsv[1] = Math.min(0.58f, Math.max(0.22f, hsv[1] * 0.85f));
        hsv[2] = Math.min(0.32f, Math.max(0.18f, hsv[2] * 0.42f));
        return Color.HSVToColor(0xFF, hsv);
    }

    public static String getAppLabel(Context ctx, String pkg) {
        if (pkg == null) return "";
        if ("com.carsyso.bluetooth".equals(pkg) || "com.android.bluetooth".equals(pkg)) {
            return "蓝牙音乐";
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return cs != null ? cs.toString() : pkg;
        } catch (Throwable t) {
            return pkg;
        }
    }

    public static String formatMeters(int meters) {
        if (meters < 0) meters = 0;
        if (meters < 1000) {
            return meters + " 米";
        }
        float km = meters / 1000f;
        if (km < 10f) {
            return String.format(java.util.Locale.US, "%.1f 公里", km);
        }
        return String.format(java.util.Locale.US, "%.0f 公里", km);
    }

    public static String formatDistanceNumber(int meters) {
        if (meters < 0) meters = 0;
        if (meters < 1000) return String.valueOf(meters);
        float km = meters / 1000f;
        if (km < 10f) return String.format(java.util.Locale.US, "%.1f", km);
        return String.format(java.util.Locale.US, "%.0f", km);
    }

    public static String formatDistanceUnit(int meters) {
        return meters < 1000 ? "米" : "公里";
    }

    public static String formatDurationShort(int seconds) {
        if (seconds <= 0) return "< 1分钟";
        int mins = (seconds + 30) / 60;
        if (mins < 60) return mins + "分钟";
        int hrs = mins / 60;
        int rem = mins % 60;
        if (rem == 0) return hrs + "小时";
        return hrs + "小时" + rem + "分";
    }

    public static String formatMs(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000L;
        long m = totalSec / 60L;
        long s = totalSec % 60L;
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }
}
