package com.tbox.carlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Top-Right 512x345 Dual-Mode Navigation Card:
 * - Idle Mode: Date header + prominent "回家" & "去公司" quick-nav buttons + "打开高德 / 搜目的地" bar
 * - Active Nav Mode: Large vector turn arrow + bold distance + next road + AMap lane guidance strip +
 *   TMC traffic-congestion progress bar + remaining distance/time/ETA
 */
public class NavWidgetView extends FrameLayout {
    public interface Callbacks {
        void onGoHomeClicked();
        void onGoCompanyClicked();
        void onOpenMapClicked();
        void onSearchClicked();
    }

    private final Callbacks callbacks;
    private final LinearLayout idleRoot;
    private final LinearLayout activeRoot;

    // Idle views
    private final TextView tvDate;
    private final TextView tvBadge;
    private final TextView tvCpuTemp;

    // Active nav views
    private final ManeuverIconView maneuverIcon;
    private final TextView tvDistNum;
    private final TextView tvDistUnit;
    private final TextView tvActionHint;
    private final TextView tvNextRoad;
    private final LaneGuidanceView laneGuidanceView;
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

        tvBadge = new TextView(context);
        tvBadge.setText("高德导航");
        tvBadge.setTextColor(0xFF7DD3FC);
        tvBadge.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f);
        tvBadge.setPadding(14, 5, 14, 5);
        tvBadge.setBackground(UiUtil.solidRounded(0x1C38BDF8, 14f, 0, 0));
        header.addView(tvBadge);

        idleRoot.addView(header, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Middle: Two prominent quick-nav buttons ("回家" & "去公司")
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        rowLp.topMargin = 16;
        rowLp.bottomMargin = 14;

        View homeCard = makeQuickDestCard(
            context, "回家",
            0xFF1A4073, 0xFF11284A, ICON_HOME,
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

        View companyCard = makeQuickDestCard(
            context, "去公司",
            0xFF563616, 0xFF36210C, ICON_WORK,
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
        activeRoot.setPadding(22, 18, 22, 16);
        activeRoot.setBackground(UiUtil.cardDrawable(0xFF0E2C3A, 0xFF0B1924, 24f, 0x2638BDF8, 1));
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
        iconBox.setBackground(UiUtil.solidRounded(0xFF164E63, 22f, 0, 0));
        iconBox.setPadding(12, 12, 12, 12);
        maneuverIcon = new ManeuverIconView(context);
        iconBox.addView(maneuverIcon, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        topGuide.addView(iconBox, new LinearLayout.LayoutParams(112, 112));

        LinearLayout guideText = new LinearLayout(context);
        guideText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gtLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        gtLp.leftMargin = 18;

        // Distance line: huge number + unit
        LinearLayout distRow = new LinearLayout(context);
        distRow.setOrientation(LinearLayout.HORIZONTAL);
        distRow.setGravity(Gravity.BOTTOM);

        tvDistNum = new TextView(context);
        tvDistNum.setTextColor(0xFFFFFFFF);
        tvDistNum.setTextSize(TypedValue.COMPLEX_UNIT_PX, 50f);
        tvDistNum.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvDistNum.setIncludeFontPadding(false);
        distRow.addView(tvDistNum);

        tvDistUnit = new TextView(context);
        tvDistUnit.setTextColor(0xFF7DD3FC);
        tvDistUnit.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f);
        tvDistUnit.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams unitLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        unitLp.leftMargin = 5;
        unitLp.bottomMargin = 5;
        distRow.addView(tvDistUnit, unitLp);

        tvActionHint = new TextView(context);
        tvActionHint.setTextColor(0xFFBAE6FD);
        tvActionHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        hintLp.leftMargin = 9;
        hintLp.bottomMargin = 5;
        distRow.addView(tvActionHint, hintLp);

        guideText.addView(distRow);

        tvNextRoad = new TextView(context);
        tvNextRoad.setTextColor(0xFFF8FAFC);
        tvNextRoad.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f);
        tvNextRoad.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvNextRoad.setMaxLines(2);
        tvNextRoad.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams roadLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        roadLp.topMargin = 6;
        guideText.addView(tvNextRoad, roadLp);

        topGuide.addView(guideText, gtLp);
        activeRoot.addView(topGuide, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // Real-time AMap lane guidance strip (shown dynamically when KEY_TYPE=13012 reports lanes)
        laneGuidanceView = new LaneGuidanceView(context);
        laneGuidanceView.setVisibility(GONE);
        LinearLayout.LayoutParams laneLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 66);
        laneLp.topMargin = 10;
        laneLp.bottomMargin = 2;
        activeRoot.addView(laneGuidanceView, laneLp);

        // TMC traffic-congestion progress strip (with fallback to smooth route progress)
        routeProgress = new ProgressStripView(context, 0xFF22C55E, 0x33FFFFFF);
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 16);
        progLp.topMargin = 10;
        progLp.bottomMargin = 10;
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

        // Optional CPU temperature badge anchored cleanly at top-right (visible in both idle & nav modes)
        tvCpuTemp = new TextView(context);
        tvCpuTemp.setTextColor(0xFF7DD3FC);
        tvCpuTemp.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f);
        tvCpuTemp.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvCpuTemp.setPadding(14, 5, 14, 5);
        tvCpuTemp.setBackground(UiUtil.solidRounded(0x2038BDF8, 14f, 0x3838BDF8, 1));
        tvCpuTemp.setVisibility(GONE);
        FrameLayout.LayoutParams cpuLp = new FrameLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        cpuLp.topMargin = 18;
        cpuLp.rightMargin = 22;
        addView(tvCpuTemp, cpuLp);

        refreshDate();
    }

    public void setCpuTemp(boolean visible, float tempC) {
        if (!visible) {
            tvCpuTemp.setVisibility(GONE);
            tvBadge.setVisibility(VISIBLE);
            return;
        }
        tvBadge.setVisibility(GONE);
        tvCpuTemp.setVisibility(VISIBLE);
        int color = UiUtil.cpuTempColor(tempC);
        int bg = (color & 0x00FFFFFF) | 0x22000000;
        int stroke = (color & 0x00FFFFFF) | 0x44000000;
        tvCpuTemp.setText("CPU " + UiUtil.formatCpuTemp(tempC));
        tvCpuTemp.setTextColor(color);
        tvCpuTemp.setBackground(UiUtil.solidRounded(bg, 14f, stroke, 1));
    }

    public void refreshDate() {
        SimpleDateFormat fmt = new SimpleDateFormat("M月d日  EEEE", Locale.CHINA);
        tvDate.setText(fmt.format(new Date()));
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
        if (s.laneEnabled && !s.lanes.isEmpty()) {
            laneGuidanceView.setLanes(s.lanes);
            laneGuidanceView.setVisibility(VISIBLE);
        } else {
            laneGuidanceView.setVisibility(GONE);
        }
        if (s.tmcEnabled && !s.tmcSegments.isEmpty()) {
            routeProgress.setTmcSegments(s.tmcSegments, s.tmcTotalDis, s.tmcFinishDis, progress);
        } else {
            routeProgress.clearTmc();
            routeProgress.setProgress(progress);
        }
    }

    private View makeQuickDestCard(Context ctx, String title,
                                   int topColor, int botColor, int iconKind,
                                   OnClickListener click) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(20, 16, 20, 16);
        card.setBackground(UiUtil.cardDrawable(topColor, botColor, 20f, 0, 0));
        card.setOnClickListener(click);
        UiUtil.addPressScale(card);

        View icon = new SimpleVectorIcon(ctx, iconKind, 0xFFFFFFFF);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(48, 48);
        iconLp.bottomMargin = 14;
        card.addView(icon, iconLp);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setGravity(Gravity.CENTER);
        card.addView(tvTitle);

        return card;
    }

    private View makePillAction(Context ctx, String label, int bgColor, OnClickListener click) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(UiUtil.solidRounded(bgColor, 16f, 0, 0));
        tv.setOnClickListener(click);
        UiUtil.addPressScale(tv);
        return tv;
    }

    static final int ICON_HOME = 1;
    static final int ICON_WORK = 2;

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
            }
            c.restore();
        }
    }

    /**
     * CarPlay / AMapAuto cockpit lane guidance strip rendered from KEY_TYPE=13012 (EXTRA_DRIVE_WAY).
     * Decodes AMapAuto's exact drive_way_lane_Back_icon codes (0..61) into multi-branch vector arrows,
     * highlighting recommended branches in crisp white over dimmed non-recommended directions.
     */
    private static final class LaneGuidanceView extends View {
        private static final int DIR_STRAIGHT = 1;
        private static final int DIR_LEFT     = 1 << 1;
        private static final int DIR_RIGHT    = 1 << 2;
        private static final int DIR_UTURN_L  = 1 << 3;
        private static final int DIR_UTURN_R  = 1 << 4;
        private static final int DIR_BUS      = 1 << 5;
        private static final int DIR_VARIABLE = 1 << 6;

        private final List<AMapBridge.LaneItem> lanes = new ArrayList<AMapBridge.LaneItem>();
        private final Paint pillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pillBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeCellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dimStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dimFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hiStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hiFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF pillRect = new RectF();
        private final RectF cellRect = new RectF();

        LaneGuidanceView(Context ctx) {
            super(ctx);
            pillBgPaint.setColor(0xB8071624);
            pillBgPaint.setStyle(Paint.Style.FILL);

            pillBorderPaint.setColor(0x3838BDF8);
            pillBorderPaint.setStyle(Paint.Style.STROKE);
            pillBorderPaint.setStrokeWidth(1.5f);

            activeCellPaint.setColor(0x2838BDF8);
            activeCellPaint.setStyle(Paint.Style.FILL);

            dividerPaint.setColor(0x40CBD5E1);
            dividerPaint.setStyle(Paint.Style.STROKE);
            dividerPaint.setStrokeWidth(1.5f);
            dividerPaint.setPathEffect(new DashPathEffect(new float[]{6f, 5f}, 0f));

            dimStrokePaint.setColor(0x5AFFFFFF);
            dimStrokePaint.setStyle(Paint.Style.STROKE);
            dimStrokePaint.setStrokeCap(Paint.Cap.ROUND);
            dimStrokePaint.setStrokeJoin(Paint.Join.ROUND);

            dimFillPaint.setColor(0x5AFFFFFF);
            dimFillPaint.setStyle(Paint.Style.FILL);

            hiStrokePaint.setColor(0xFFFFFFFF);
            hiStrokePaint.setStyle(Paint.Style.STROKE);
            hiStrokePaint.setStrokeCap(Paint.Cap.ROUND);
            hiStrokePaint.setStrokeJoin(Paint.Join.ROUND);

            hiFillPaint.setColor(0xFFFFFFFF);
            hiFillPaint.setStyle(Paint.Style.FILL);

            badgeTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        void setLanes(List<AMapBridge.LaneItem> items) {
            lanes.clear();
            if (items != null) lanes.addAll(items);
            invalidate();
        }

        // Returns packed int: low 16 bits = allDirs, high 16 bits = selDirs
        private static int decodeLaneCode(int code, boolean advised, int laneType) {
            int all = 0;
            int sel = 0;
            switch (code) {
                // Dimmed single / combo lanes (global_image_landback_0..e)
                case 0:  all = DIR_STRAIGHT; break;
                case 1:  all = DIR_LEFT; break;
                case 2:  all = DIR_STRAIGHT | DIR_LEFT; break;
                case 3:  all = DIR_RIGHT; break;
                case 4:  all = DIR_STRAIGHT | DIR_RIGHT; break;
                case 5:  all = DIR_UTURN_L; break;
                case 6:  all = DIR_LEFT | DIR_RIGHT; break;
                case 7:  all = DIR_STRAIGHT | DIR_LEFT | DIR_RIGHT; break;
                case 8:  all = DIR_UTURN_R; break;
                case 9:  all = DIR_STRAIGHT | DIR_UTURN_L; break;
                case 10: all = DIR_STRAIGHT | DIR_UTURN_R; break;
                case 11: all = DIR_LEFT | DIR_UTURN_L; break;
                case 12: all = DIR_RIGHT | DIR_UTURN_R; break;
                case 13: all = DIR_STRAIGHT; break;
                case 14: all = DIR_LEFT | DIR_UTURN_L; break;

                // Highlighted single lanes (global_image_landfront_0,1,3,5,8,d) & special lanes
                case 15: all = DIR_STRAIGHT; sel = DIR_STRAIGHT; break;
                case 16: all = DIR_LEFT;     sel = DIR_LEFT; break;
                case 17: all = DIR_RIGHT | DIR_UTURN_L; break;
                case 18: all = DIR_RIGHT;    sel = DIR_RIGHT; break;
                case 19: all = DIR_STRAIGHT | DIR_RIGHT | DIR_UTURN_L; break;
                case 20: all = DIR_UTURN_L;  sel = DIR_UTURN_L; break;
                case 21: all = DIR_BUS; break;
                case 22: all = DIR_STRAIGHT | DIR_LEFT | DIR_RIGHT; break;
                case 23:
                    if (laneType == 3) {
                        all = DIR_VARIABLE;
                    } else {
                        all = DIR_UTURN_R;
                        sel = DIR_UTURN_R;
                    }
                    break;
                case 24: all = DIR_STRAIGHT | DIR_UTURN_L; break;
                case 25: all = DIR_STRAIGHT | DIR_UTURN_R; break;
                case 26: all = DIR_LEFT | DIR_UTURN_L; break;
                case 27: all = DIR_RIGHT | DIR_UTURN_R; break;
                case 28: all = DIR_STRAIGHT; sel = DIR_STRAIGHT; break;
                case 29: all = DIR_LEFT | DIR_UTURN_L; break;

                // Highlighted combo lanes (global_image_landfront_20..148)
                case 30: all = DIR_STRAIGHT | DIR_LEFT;                sel = DIR_STRAIGHT; break;
                case 31: all = DIR_STRAIGHT | DIR_LEFT;                sel = DIR_LEFT; break;
                case 32: all = DIR_STRAIGHT | DIR_RIGHT;               sel = DIR_STRAIGHT; break;
                case 33: all = DIR_STRAIGHT | DIR_RIGHT;               sel = DIR_RIGHT; break;
                case 34: all = DIR_LEFT | DIR_RIGHT;                   sel = DIR_LEFT; break;
                case 35: all = DIR_LEFT | DIR_RIGHT;                   sel = DIR_RIGHT; break;
                case 36: all = DIR_STRAIGHT | DIR_LEFT | DIR_RIGHT;    sel = DIR_STRAIGHT; break;
                case 37: all = DIR_STRAIGHT | DIR_LEFT | DIR_RIGHT;    sel = DIR_LEFT; break;
                case 38: all = DIR_STRAIGHT | DIR_LEFT | DIR_RIGHT;    sel = DIR_RIGHT; break;
                case 39: all = DIR_STRAIGHT | DIR_UTURN_L;             sel = DIR_STRAIGHT; break;
                case 40: all = DIR_STRAIGHT | DIR_UTURN_L;             sel = DIR_UTURN_L; break;
                case 41: all = DIR_STRAIGHT | DIR_UTURN_R;             sel = DIR_STRAIGHT; break;
                case 42: all = DIR_STRAIGHT | DIR_UTURN_R;             sel = DIR_UTURN_R; break;
                case 43: all = DIR_LEFT | DIR_UTURN_L;                 sel = DIR_LEFT; break;
                case 44: all = DIR_LEFT | DIR_UTURN_L;                 sel = DIR_UTURN_L; break;
                case 45: all = DIR_RIGHT | DIR_UTURN_R;                sel = DIR_RIGHT; break;
                case 46: all = DIR_RIGHT | DIR_UTURN_R;                sel = DIR_UTURN_R; break;
                case 47: all = DIR_LEFT | DIR_UTURN_L;                 sel = DIR_LEFT; break;
                case 48: all = DIR_LEFT | DIR_UTURN_L;                 sel = DIR_UTURN_L; break;
                case 49: all = DIR_STRAIGHT | DIR_LEFT | DIR_UTURN_L;  sel = DIR_STRAIGHT; break;
                case 50: all = DIR_STRAIGHT | DIR_LEFT | DIR_UTURN_L;  sel = DIR_LEFT; break;
                case 51: all = DIR_STRAIGHT | DIR_LEFT | DIR_UTURN_L;  sel = DIR_UTURN_L; break;
                case 52: all = DIR_RIGHT | DIR_UTURN_L;                sel = DIR_RIGHT; break;
                case 53: all = DIR_RIGHT | DIR_UTURN_L;                sel = DIR_UTURN_L; break;
                case 54: all = DIR_LEFT | DIR_RIGHT | DIR_UTURN_L;     sel = DIR_LEFT; break;
                case 55: all = DIR_LEFT | DIR_RIGHT | DIR_UTURN_L;     sel = DIR_RIGHT; break;
                case 56: all = DIR_LEFT | DIR_RIGHT | DIR_UTURN_L;     sel = DIR_UTURN_L; break;
                case 57: all = DIR_STRAIGHT | DIR_RIGHT | DIR_UTURN_L; sel = DIR_STRAIGHT; break;
                case 58: all = DIR_STRAIGHT | DIR_RIGHT | DIR_UTURN_L; sel = DIR_RIGHT; break;
                case 59: all = DIR_STRAIGHT | DIR_RIGHT | DIR_UTURN_L; sel = DIR_UTURN_L; break;
                case 60: all = DIR_LEFT | DIR_UTURN_R;                 sel = DIR_LEFT; break;
                case 61: all = DIR_LEFT | DIR_UTURN_R;                 sel = DIR_UTURN_R; break;
                default: all = DIR_STRAIGHT; break;
            }
            if (laneType == 1 && all == 0) all = DIR_BUS;
            if (advised && sel == 0) {
                sel = all;
            }
            return (sel << 16) | (all & 0xFFFF);
        }

        @Override
        protected void onDraw(Canvas c) {
            int count = lanes.size();
            if (count <= 0) return;
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float padH = 8f;
            float maxCellW = 54f;
            float cellW = Math.min(maxCellW, (w - padH * 2f) / count);
            float totalW = cellW * count + padH * 2f;
            float startX = (w - totalW) * 0.5f;

            pillRect.set(startX, 1f, startX + totalW, h - 1f);
            c.drawRoundRect(pillRect, 14f, 14f, pillBgPaint);
            c.drawRoundRect(pillRect, 14f, 14f, pillBorderPaint);

            for (int i = 0; i < count; i++) {
                AMapBridge.LaneItem item = lanes.get(i);
                int packed = decodeLaneCode(item.iconCode, item.advised, item.laneType);
                int allDirs = packed & 0xFFFF;
                int selDirs = (packed >>> 16) & 0xFFFF;
                float cx0 = startX + padH + i * cellW;
                float cx1 = cx0 + cellW;

                if (selDirs != 0) {
                    cellRect.set(cx0 + 2.5f, 5f, cx1 - 2.5f, h - 5f);
                    c.drawRoundRect(cellRect, 9f, 9f, activeCellPaint);
                }

                if (i > 0) {
                    c.drawLine(cx0, 11f, cx0, h - 11f, dividerPaint);
                }

                drawLaneCell(c, cx0, 4f, cellW, h - 8f, allDirs, selDirs, item.laneType);
            }
        }

        private void drawLaneCell(Canvas c, float x, float y, float cw, float ch,
                                  int allDirs, int selDirs, int laneType) {
            if ((allDirs & DIR_BUS) != 0 || laneType == 1 && allDirs == 0) {
                badgeTextPaint.setColor(selDirs != 0 ? 0xFFFACC15 : 0xAA94A3B8);
                badgeTextPaint.setTextSize(Math.min(16f, cw * 0.34f));
                c.drawText("公交", x + cw * 0.5f, y + ch * 0.58f, badgeTextPaint);
                return;
            }
            if ((allDirs & DIR_VARIABLE) != 0) {
                badgeTextPaint.setColor(selDirs != 0 ? 0xFF38BDF8 : 0xAA94A3B8);
                badgeTextPaint.setTextSize(Math.min(16f, cw * 0.34f));
                c.drawText("可变", x + cw * 0.5f, y + ch * 0.58f, badgeTextPaint);
                return;
            }

            float s = Math.min(cw, ch);
            float ox = x + (cw - s) * 0.5f;
            float oy = y + (ch - s) * 0.5f;

            float strokeW = Math.max(3.2f, s * 0.095f);
            dimStrokePaint.setStrokeWidth(strokeW);
            hiStrokePaint.setStrokeWidth(strokeW * 1.06f);

            // Shift stem slightly if asymmetric single/U-turn so arrow stays balanced inside cell
            boolean hasL = (allDirs & DIR_LEFT) != 0;
            boolean hasR = (allDirs & DIR_RIGHT) != 0;
            boolean hasUL = (allDirs & DIR_UTURN_L) != 0;
            boolean hasUR = (allDirs & DIR_UTURN_R) != 0;
            float stemX = ox + s * 0.50f;
            if ((hasL || hasUL) && !hasR && !hasUR) stemX = ox + s * 0.60f;
            else if ((hasR || hasUR) && !hasL && !hasUL) stemX = ox + s * 0.40f;

            // Pass 1: draw non-selected branches dimmed
            int dimDirs = allDirs & ~selDirs;
            if (dimDirs != 0) {
                drawDirBranches(c, ox, oy, s, stemX, dimDirs, dimStrokePaint, dimFillPaint);
            }
            // Pass 2: draw selected/recommended branches bright on top
            if (selDirs != 0) {
                drawDirBranches(c, ox, oy, s, stemX, selDirs, hiStrokePaint, hiFillPaint);
            }
        }

        private void drawDirBranches(Canvas c, float ox, float oy, float s, float stemX,
                                     int dirs, Paint sp, Paint fp) {
            float botY = oy + s * 0.86f;
            float ah = s * 0.14f;

            if ((dirs & DIR_STRAIGHT) != 0) {
                float tipY = oy + s * 0.14f;
                c.drawLine(stemX, botY, stemX, tipY + ah * 0.7f, sp);
                drawArrowHead(c, stemX, tipY, 0f, -1f, ah, fp);
            }
            if ((dirs & DIR_LEFT) != 0) {
                float turnY = oy + s * 0.42f;
                float tipX = ox + s * 0.14f;
                float rad = s * 0.12f;
                path.reset();
                path.moveTo(stemX, botY);
                path.lineTo(stemX, turnY + rad);
                path.quadTo(stemX, turnY, stemX - rad, turnY);
                path.lineTo(tipX + ah * 0.7f, turnY);
                c.drawPath(path, sp);
                drawArrowHead(c, tipX, turnY, -1f, 0f, ah, fp);
            }
            if ((dirs & DIR_RIGHT) != 0) {
                float turnY = oy + s * 0.42f;
                float tipX = ox + s * 0.86f;
                float rad = s * 0.12f;
                path.reset();
                path.moveTo(stemX, botY);
                path.lineTo(stemX, turnY + rad);
                path.quadTo(stemX, turnY, stemX + rad, turnY);
                path.lineTo(tipX - ah * 0.7f, turnY);
                c.drawPath(path, sp);
                drawArrowHead(c, tipX, turnY, 1f, 0f, ah, fp);
            }
            if ((dirs & DIR_UTURN_L) != 0) {
                float arcTop = oy + s * 0.50f;
                float leftX = Math.max(ox + s * 0.20f, stemX - s * 0.34f);
                float tipY = oy + s * 0.84f;
                path.reset();
                path.moveTo(stemX, botY);
                path.lineTo(stemX, arcTop + s * 0.14f);
                RectF arc = new RectF(leftX, arcTop, stemX, arcTop + (stemX - leftX));
                path.arcTo(arc, 0f, -180f, false);
                path.lineTo(leftX, tipY - ah * 0.65f);
                c.drawPath(path, sp);
                drawArrowHead(c, leftX, tipY, 0f, 1f, ah * 0.92f, fp);
            }
            if ((dirs & DIR_UTURN_R) != 0) {
                float arcTop = oy + s * 0.50f;
                float rightX = Math.min(ox + s * 0.80f, stemX + s * 0.34f);
                float tipY = oy + s * 0.84f;
                path.reset();
                path.moveTo(stemX, botY);
                path.lineTo(stemX, arcTop + s * 0.14f);
                RectF arc = new RectF(stemX, arcTop, rightX, arcTop + (rightX - stemX));
                path.arcTo(arc, 180f, 180f, false);
                path.lineTo(rightX, tipY - ah * 0.65f);
                c.drawPath(path, sp);
                drawArrowHead(c, rightX, tipY, 0f, 1f, ah * 0.92f, fp);
            }
        }

        private void drawArrowHead(Canvas c, float tipX, float tipY,
                                   float dx, float dy, float size, Paint fp) {
            float px = -dy;
            float py = dx;
            float baseX = tipX - dx * size * 1.15f;
            float baseY = tipY - dy * size * 1.15f;
            float halfW = size * 0.88f;
            path.reset();
            path.moveTo(tipX, tipY);
            path.lineTo(baseX + px * halfW, baseY + py * halfW);
            path.lineTo(baseX - px * halfW, baseY - py * halfW);
            path.close();
            c.drawPath(path, fp);
        }
    }

    static final class ProgressStripView extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint segPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF barRect = new RectF();
        private final List<AMapBridge.TmcSegment> tmcSegments = new ArrayList<AMapBridge.TmcSegment>();
        private boolean hasTmc = false;
        private int tmcTotalDis = 0;
        private int tmcFinishDis = 0;
        private float progress = 0f;

        ProgressStripView(Context ctx, int fgColor, int bgColor) {
            super(ctx);
            fgPaint.setColor(fgColor);
            bgPaint.setColor(bgColor);
            thumbOuterPaint.setColor(0xFF0B1924);
            thumbOuterPaint.setStyle(Paint.Style.FILL);
            thumbInnerPaint.setColor(0xFFFFFFFF);
            thumbInnerPaint.setStyle(Paint.Style.FILL);
        }

        void setProgress(float p) {
            if (p < 0f) p = 0f;
            if (p > 1f) p = 1f;
            this.progress = p;
            invalidate();
        }

        void setTmcSegments(List<AMapBridge.TmcSegment> segs, int totalDis, int finishDis, float fallbackProgress) {
            tmcSegments.clear();
            if (segs != null) tmcSegments.addAll(segs);
            this.hasTmc = !tmcSegments.isEmpty();
            this.tmcTotalDis = Math.max(0, totalDis);
            this.tmcFinishDis = Math.max(0, finishDis);
            if (fallbackProgress < 0f) fallbackProgress = 0f;
            if (fallbackProgress > 1f) fallbackProgress = 1f;
            this.progress = fallbackProgress;
            invalidate();
        }

        void clearTmc() {
            if (hasTmc) {
                tmcSegments.clear();
                hasTmc = false;
                invalidate();
            }
        }

        private static int tmcColor(int status) {
            switch (status) {
                case 10: return 0xFF475569; // Driven / passed
                case 1:
                case 5:  return 0xFF22C55E; // Smooth (green)
                case 2:  return 0xFFF59E0B; // Slow (amber)
                case 3:  return 0xFFEF4444; // Congested (red)
                case 4:  return 0xFFB91C1C; // Severe jam (dark red)
                default: return 0xFF38BDF8; // Unknown (sky blue)
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float barH = Math.min(10f, h);
            float top = (h - barH) * 0.5f;
            float bot = top + barH;
            float r = barH * 0.5f;
            barRect.set(0f, top, w, bot);

            if (!hasTmc) {
                c.drawRoundRect(barRect, r, r, bgPaint);
                float fw = Math.max(progress > 0f ? barH : 0f, w * progress);
                if (fw > 0f) {
                    c.drawRoundRect(0f, top, Math.min(w, fw), bot, r, r, fgPaint);
                }
                return;
            }

            // Compute total weight (prefer exact distances in meters; fall back to percent)
            float totalWeight = 0f;
            boolean useDist = false;
            for (AMapBridge.TmcSegment seg : tmcSegments) {
                if (seg.distance > 0) useDist = true;
                totalWeight += seg.distance;
            }
            if (!useDist || totalWeight <= 0f) {
                totalWeight = 0f;
                for (AMapBridge.TmcSegment seg : tmcSegments) {
                    totalWeight += seg.percent;
                }
            }
            if (totalWeight <= 0f) {
                c.drawRoundRect(barRect, r, r, bgPaint);
                return;
            }

            // Compute pixel widths, ensuring short congestion segments (status 2/3/4) stay visible (>=4px)
            int n = tmcSegments.size();
            float[] widths = new float[n];
            float sumW = 0f;
            for (int i = 0; i < n; i++) {
                AMapBridge.TmcSegment seg = tmcSegments.get(i);
                float rawVal = useDist ? seg.distance : seg.percent;
                if (rawVal <= 0f) {
                    widths[i] = 0f;
                    continue;
                }
                float pw = (rawVal / totalWeight) * w;
                if ((seg.status == 2 || seg.status == 3 || seg.status == 4) && pw < 4.5f) {
                    pw = 4.5f;
                }
                widths[i] = pw;
                sumW += pw;
            }
            float scale = sumW > 0f ? (w / sumW) : 1f;

            c.save();
            clipPath.reset();
            clipPath.addRoundRect(barRect, r, r, Path.Direction.CW);
            c.clipPath(clipPath);
            c.drawRect(barRect, bgPaint);

            float curX = 0f;
            float drivenEndX = -1f;
            for (int i = 0; i < n; i++) {
                float sw = widths[i] * scale;
                if (sw <= 0.05f) continue;
                AMapBridge.TmcSegment seg = tmcSegments.get(i);
                segPaint.setColor(tmcColor(seg.status));
                float right = (i == n - 1) ? w : (curX + sw);
                c.drawRect(curX, top, right + 0.5f, bot, segPaint);
                if (seg.status == 10) {
                    drivenEndX = right;
                }
                curX = right;
            }
            c.restore();

            // Draw current car position marker dot along the TMC strip
            float thumbX;
            if (drivenEndX >= 0f) {
                thumbX = drivenEndX;
            } else if (tmcTotalDis > 0 && tmcFinishDis >= 0) {
                thumbX = w * Math.max(0f, Math.min(1f, (float) tmcFinishDis / (float) tmcTotalDis));
            } else {
                thumbX = w * progress;
            }
            float thumbR = Math.min(h * 0.48f, 7.2f);
            thumbX = Math.max(thumbR, Math.min(w - thumbR, thumbX));
            float cy = h * 0.5f;
            c.drawCircle(thumbX, cy, thumbR, thumbOuterPaint);
            c.drawCircle(thumbX, cy, thumbR - 2.0f, thumbInnerPaint);
        }
    }
}
