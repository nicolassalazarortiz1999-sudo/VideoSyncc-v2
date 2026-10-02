package com.videosync.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Constantes, registro de eventos (para diagnosticar) y utilidades compartidas. */
final class Net {
    static final String NET_NAME = "DIRECT-VS-Maestro";
    static final String PASS = "videosync2024";
    static final int CTRL_PORT = 8888;
    static final int DATA_PORT = 8889;
    static final int MAX_SLAVES = 8;
    static final String GO_IP = "192.168.49.1";
    static final int CHUNK = 256 * 1024;
    static final long LEAD_MS = 3500;

    private static final ArrayList<String> LOG = new ArrayList<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Runnable logListener;

    private Net() {}

    static synchronized void log(String s) {
        LOG.add(FMT.format(new Date()) + "  " + s);
        while (LOG.size() > 300) LOG.remove(0);
        Runnable l = logListener;
        if (l != null) MAIN.post(l);
    }

    static synchronized String logText() {
        StringBuilder sb = new StringBuilder();
        for (String s : LOG) sb.append(s).append('\n');
        return sb.toString();
    }

    static void setLogListener(Runnable r) {
        logListener = r;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("vs", Context.MODE_PRIVATE);
    }

    static long nowNs() {
        return SystemClock.elapsedRealtimeNanos();
    }

    static String reason(int r) {
        switch (r) {
            case 0: return "ERROR interno";
            case 1: return "Wi-Fi Direct no soportado";
            case 2: return "OCUPADO";
            case 3: return "sin solicitudes de servicio";
            default: return "código " + r;
        }
    }

    /** En Android 12 o menor, Wi-Fi Direct necesita la ubicación (GPS) encendida. */
    static boolean locationOn(Context c) {
        if (Build.VERSION.SDK_INT >= 33) return true;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        return lm == null || lm.isLocationEnabled();
    }
}
