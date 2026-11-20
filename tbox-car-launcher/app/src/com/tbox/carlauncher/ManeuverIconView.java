package com.tbox.carlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Custom crisp vector renderer for AMapAuto maneuver ICON codes.
 */
public class ManeuverIconView extends View {
    private int iconType = 9; // Default straight
    private final Paint mainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public ManeuverIconView(Context context) {
        super(context);
        mainPaint.setStyle(Paint.Style.STROKE);
        mainPaint.setColor(0xFFFFFFFF);
        mainPaint.setStrokeCap(Paint.Cap.ROUND);
        mainPaint.setStrokeJoin(Paint.Join.ROUND);

        dimPaint.setStyle(Paint.Style.STROKE);
        dimPaint.setColor(0x4D94A3B8);
        dimPaint.setStrokeCap(Paint.Cap.ROUND);
        dimPaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0xFFFFFFFF);
    }

    public void setIconType(int type) {
        if (this.iconType != type) {
            this.iconType = type;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w, h);
        float ox = (w - s) * 0.5f;
        float oy = (h - s) * 0.5f;

        canvas.save();
        canvas.translate(ox, oy);

        float stroke = s * 0.115f;
        mainPaint.setStrokeWidth(stroke);
        dimPaint.setStrokeWidth(stroke * 0.85f);

        switch (iconType) {
            case 2: // Left turn
                drawTurn(canvas, s, true, false);
                break;
            case 3: // Right turn
                drawTurn(canvas, s, false, false);
                break;
            case 4: // Slight left / front-left
            case 17: // Keep left
                drawSlightTurn(canvas, s, true);
                break;
            case 5: // Slight right / front-right
            case 18: // Keep right
                drawSlightTurn(canvas, s, false);
                break;
            case 6: // Sharp left
                drawSharpTurn(canvas, s, true);
                break;
            case 7: // Sharp right
                drawSharpTurn(canvas, s, false);
                break;
            case 8: // U-turn left
                drawUTurn(canvas, s, true);
                break;
            case 19: // U-turn right
                drawUTurn(canvas, s, false);
                break;
            case 11: // Enter roundabout
            case 12: // Exit roundabout
                drawRoundabout(canvas, s);
                break;
            case 15: // Destination
            case 10: // Waypoint
                drawDestination(canvas, s);
                break;
            case 13: // Service area
            case 14: // Toll gate
            case 16: // Tunnel
                drawStraight(canvas, s, true);
                break;
            case 9: // Straight
            case 20:
            default:
                drawStraight(canvas, s, false);
                break;
        }
        canvas.restore();
    }

    private void drawArrowHead(Canvas c, float tipX, float tipY, float angleDeg, float size) {
        c.save();
        c.translate(tipX, tipY);
        c.rotate(angleDeg);
        path.reset();
        path.moveTo(0, 0);
        path.lineTo(-size * 0.72f, size * 0.92f);
        path.lineTo(0, size * 0.65f);
        path.lineTo(size * 0.72f, size * 0.92f);
        path.close();
        c.drawPath(path, fillPaint);
        c.restore();
    }

    private void drawStraight(Canvas c, float s, boolean withArch) {
        if (withArch) {
            RectF arch = new RectF(s * 0.18f, s * 0.16f, s * 0.82f, s * 0.84f);
            c.drawArc(arch, 180, 180, false, dimPaint);
        }
        c.drawLine(s * 0.50f, s * 0.84f, s * 0.50f, s * 0.28f, mainPaint);
        drawArrowHead(c, s * 0.50f, s * 0.12f, 0f, s * 0.28f);
    }

    private void drawTurn(Canvas c, float s, boolean left, boolean sharp) {
        float dir = left ? -1f : 1f;
        float stemX = s * (left ? 0.64f : 0.36f);
        // Subtle ghost straight stem
        c.drawLine(stemX, s * 0.42f, stemX, s * 0.18f, dimPaint);

        path.reset();
        path.moveTo(stemX, s * 0.85f);
        path.lineTo(stemX, s * 0.46f);
        path.quadTo(stemX, s * 0.34f, stemX + dir * s * 0.12f, s * 0.34f);
        path.lineTo(stemX + dir * s * 0.32f, s * 0.34f);
        c.drawPath(path, mainPaint);

        float tipX = stemX + dir * s * 0.46f;
        drawArrowHead(c, tipX, s * 0.34f, left ? -90f : 90f, s * 0.27f);
    }

    private void drawSlightTurn(Canvas c, float s, boolean left) {
        float dir = left ? -1f : 1f;
        float stemX = s * (left ? 0.58f : 0.42f);
        c.drawLine(stemX, s * 0.54f, stemX, s * 0.18f, dimPaint);

        path.reset();
        path.moveTo(stemX, s * 0.85f);
        path.lineTo(stemX, s * 0.54f);
        path.lineTo(stemX + dir * s * 0.24f, s * 0.27f);
        c.drawPath(path, mainPaint);

        drawArrowHead(c, stemX + dir * s * 0.33f, s * 0.17f, left ? -42f : 42f, s * 0.26f);
    }

    private void drawSharpTurn(Canvas c, float s, boolean left) {
        float dir = left ? -1f : 1f;
        float stemX = s * (left ? 0.66f : 0.34f);
        path.reset();
        path.moveTo(stemX, s * 0.85f);
        path.lineTo(stemX, s * 0.34f);
        path.quadTo(stemX, s * 0.22f, stemX + dir * s * 0.12f, s * 0.28f);
        path.lineTo(stemX + dir * s * 0.34f, s * 0.54f);
        c.drawPath(path, mainPaint);

        drawArrowHead(c, stemX + dir * s * 0.44f, s * 0.65f, left ? -135f : 135f, s * 0.26f);
    }

    private void drawUTurn(Canvas c, float s, boolean left) {
        float x1 = s * (left ? 0.68f : 0.32f);
        float x2 = s * (left ? 0.32f : 0.68f);
        float r = Math.abs(x1 - x2) * 0.5f;
        path.reset();
        path.moveTo(x1, s * 0.84f);
        path.lineTo(x1, s * 0.36f);
        RectF arc = new RectF(Math.min(x1, x2), s * 0.36f - r, Math.max(x1, x2), s * 0.36f + r);
        path.arcTo(arc, left ? 0f : 180f, left ? -180f : 180f);
        path.lineTo(x2, s * 0.62f);
        c.drawPath(path, mainPaint);

        drawArrowHead(c, x2, s * 0.78f, 180f, s * 0.26f);
    }

    private void drawRoundabout(Canvas c, float s) {
        float cx = s * 0.50f;
        float cy = s * 0.50f;
        float r = s * 0.20f;
        c.drawCircle(cx, cy, r, dimPaint);
        RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(oval, 90f, -180f, false, mainPaint);
        c.drawLine(cx, s * 0.86f, cx, cy + r, mainPaint);
        c.drawLine(cx, cy - r, cx, s * 0.20f, mainPaint);
        drawArrowHead(c, cx, s * 0.11f, 0f, s * 0.24f);
    }

    private void drawDestination(Canvas c, float s) {
        float cx = s * 0.50f;
        float cy = s * 0.40f;
        float r = s * 0.22f;
        path.reset();
        path.moveTo(cx, s * 0.86f);
        path.cubicTo(cx - r * 1.4f, s * 0.60f, cx - r * 1.3f, cy - r * 0.5f, cx, cy - r);
        path.cubicTo(cx + r * 1.3f, cy - r * 0.5f, cx + r * 1.4f, s * 0.60f, cx, s * 0.86f);
        path.close();
        c.drawPath(path, fillPaint);
        Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
        hole.setColor(0xFF0E2833);
        c.drawCircle(cx, cy, r * 0.42f, hole);
    }

    public static String iconLabel(int type) {
        switch (type) {
            case 2: return "左转";
            case 3: return "右转";
            case 4: return "向左前方行驶";
            case 5: return "向右前方行驶";
            case 6: return "向左后方转弯";
            case 7: return "向右后方转弯";
            case 8: return "左转掉头";
            case 9: return "直行";
            case 10: return "到达途经点";
            case 11: return "进入环岛";
            case 12: return "驶出环岛";
            case 13: return "前方服务区";
            case 14: return "前方收费站";
            case 15: return "到达目的地";
            case 16: return "进入隧道";
            case 17: return "靠左行驶";
            case 18: return "靠右行驶";
            case 19: return "右转掉头";
            default: return "前方行驶";
        }
    }
}
