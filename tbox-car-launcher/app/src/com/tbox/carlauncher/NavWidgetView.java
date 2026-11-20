package com.tbox.carlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Top-Right 512x345 Dual-Mode Navigation Card:
 * - Idle Mode: Date header + prominent "回家" & "去公司" quick-nav buttons + "打开高德 / 搜目的地" bar
 * - Active Nav Mode: Large vector turn arrow + bold distance + next road + remaining distance/time/ETA + progress bar
 */
public class NavWidgetView extends FrameLayout {
    public interface Callbacks {
        void onGoHomeClicked();
        void onGoCompanyClicked();
        void onOpenMapClicked();
        void onSearchClicked();
        void onNavSettingsClicked();
    }

    private final Callbacks callbacks;
    private final LinearLayout idleRoot;
    private final LinearLayout activeRoot;

    // Idle views
    private final TextView tvDate;
    private final TextView tvHomeSub;
    private final TextView tvCompanySub;

    // Active nav views
    private final ManeuverIconView maneuverIcon;
    private final TextView tvDistNum;
    private final TextView tvDistUnit;
    private final TextView tvActionHint;
    private final TextView tvNextRoad;
    private final TextView tvRemainSummary;
    private final TextView tvEta;
    private final ProgressStripView routeProgress;

    public NavWidgetView(Context context, Callbacks cb) {
        super(context);
        this.callbacks = cb;
        setBackground(UiUtil.glassCard(24f));
        setClipToOutline(true);

        // ===== IDLE MODE =====
        idleRoot = new LinearLayout(context);
        idleRoot.setOrientation(LinearLayout.VERTICAL);
        idleRoot.setPadding(22, 18, 22, 18);

        // Header row: Date + AMap badge + gear
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        tvDate = new TextView(context);
        tvDate.setTextColor(UiUtil.TEXT_SECONDARY);
        tvDate.setTextSize(TypedValue.COMPLEX_UNIT_PX, 21f);
        tvDate.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(tvDate, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView tvBadge = new TextView(context);
        tvBadge.setText("高德导航");
        tvBadge.setTextColor(0xFF7DD3FC);
        tvBadge.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f);
        tvBadge.setPadding(14, 5, 14, 5);
        tvBadge.setBackground(UiUtil.solidRounded(0x2238BDF8, 14f, 0x4438BDF8, 1));
        header.addView(tvBadge);

        View gearBtn = makeSmallIconBtn(context, ICON_GEAR);
        gearBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callbacks != null) callbacks.onNavSettingsClicked();
            }
        });
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(42, 42);
        gearLp.leftMargin = 10;
        header.addView(gearBtn, gearLp);

        idleRoot.addView(header, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Middle: Two prominent quick-nav buttons ("回家" & "去公司")
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        rowLp.topMargin = 16;
        rowLp.bottomMargin = 14;

        tvHomeSub = new TextView(context);
        View homeCard = makeQuickDestCard(
            context, "回家", tvHomeSub, "一键开始导航",
            0xFF1A437A, 0xFF112A4E, 0x4D38BDF8, ICON_HOME,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (callbacks != null) callbacks.onGoHomeClicked();
                }
            }
        );
        LinearLayout.LayoutParams leftCardLp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        leftCardLp.rightMargin = 8;
        btnRow.addView(homeCard, leftCardLp);

        tvCompanySub = new TextView(context);
        View companyCard = makeQuickDestCard(
            context, "去公司", tvCompanySub, "一键开始导航",
            0xFF5C3A16, 0xFF3A230D, 0x4DF59E0B, ICON_WORK,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (callbacks != null) callbacks.onGoCompanyClicked();
                }
            }
        );
        LinearLayout.LayoutParams rightCardLp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        rightCardLp.leftMargin = 8;
        btnRow.addView(companyCard, rightCardLp);

        idleRoot.addView(btnRow, rowLp);

        // Bottom subtle bar: Open Map & Search
        LinearLayout bottomRow = new LinearLayout(context);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);

        View openMapBtn = makePillAction(context, "打开地图", 0xFF1E293B, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callbacks != null) callbacks.onOpenMapClicked();
            }
        });
        LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(0, 52, 1f);
        b1Lp.rightMargin = 8;
        bottomRow.addView(openMapBtn, b1Lp);

        View searchBtn = makePillAction(context, "搜目的地", 0xFF1E293B, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callbacks != null) callbacks.onSearchClicked();
            }
        });
        LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(0, 52, 1f);
        b2Lp.leftMargin = 8;
        bottomRow.addView(searchBtn, b2Lp);

        idleRoot.addView(bottomRow, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(idleRoot, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // ===== ACTIVE TURN-BY-TURN NAVIGATION MODE =====
        activeRoot = new LinearLayout(context);
        activeRoot.setOrientation(LinearLayout.VERTICAL);
        activeRoot.setPadding(24, 20, 24, 20);
        activeRoot.setBackground(UiUtil.cardDrawable(0xFF0E2C3A, 0xFF0B1924, 24f, 0x5538BDF8, 2));
        activeRoot.setVisibility(GONE);
        activeRoot.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callbacks != null) callbacks.onOpenMapClicked();
            }
        });
        UiUtil.addPressScale(activeRoot);

        // Top section: Maneuver Icon + Distance & Road Name
        LinearLayout topGuide = new LinearLayout(context);
        topGuide.setOrientation(LinearLayout.HORIZONTAL);
        topGuide.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout iconBox = new FrameLayout(context);
        iconBox.setBackground(UiUtil.solidRounded(0xFF164E63, 22f, 0x5538BDF8, 2));
        iconBox.setPadding(14, 14, 14, 14);
        maneuverIcon = new ManeuverIconView(context);
        iconBox.addView(maneuverIcon, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        topGuide.addView(iconBox, new LinearLayout.LayoutParams(126, 126));

        LinearLayout guideText = new LinearLayout(context);
        guideText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gtLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        gtLp.leftMargin = 20;

        // Distance line: huge number + unit
        LinearLayout distRow = new LinearLayout(context);
        distRow.setOrientation(LinearLayout.HORIZONTAL);
        distRow.setGravity(Gravity.BOTTOM);

        tvDistNum = new TextView(context);
        tvDistNum.setTextColor(0xFFFFFFFF);
        tvDistNum.setTextSize(TypedValue.COMPLEX_UNIT_PX, 54f);
        tvDistNum.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvDistNum.setIncludeFontPadding(false);
        distRow.addView(tvDistNum);

        tvDistUnit = new TextView(context);
        tvDistUnit.setTextColor(0xFF7DD3FC);
        tvDistUnit.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f);
        tvDistUnit.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams unitLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        unitLp.leftMargin = 6;
        unitLp.bottomMargin = 5;
        distRow.addView(tvDistUnit, unitLp);

        tvActionHint = new TextView(context);
        tvActionHint.setTextColor(0xFFBAE6FD);
        tvActionHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        hintLp.leftMargin = 10;
        hintLp.bottomMargin = 5;
        distRow.addView(tvActionHint, hintLp);

        guideText.addView(distRow);

        tvNextRoad = new TextView(context);
        tvNextRoad.setTextColor(0xFFF8FAFC);
        tvNextRoad.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26f);
        tvNextRoad.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvNextRoad.setMaxLines(2);
        tvNextRoad.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams roadLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        roadLp.topMargin = 8;
        guideText.addView(tvNextRoad, roadLp);

        topGuide.addView(guideText, gtLp);
        activeRoot.addView(topGuide, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // Progress strip
        routeProgress = new ProgressStripView(context, 0xFF22C55E, 0x33FFFFFF);
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 8);
        progLp.topMargin = 14;
        progLp.bottomMargin = 14;
        activeRoot.addView(routeProgress, progLp);

        // Bottom telemetry row: Remaining distance/time + ETA
        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);

        tvRemainSummary = new TextView(context);
        tvRemainSummary.setTextColor(0xFFCBD5E1);
        tvRemainSummary.setTextSize(TypedValue.COMPLEX_UNIT_PX, 21f);
        tvRemainSummary.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        footer.addView(tvRemainSummary, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        tvEta = new TextView(context);
        tvEta.setTextColor(0xFF4ADE80);
        tvEta.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f);
        tvEta.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        footer.addView(tvEta);

        activeRoot.addView(footer, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(activeRoot, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        refreshDate();
    }

    public void refreshDate() {
        SimpleDateFormat fmt = new SimpleDateFormat("M月d日  EEEE", Locale.CHINA);
        tvDate.setText(fmt.format(new Date()));
    }

    public void refreshCustomLabels(LauncherPrefs prefs) {
        String h = prefs.getHomeName();
        String c = prefs.getCompanyName();
        tvHomeSub.setText(TextUtils.isEmpty(h) ? "高德一键回家" : h);
        tvCompanySub.setText(TextUtils.isEmpty(c) ? "高德一键去公司" : c);
    }

    public void updateNavState(AMapBridge.NavState s) {
        if (s == null || !s.navigating) {
            activeRoot.setVisibility(GONE);
            idleRoot.setVisibility(VISIBLE);
            return;
        }
        idleRoot.setVisibility(GONE);
        activeRoot.setVisibility(VISIBLE);

        maneuverIcon.setIconType(s.iconType);
        tvDistNum.setText(UiUtil.formatDistanceNumber(s.segRemainDis));
        tvDistUnit.setText(UiUtil.formatDistanceUnit(s.segRemainDis));
        tvActionHint.setText(ManeuverIconView.iconLabel(s.iconType));

        if (!TextUtils.isEmpty(s.nextRoadName)) {
            tvNextRoad.setText("进入 " + s.nextRoadName);
        } else if (!TextUtils.isEmpty(s.curRoadName)) {
            tvNextRoad.setText("当前 " + s.curRoadName);
        } else {
            tvNextRoad.setText(ManeuverIconView.iconLabel(s.iconType));
        }

        String remDis = UiUtil.formatMeters(s.routeRemainDis);
        String remTime = UiUtil.formatDurationShort(s.routeRemainTimeSec);
        tvRemainSummary.setText("剩余 " + remDis + " · " + remTime);

        Calendar etaCal = Calendar.getInstance();
        etaCal.add(Calendar.SECOND, Math.max(0, s.routeRemainTimeSec));
        String etaStr = String.format(Locale.US, "%02d:%02d",
            etaCal.get(Calendar.HOUR_OF_DAY), etaCal.get(Calendar.MINUTE));
        tvEta.setText(etaStr + " 到达");

        float progress = 0f;
        if (s.routeAllDis > 0 && s.routeRemainDis >= 0) {
            progress = 1f - ((float) s.routeRemainDis / (float) s.routeAllDis);
        }
        routeProgress.setProgress(progress);
    }

    private View makeQuickDestCard(Context ctx, String title, TextView subView, String defaultSub,
                                   int topColor, int botColor, int borderColor, int iconKind,
                                   OnClickListener click) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(20, 16, 18, 16);
        card.setBackground(UiUtil.cardDrawable(topColor, botColor, 20f, borderColor, 2));
        card.setOnClickListener(click);
        UiUtil.addPressScale(card);

        View icon = new SimpleVectorIcon(ctx, iconKind, 0xFFFFFFFF);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(44, 44);
        iconLp.bottomMargin = 14;
        card.addView(icon, iconLp);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        card.addView(tvTitle);

        subView.setText(defaultSub);
        subView.setTextColor(0xB3E2E8F0);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f);
        subView.setSingleLine(true);
        subView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        subLp.topMargin = 4;
        card.addView(subView, subLp);

        return card;
    }

    private View makePillAction(Context ctx, String label, int bgColor, OnClickListener click) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(UiUtil.solidRounded(bgColor, 16f, 0x22FFFFFF, 1));
        tv.setOnClickListener(click);
        UiUtil.addPressScale(tv);
        return tv;
    }

    private View makeSmallIconBtn(Context ctx, int kind) {
        FrameLayout f = new FrameLayout(ctx);
        f.setBackground(UiUtil.solidRounded(0x1EFFFFFF, 12f, 0, 0));
        f.setPadding(9, 9, 9, 9);
        f.addView(new SimpleVectorIcon(ctx, kind, 0xFFCBD5E1),
            new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        UiUtil.addPressScale(f);
        return f;
    }

    static final int ICON_HOME = 1;
    static final int ICON_WORK = 2;
    static final int ICON_GEAR = 3;

    private static final class SimpleVectorIcon extends View {
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        SimpleVectorIcon(Context ctx, int kind, int color) {
            super(ctx);
            this.kind = kind;
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float s = Math.min(w, h);
            if (s <= 0) return;
            c.save();
            c.translate((w - s) * 0.5f, (h - s) * 0.5f);
            if (kind == ICON_HOME) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(s * 0.10f);
                path.reset();
                path.moveTo(s * 0.14f, s * 0.50f);
                path.lineTo(s * 0.50f, s * 0.16f);
                path.lineTo(s * 0.86f, s * 0.50f);
                c.drawPath(path, paint);
                path.reset();
                path.moveTo(s * 0.24f, s * 0.44f);
                path.lineTo(s * 0.24f, s * 0.84f);
                path.lineTo(s * 0.76f, s * 0.84f);
                path.lineTo(s * 0.76f, s * 0.44f);
                c.drawPath(path, paint);
            } else if (kind == ICON_WORK) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(s * 0.095f);
                RectF body = new RectF(s * 0.14f, s * 0.34f, s * 0.86f, s * 0.82f);
                c.drawRoundRect(body, s * 0.10f, s * 0.10f, paint);
                RectF handle = new RectF(s * 0.35f, s * 0.18f, s * 0.65f, s * 0.34f);
                c.drawRoundRect(handle, s * 0.06f, s * 0.06f, paint);
                c.drawLine(s * 0.16f, s * 0.54f, s * 0.84f, s * 0.54f, paint);
            } else if (kind == ICON_GEAR) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(s * 0.11f);
                c.drawCircle(s * 0.5f, s * 0.5f, s * 0.24f, paint);
                for (int i = 0; i < 6; i++) {
                    double a = Math.toRadians(i * 60);
                    float x1 = (float) (s * 0.5 + Math.cos(a) * s * 0.32);
                    float y1 = (float) (s * 0.5 + Math.sin(a) * s * 0.32);
                    float x2 = (float) (s * 0.5 + Math.cos(a) * s * 0.43);
                    float y2 = (float) (s * 0.5 + Math.sin(a) * s * 0.43);
                    c.drawLine(x1, y1, x2, y2, paint);
                }
            }
            c.restore();
        }
    }

    static final class ProgressStripView extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress = 0f;

        ProgressStripView(Context ctx, int fgColor, int bgColor) {
            super(ctx);
            fgPaint.setColor(fgColor);
            bgPaint.setColor(bgColor);
        }

        void setProgress(float p) {
            if (p < 0f) p = 0f;
            if (p > 1f) p = 1f;
            this.progress = p;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float r = h * 0.5f;
            c.drawRoundRect(0, 0, w, h, r, r, bgPaint);
            float fw = Math.max(progress > 0f ? h : 0f, w * progress);
            if (fw > 0f) {
                c.drawRoundRect(0, 0, fw, h, r, r, fgPaint);
            }
        }
    }
}
