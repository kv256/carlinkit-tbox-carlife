package com.tbox.carlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Bottom-Right 512x345 CarPlay / Apple Music-style Media Card.
 * Dynamically tints its background from album art, displays track/artist/source badge,
 * smoothly interpolates playback progress, and offers large ergonomic transport controls.
 */
public class MusicWidgetView extends LinearLayout {
    public interface Callbacks {
        void onPrevious();
        void onPlayPause();
        void onNext();
        void onOpenMediaSource(String packageName);
    }

    private final Callbacks callbacks;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ImageView ivArt;
    private final VinylPlaceholderView vinylPlaceholder;
    private final TextView tvSource;
    private final View liveDot;
    private final TextView tvTitle;
    private final TextView tvArtist;
    private final NavWidgetView.ProgressStripView progressStrip;
    private final TextView tvElapsed;
    private final TextView tvDuration;
    private final TransportIconView playPauseIcon;

    private MediaHub.MediaState currentState;
    private Bitmap lastArtRef;
    private int currentBgTint = UiUtil.CARD_BG_TOP;

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            updateProgressUi();
            if (currentState != null && currentState.hasSession && currentState.playing) {
                handler.postDelayed(this, 1000L);
            }
        }
    };

    public MusicWidgetView(Context context, Callbacks cb) {
        super(context);
        this.callbacks = cb;
        setOrientation(VERTICAL);
        setPadding(24, 20, 24, 18);
        setBackground(UiUtil.glassCard(24f));
        setClipToOutline(true);

        // Top section: Album Art + Track Info (tapping opens media app)
        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callbacks != null && currentState != null) {
                    callbacks.onOpenMediaSource(currentState.packageName);
                }
            }
        });
        UiUtil.addPressScale(topRow);

        FrameLayout artBox = new FrameLayout(context);
        artBox.setBackground(UiUtil.cardDrawable(0xFF1D2636, 0xFF111827, 20f, 0, 0));
        vinylPlaceholder = new VinylPlaceholderView(context);
        artBox.addView(vinylPlaceholder, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        ivArt = new ImageView(context);
        ivArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivArt.setVisibility(GONE);
        artBox.addView(ivArt, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        topRow.addView(artBox, new LayoutParams(118, 118));

        LinearLayout infoCol = new LinearLayout(context);
        infoCol.setOrientation(VERTICAL);
        LayoutParams infoLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = 18;

        // Source pill row
        LinearLayout sourceRow = new LinearLayout(context);
        sourceRow.setOrientation(HORIZONTAL);
        sourceRow.setGravity(Gravity.CENTER_VERTICAL);
        sourceRow.setPadding(12, 5, 14, 5);
        sourceRow.setBackground(UiUtil.solidRounded(0x24FFFFFF, 14f, 0, 0));

        liveDot = new View(context);
        liveDot.setBackground(UiUtil.solidRounded(0xFF64748B, 6f, 0, 0));
        LayoutParams dotLp = new LayoutParams(10, 10);
        dotLp.rightMargin = 8;
        sourceRow.addView(liveDot, dotLp);

        tvSource = new TextView(context);
        tvSource.setText("蓝牙音乐");
        tvSource.setTextColor(0xFFE2E8F0);
        tvSource.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f);
        tvSource.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        sourceRow.addView(tvSource);

        infoCol.addView(sourceRow, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        tvTitle = new TextView(context);
        tvTitle.setText("等待播放音乐");
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        tvTitle.setMarqueeRepeatLimit(-1);
        tvTitle.setSelected(true);
        LayoutParams titleLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = 10;
        infoCol.addView(tvTitle, titleLp);

        tvArtist = new TextView(context);
        tvArtist.setText("手机蓝牙音频 / 本机音乐自动接入");
        tvArtist.setTextColor(0xFFB8C7DC);
        tvArtist.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f);
        tvArtist.setSingleLine(true);
        tvArtist.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams artLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        artLp.topMargin = 6;
        infoCol.addView(tvArtist, artLp);

        topRow.addView(infoCol, infoLp);
        addView(topRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Middle: Progress strip + timestamps
        progressStrip = new NavWidgetView.ProgressStripView(context, 0xFFF8FAFC, 0x2EFFFFFF);
        LayoutParams pLp = new LayoutParams(LayoutParams.MATCH_PARENT, 6);
        pLp.topMargin = 22;
        addView(progressStrip, pLp);

        LinearLayout timeRow = new LinearLayout(context);
        timeRow.setOrientation(HORIZONTAL);
        LayoutParams tLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        tLp.topMargin = 6;

        tvElapsed = new TextView(context);
        tvElapsed.setText("0:00");
        tvElapsed.setTextColor(0x99E2E8F0);
        tvElapsed.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f);
        timeRow.addView(tvElapsed, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        tvDuration = new TextView(context);
        tvDuration.setText("--:--");
        tvDuration.setTextColor(0x99E2E8F0);
        tvDuration.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f);
        timeRow.addView(tvDuration);

        addView(timeRow, tLp);

        // Bottom: Large ergonomic transport buttons
        LinearLayout ctrlRow = new LinearLayout(context);
        ctrlRow.setOrientation(HORIZONTAL);
        ctrlRow.setGravity(Gravity.CENTER);
        LayoutParams cLp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        cLp.topMargin = 10;

        View prevBtn = makeCtrlButton(context, new TransportIconView(context, TransportIconView.PREV, 0xFFF8FAFC),
            76, 68, 0x1AFFFFFF, 20f, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (callbacks != null) callbacks.onPrevious();
                }
            });
        ctrlRow.addView(prevBtn);

        playPauseIcon = new TransportIconView(context, TransportIconView.PLAY, 0xFF0F172A);
        View playBtn = makeCtrlButton(context, playPauseIcon,
            82, 82, 0xFFF8FAFC, 41f, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (callbacks != null) callbacks.onPlayPause();
                }
            });
        LayoutParams playLp = (LayoutParams) playBtn.getLayoutParams();
        playLp.leftMargin = 34;
        playLp.rightMargin = 34;
        ctrlRow.addView(playBtn);

        View nextBtn = makeCtrlButton(context, new TransportIconView(context, TransportIconView.NEXT, 0xFFF8FAFC),
            76, 68, 0x1AFFFFFF, 20f, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (callbacks != null) callbacks.onNext();
                }
            });
        ctrlRow.addView(nextBtn);

        addView(ctrlRow, cLp);
    }

    public void updateMediaState(MediaHub.MediaState s) {
        this.currentState = s;
        handler.removeCallbacks(progressTicker);
        if (s == null) return;

        tvSource.setText(TextUtils.isEmpty(s.sourceLabel) ? "蓝牙音乐" : s.sourceLabel);
        liveDot.setBackground(UiUtil.solidRounded(s.playing ? UiUtil.ACCENT_GREEN : 0xFF64748B, 6f, 0, 0));

        if (!TextUtils.isEmpty(s.title)) {
            tvTitle.setText(s.title);
            tvArtist.setText(TextUtils.isEmpty(s.artist) ? s.sourceLabel : s.artist);
        } else if (s.playing) {
            tvTitle.setText("正在播放");
            tvArtist.setText(s.sourceLabel);
        } else {
            tvTitle.setText("等待播放音乐");
            tvArtist.setText("连接手机蓝牙音频即可直接控制");
        }

        playPauseIcon.setKind(s.playing ? TransportIconView.PAUSE : TransportIconView.PLAY);

        if (s.albumArt != lastArtRef) {
            lastArtRef = s.albumArt;
            if (s.albumArt != null) {
                Bitmap rounded = UiUtil.roundBitmap(s.albumArt, 118, 20f);
                ivArt.setImageBitmap(rounded);
                ivArt.setVisibility(VISIBLE);
                vinylPlaceholder.setVisibility(GONE);
                currentBgTint = UiUtil.extractDarkTint(s.albumArt, UiUtil.CARD_BG_TOP);
                setBackground(UiUtil.cardDrawable(currentBgTint, 0xFF0F141D, 24f, UiUtil.CARD_BORDER, 1));
            } else {
                ivArt.setVisibility(GONE);
                vinylPlaceholder.setVisibility(VISIBLE);
                currentBgTint = UiUtil.CARD_BG_TOP;
                setBackground(UiUtil.glassCard(24f));
            }
        }

        updateProgressUi();
        if (s.hasSession && s.playing) {
            handler.postDelayed(progressTicker, 1000L);
        }
    }

    private void updateProgressUi() {
        if (currentState == null || !currentState.hasSession || currentState.durationMs <= 0L) {
            boolean activePlaying = currentState != null && currentState.hasSession && currentState.playing;
            progressStrip.setProgress(activePlaying ? 0.35f : 0f);
            tvElapsed.setText(activePlaying ? "正在播放" : "0:00");
            tvDuration.setText(activePlaying ? "LIVE" : "--:--");
            return;
        }
        long pos = currentState.estimatedPositionMs();
        long dur = currentState.durationMs;
        progressStrip.setProgress((float) pos / (float) dur);
        tvElapsed.setText(UiUtil.formatMs(pos));
        tvDuration.setText(UiUtil.formatMs(dur));
    }

    public void release() {
        handler.removeCallbacks(progressTicker);
    }

    private View makeCtrlButton(Context ctx, View icon, int w, int h, int bgColor, float radius, OnClickListener click) {
        FrameLayout box = new FrameLayout(ctx);
        box.setBackground(UiUtil.solidRounded(bgColor, radius, 0, 0));
        box.setLayoutParams(new LayoutParams(w, h));
        int pad = Math.min(w, h) / 4;
        box.setPadding(pad, pad, pad, pad);
        box.addView(icon, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        box.setOnClickListener(click);
        UiUtil.addPressScale(box);
        return box;
    }

    private static final class VinylPlaceholderView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path note = new Path();

        VinylPlaceholderView(Context ctx) {
            super(ctx);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float r = Math.min(w, h) * 0.36f;

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f);
            p.setColor(0x28FFFFFF);
            c.drawCircle(cx, cy, r, p);
            c.drawCircle(cx, cy, r * 0.72f, p);

            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF38BDF8);
            c.drawCircle(cx - r * 0.24f, cy + r * 0.28f, r * 0.20f, p);
            c.drawCircle(cx + r * 0.28f, cy + r * 0.20f, r * 0.20f, p);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.14f);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(cx - r * 0.08f, cy + r * 0.24f, cx - r * 0.08f, cy - r * 0.34f, p);
            c.drawLine(cx + r * 0.44f, cy + r * 0.16f, cx + r * 0.44f, cy - r * 0.42f, p);
            note.reset();
            note.moveTo(cx - r * 0.08f, cy - r * 0.34f);
            note.lineTo(cx + r * 0.44f, cy - r * 0.42f);
            c.drawPath(note, p);
        }
    }

    private static final class TransportIconView extends View {
        static final int PREV = 1;
        static final int PLAY = 2;
        static final int PAUSE = 3;
        static final int NEXT = 4;

        private int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        TransportIconView(Context ctx, int kind, int color) {
            super(ctx);
            this.kind = kind;
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
        }

        void setKind(int kind) {
            if (this.kind != kind) {
                this.kind = kind;
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
            if (kind == PLAY) {
                path.reset();
                path.moveTo(s * 0.26f, s * 0.14f);
                path.lineTo(s * 0.84f, s * 0.50f);
                path.lineTo(s * 0.26f, s * 0.86f);
                path.close();
                c.drawPath(path, paint);
            } else if (kind == PAUSE) {
                c.drawRoundRect(new RectF(s * 0.20f, s * 0.16f, s * 0.42f, s * 0.84f), s * 0.08f, s * 0.08f, paint);
                c.drawRoundRect(new RectF(s * 0.58f, s * 0.16f, s * 0.80f, s * 0.84f), s * 0.08f, s * 0.08f, paint);
            } else if (kind == PREV) {
                c.drawRoundRect(new RectF(s * 0.14f, s * 0.20f, s * 0.26f, s * 0.80f), s * 0.04f, s * 0.04f, paint);
                path.reset();
                path.moveTo(s * 0.84f, s * 0.20f);
                path.lineTo(s * 0.30f, s * 0.50f);
                path.lineTo(s * 0.84f, s * 0.80f);
                path.close();
                c.drawPath(path, paint);
            } else if (kind == NEXT) {
                c.drawRoundRect(new RectF(s * 0.74f, s * 0.20f, s * 0.86f, s * 0.80f), s * 0.04f, s * 0.04f, paint);
                path.reset();
                path.moveTo(s * 0.16f, s * 0.20f);
                path.lineTo(s * 0.70f, s * 0.50f);
                path.lineTo(s * 0.16f, s * 0.80f);
                path.close();
                c.drawPath(path, paint);
            }
            c.restore();
        }
    }
}
