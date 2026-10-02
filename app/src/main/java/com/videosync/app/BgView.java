package com.videosync.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/** Fondo "aurora": degradado, tres luces que flotan y partículas que suben. */
class BgView extends View {
    private static final int NP = 26;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RadialGradient[] blobs = new RadialGradient[3];
    private final float[] px = new float[NP];
    private final float[] py = new float[NP];
    private final float[] pr = new float[NP];
    private final int[] rate = new int[NP];
    private final float density;
    private LinearGradient base;
    private float radius;
    private float phase;
    private ValueAnimator anim;

    BgView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        dot.setColor(0xFFFFFFFF);
        Random r = new Random(7);
        for (int i = 0; i < NP; i++) {
            px[i] = r.nextFloat();
            py[i] = r.nextFloat();
            pr[i] = 1.2f + r.nextFloat() * 2.8f;
            rate[i] = 1 + r.nextInt(3);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        base = new LinearGradient(0, 0, w, h,
                new int[]{Ui.BG1, Ui.BG2, Ui.BG3}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
        radius = Math.max(w, h) * 0.38f;
        int[] cols = {Ui.ACCENT, Ui.MINT, Ui.PINK};
        for (int i = 0; i < 3; i++) {
            int c = cols[i];
            int on = (c & 0x00FFFFFF) | 0x50000000;
            int off = c & 0x00FFFFFF;
            blobs[i] = new RadialGradient(0, 0, radius, new int[]{on, off}, null, Shader.TileMode.CLAMP);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        anim.setDuration(26000);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
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
        if (base == null) return;
        int w = getWidth();
        int h = getHeight();
        p.setShader(base);
        canvas.drawRect(0, 0, w, h, p);
        for (int i = 0; i < 3; i++) {
            float cx = w * (0.5f + 0.42f * (float) Math.sin(phase + i * 2.1f));
            float cy = h * (0.5f + 0.38f * (float) Math.cos(phase + i * 1.7f));
            canvas.save();
            canvas.translate(cx, cy);
            p.setShader(blobs[i]);
            canvas.drawCircle(0, 0, radius, p);
            canvas.restore();
        }
        p.setShader(null);
        float turn = phase / (float) (Math.PI * 2);
        for (int i = 0; i < NP; i++) {
            float yy = (py[i] + turn * rate[i]) % 1f;
            float x = w * (px[i] + 0.02f * (float) Math.sin(phase * 3 + i));
            float y = h * (1f - yy);
            dot.setAlpha((int) (110 * Math.sin(Math.PI * yy)));
            canvas.drawCircle(x, y, pr[i] * density, dot);
        }
    }
}
