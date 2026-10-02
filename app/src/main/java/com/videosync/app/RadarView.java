package com.videosync.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Ondas concéntricas que se expanden, con un núcleo que late. */
class RadarView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float t;
    private int color = Ui.MINT;
    private ValueAnimator anim;

    RadarView(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 2));
        dot.setColor(0xFFFFFFFF);
    }

    void setColor(int c) {
        color = c;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(2800);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
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
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float maxR = Math.min(getWidth(), getHeight()) / 2f;
        float coreR = maxR * 0.22f * (1f + 0.07f * (float) Math.sin(t * 2 * Math.PI));
        ring.setColor(color);
        for (int k = 0; k < 3; k++) {
            float ph = (t + k / 3f) % 1f;
            float r = coreR + ph * (maxR - coreR - ring.getStrokeWidth());
            ring.setAlpha((int) (200 * (1f - ph)));
            canvas.drawCircle(cx, cy, r, ring);
        }
        core.setColor(color);
        core.setAlpha(70);
        canvas.drawCircle(cx, cy, coreR * 1.45f, core);
        core.setAlpha(255);
        canvas.drawCircle(cx, cy, coreR, core);
        canvas.drawCircle(cx, cy, coreR * 0.36f, dot);
    }
}
