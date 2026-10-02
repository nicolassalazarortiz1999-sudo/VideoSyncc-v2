package com.videosync.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG1);
        getWindow().setNavigationBarColor(Ui.BG3);

        FrameLayout root = new FrameLayout(this);
        root.addView(new BgView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 28);
        col.setPadding(pad, Ui.dp(this, 44), pad, Ui.dp(this, 28));

        RadarView radar = new RadarView(this);
        col.addView(radar, new LinearLayout.LayoutParams(Ui.dp(this, 200), Ui.dp(this, 200)));

        TextView title = Ui.text(this, "VideoSync", 40, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        col.addView(title, wrap(Ui.dp(this, 14)));

        TextView sub = Ui.text(this, "Un video. Ocho pantallas.\nEl mismo instante.", 16, Ui.MUTED, false);
        sub.setGravity(Gravity.CENTER);
        col.addView(sub, wrap(Ui.dp(this, 8)));

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        TextView master = Ui.button(this, "SOY EL MAESTRO", true);
        master.setOnClickListener(v -> {
            Net.prefs(this).edit().remove("role").apply();
            startActivity(new Intent(this, MasterActivity.class));
        });
        col.addView(master, match(0));
        TextView mHint = Ui.text(this, "Elige el video y lo envía a todos", 12, Ui.MUTED, false);
        mHint.setGravity(Gravity.CENTER);
        col.addView(mHint, wrap(Ui.dp(this, 6)));

        TextView slave = Ui.button(this, "SOY UN ESCLAVO", false);
        slave.setOnClickListener(v -> {
            Net.prefs(this).edit().putString("role", "slave").apply();
            startActivity(new Intent(this, SlaveActivity.class));
        });
        col.addView(slave, match(Ui.dp(this, 18)));
        TextView sHint = Ui.text(this,
                "Se une solo y reproduce. Desde la próxima vez abre directo en este modo", 12, Ui.MUTED, false);
        sHint.setGravity(Gravity.CENTER);
        col.addView(sHint, wrap(Ui.dp(this, 6)));

        TextView ver = Ui.text(this, "v2 · Wi-Fi Direct", 11, 0x66FFFFFF, false);
        ver.setGravity(Gravity.CENTER);
        col.addView(ver, wrap(Ui.dp(this, 18)));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        Ui.enter(radar, 0);
        Ui.enter(title, 1);
        Ui.enter(sub, 2);
        Ui.enter(master, 3);
        Ui.enter(mHint, 3);
        Ui.enter(slave, 4);
        Ui.enter(sHint, 4);

        if ("slave".equals(Net.prefs(this).getString("role", ""))) {
            startActivity(new Intent(this, SlaveActivity.class));
        }
    }

    private LinearLayout.LayoutParams wrap(int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = top;
        return l;
    }

    private LinearLayout.LayoutParams match(int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = top;
        return l;
    }
}
