package com.videosync.app;

import android.os.Handler;
import android.widget.TextView;

/**
 * Cuenta regresiva y arranque exacto: espera hasta un instante (en el reloj local) mostrando 3-2-1
 * y dispara la acción justo en ese momento (los últimos milisegundos con espera activa).
 */
final class SyncStart implements Runnable {
    private final Handler ui;
    private final long target;
    private final TextView label;
    private final Runnable go;
    private int lastSec = -1;
    private volatile boolean cancelled;

    SyncStart(Handler ui, long targetLocalNs, TextView label, Runnable go) {
        this.ui = ui;
        this.target = targetLocalNs;
        this.label = label;
        this.go = go;
    }

    void begin() {
        ui.post(this);
    }

    void cancel() {
        cancelled = true;
        ui.removeCallbacks(this);
        label.setText("");
    }

    @Override
    public void run() {
        if (cancelled) return;
        long remNs = target - Net.nowNs();
        if (remNs <= 0) {
            fire();
            return;
        }
        long remMs = remNs / 1_000_000L;
        int sec = (int) ((remMs + 999) / 1000);
        if (sec <= 3 && sec != lastSec) {
            lastSec = sec;
            Ui.pop(label, String.valueOf(sec));
        }
        if (remMs <= 12) {
            while (Net.nowNs() < target) {
                // espera activa de unos pocos milisegundos para arrancar con precisión
            }
            fire();
            return;
        }
        long d = remMs > 40 ? 20 : remMs - 12;
        ui.postDelayed(this, Math.max(1, d));
    }

    private void fire() {
        if (cancelled) return;
        label.setText("");
        go.run();
    }
}
