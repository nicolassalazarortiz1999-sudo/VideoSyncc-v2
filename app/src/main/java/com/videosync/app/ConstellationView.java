package com.videosync.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Constelación: el maestro al centro y 8 nodos en órbita. Cada nodo se enciende cuando
 * se une un esclavo, muestra un anillo de progreso al recibir y un check cuando está listo.
 */
class ConstellationView extends View {
    static final int EMPTY = 0;
    static final int CONNECTED = 1;
    static final int RECEIVING = 2;
    static final int READY = 3;
    static final int PLAYING = 4;
    private static final int N = 8;

    private final int[] state = new int[N];
    private final float[] prog = new float[N];
    private final float[] pop = new float[N];
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path path = new Path();
    private final float density;
    private float t;
    private ValueAnimator anim;

    ConstellationView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * density);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2 * density);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(3.5f * density);
        arc.setStrokeCap(Paint.Cap.ROUND);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    void setNode(int i, int st, float p) {
        if (i < 0 || i >= N) return;
        state[i] = st;
        prog[i] = p;
    }

    private static int colorFor(int st) {
        switch (st) {
            case RECEIVING: return Ui.ACCENT;
            case READY: return Ui.GREEN;
            case PLAYING: return Ui.PINK;
            default: return Ui.MINT;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(3200);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            for (int i = 0; i < N; i++) {
                float target = state[i] != EMPTY ? 1f : 0f;
                pop[i] += (target - pop[i]) * 0.2f;
            }
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (int) (w * 0.92f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float m = Math.min(w, h);
        float orbit = m * 0.36f;
        float rn = m * 0.075f;
        float rc = m * 0.10f;

        stroke.setColor(0x14FFFFFF);
        canvas.drawCircle(cx, cy, orbit, stroke);

        txt.setTextSize(rn * 0.95f);
        Paint.FontMetrics fm = txt.getFontMetrics();

        for (int i = 0; i < N; i++) {
            double ang = Math.toRadians(-90 + i * 45);
            float nx = cx + orbit * (float) Math.cos(ang);
            float ny = cy + orbit * (float) Math.sin(ang);
            float pp = Math.max(0f, Math.min(1f, pop[i]));
            int st = state[i];
            int col = colorFor(st);

            if (pp > 0.02f) {
                line.setColor(col);
                line.setAlpha((int) (120 * pp));
                canvas.drawLine(cx, cy, nx, ny, line);
                if (st == RECEIVING || st == PLAYING) {
                    fill.setColor(col);
                    fill.setAlpha(240);
                    for (int k = 0; k < 3; k++) {
                        float f = (t + k / 3f) % 1f;
                        canvas.drawCircle(cx + (nx - cx) * f, cy + (ny - cy) * f, 3.2f * density, fill);
                    }
                }
            }

            stroke.setColor(0x33FFFFFF);
            canvas.drawCircle(nx, ny, rn, stroke);

            if (pp > 0.02f) {
                fill.setColor(col);
                fill.setAlpha(255);
                canvas.drawCircle(nx, ny, rn * pp, fill);
                if (st == RECEIVING) {
                    oval.set(nx - rn * 1.4f, ny - rn * 1.4f, nx + rn * 1.4f, ny + rn * 1.4f);
                    arc.setColor(0x33FFFFFF);
                    canvas.drawArc(oval, 0, 360, false, arc);
                    arc.setColor(Ui.MINT);
                    canvas.drawArc(oval, -90, 360f * prog[i], false, arc);
                }
            }

            if (st == READY && pp > 0.5f) {
                path.reset();
                path.moveTo(nx - rn * 0.38f, ny + rn * 0.02f);
                path.lineTo(nx - rn * 0.1f, ny + rn * 0.32f);
                path.lineTo(nx + rn * 0.42f, ny - rn * 0.3f);
                stroke.setColor(Ui.BG1);
                stroke.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawPath(path, stroke);
                stroke.setStrokeCap(Paint.Cap.BUTT);
            } else {
                txt.setColor(pp > 0.5f ? (st == RECEIVING || st == PLAYING ? Ui.TEXT : Ui.BG1) : Ui.MUTED);
                canvas.drawText(String.valueOf(i + 1), nx, ny - (fm.ascent + fm.descent) / 2f, txt);
            }
        }

        float pulse = 1f + 0.06f * (float) Math.sin(t * 2 * Math.PI);
        fill.setColor(Ui.ACCENT);
        fill.setAlpha(60);
        canvas.drawCircle(cx, cy, rc * 1.6f * pulse, fill);
        fill.setAlpha(255);
        canvas.drawCircle(cx, cy, rc * pulse, fill);
        path.reset();
        path.moveTo(cx - rc * 0.28f, cy - rc * 0.42f);
        path.lineTo(cx - rc * 0.28f, cy + rc * 0.42f);
        path.lineTo(cx + rc * 0.46f, cy);
        path.close();
        fill.setColor(Ui.TEXT);
        canvas.drawPath(path, fill);
    }
}
