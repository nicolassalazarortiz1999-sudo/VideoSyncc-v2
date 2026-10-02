package com.videosync.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Panel de registro: muestra lo que pasa en la red para poder diagnosticar errores. */
class LogPanel extends FrameLayout {
    private final TextView tv;
    private final ScrollView sv;

    LogPanel(Context c) {
        super(c);
        setBackgroundColor(0xF20B1026);
        setVisibility(View.GONE);
        setClickable(true);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 16);
        col.setPadding(p, p + Ui.dp(c, 18), p, p);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(c, "Registro", 18, Ui.TEXT, true);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copy = Ui.chip(c, "COPIAR");
        copy.setOnClickListener(v -> copyLog());
        row.addView(copy);

        TextView close = Ui.chip(c, "CERRAR");
        close.setOnClickListener(v -> closePanel());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = Ui.dp(c, 8);
        row.addView(close, cl);

        col.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sv = new ScrollView(c);
        tv = new TextView(c);
        tv.setTextColor(0xFFCFE8FF);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        sv.addView(tv);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sl.topMargin = Ui.dp(c, 12);
        col.addView(sv, sl);

        addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    boolean isOpen() {
        return getVisibility() == View.VISIBLE;
    }

    void open() {
        setVisibility(View.VISIBLE);
        refresh();
        Net.setLogListener(this::refresh);
    }

    void closePanel() {
        setVisibility(View.GONE);
        Net.setLogListener(null);
    }

    void toggle() {
        if (isOpen()) closePanel();
        else open();
    }

    private void refresh() {
        tv.setText(Net.logText());
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("VideoSync", Net.logText()));
            Toast.makeText(getContext(), "Registro copiado", Toast.LENGTH_SHORT).show();
        }
    }
}
