package com.videosync.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Barra de progreso redondeada con degradado. */
class BarView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float progress;

    BarView(Context c) {
        super(c);
        track.setColor(0x26FFFFFF);
    }

    void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        bar.setShader(new LinearGradient(0, 0, w, 0, Ui.ACCENT, Ui.MINT, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = getHeight();
        float r = h / 2f;
        rect.set(0, 0, getWidth(), h);
        canvas.drawRoundRect(rect, r, r, track);
        if (progress > 0f) {
            rect.set(0, 0, Math.max(h, getWidth() * progress), h);
            canvas.drawRoundRect(rect, r, r, bar);
        }
    }
}
