package com.videosync.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

final class Ui {
    static final int BG1 = 0xFF0B1026;
    static final int BG2 = 0xFF2B1055;
    static final int BG3 = 0xFF0F3A5F;
    static final int ACCENT = 0xFF7C5CFF;
    static final int BLUE = 0xFF4F8CFF;
    static final int MINT = 0xFF00E5C3;
    static final int PINK = 0xFFFF4FA3;
    static final int GREEN = 0xFF3DDC84;
    static final int RED = 0xFFFF5252;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xB3FFFFFF;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif-light", Typeface.NORMAL));
        return t;
    }

    static GradientDrawable card(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x1AFFFFFF);
        g.setCornerRadius(dp(c, 24));
        g.setStroke(dp(c, 1), 0x2AFFFFFF);
        return g;
    }

    static TextView button(Context c, String label, boolean primary) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextSize(16);
        b.setTextColor(TEXT);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(c, 24), dp(c, 16), dp(c, 24), dp(c, 16));
        b.setClickable(true);
        b.setFocusable(true);
        GradientDrawable g;
        if (primary) {
            g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT, BLUE});
        } else {
            g = new GradientDrawable();
            g.setColor(0x22FFFFFF);
            g.setStroke(dp(c, 1), 0x44FFFFFF);
        }
        g.setCornerRadius(dp(c, 28));
        b.setBackground(g);
        b.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.96f).scaleY(0.96f).setStartDelay(0).setDuration(90).start();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(120).start();
            }
            return false;
        });
        return b;
    }

    static TextView chip(Context c, String label) {
        TextView t = text(c, label, 12, TEXT, true);
        t.setPadding(dp(c, 14), dp(c, 7), dp(c, 14), dp(c, 7));
        t.setClickable(true);
        t.setGravity(Gravity.CENTER);
        chipLook(t, false);
        return t;
    }

    static void chipLook(TextView t, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(t.getContext(), 18));
        g.setColor(selected ? ACCENT : 0x22FFFFFF);
        g.setStroke(dp(t.getContext(), 1), selected ? 0x00000000 : 0x33FFFFFF);
        t.setBackground(g);
    }

    static void setEnabledLook(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.4f);
    }

    static void enter(View v, int index) {
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 24));
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(120L * index).setDuration(520)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /** Muestra un texto con efecto de "rebote" (usado en la cuenta regresiva). */
    static void pop(TextView t, String s) {
        t.setText(s);
        t.setAlpha(0f);
        t.setScaleX(1.8f);
        t.setScaleY(1.8f);
        t.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(380)
                .setInterpolator(new DecelerateInterpolator()).start();
    }
}
