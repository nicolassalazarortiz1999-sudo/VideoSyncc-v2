package com.videosync.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.CRC32;

/**
 * Maestro: crea la red Wi-Fi Direct, acepta hasta 8 esclavos, les reparte el video en paralelo
 * (o reutiliza el que ya tienen) y ordena arrancar a todos en el mismo instante.
 */
public class MasterActivity extends Activity {
    private interface W {
        void w(DataOutputStream o) throws IOException;
    }

    private static final int REQ_PERM = 1;
    private static final int REQ_VIDEO = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private ServerSocket ctrlServer;
    private ServerSocket dataServer;

    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final Client[] slotArr = new Client[Net.MAX_SLAVES];
    private final Map<String, Client> byId = new ConcurrentHashMap<>();
    private volatile List<Client> participants = new ArrayList<>();

    private volatile boolean running = true;
    private volatile boolean sending = false;
    private volatile boolean playing = false;
    private volatile boolean creating = false;
    private boolean started = false;

    private Uri videoUri;
    private String videoName;
    private volatile long videoSize;
    private volatile String videoFp;

    private long lastTotal = 0;
    private long lastTickMs = 0;
    private double speed = 0;

    // interfaz
    private TextView status;
    private TextView countText;
    private TextView speedText;
    private TextView fileText;
    private TextView pctText;
    private TextView pickBtn;
    private TextView sendBtn;
    private TextView[] bandChips = new TextView[3];
    private ConstellationView cons;
    private BarView bar;
    private FrameLayout playerLayer;
    private VideoView video;
    private TextView countdown;
    private LogPanel logPanel;
    private SyncStart pendingStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Ui.BG1);
        getWindow().setNavigationBarColor(Ui.BG3);
        buildUi();

        if (hasPerms()) {
            start();
        } else {
            requestPermissions(perms(), REQ_PERM);
        }
    }

    // ---------------------------------------------------------------- interfaz

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.addView(new BgView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 22);
        col.setPadding(pad, Ui.dp(this, 26), pad, pad);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "MAESTRO", 26, Ui.TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView logBtn = Ui.chip(this, "REGISTRO");
        logBtn.setOnClickListener(v -> logPanel.toggle());
        head.addView(logBtn);
        col.addView(head, match(0));

        status = Ui.text(this, "Creando red Wi-Fi Direct…", 15, Ui.MUTED, false);
        status.setGravity(Gravity.CENTER);
        col.addView(status, match(Ui.dp(this, 6)));

        cons = new ConstellationView(this);
        col.addView(cons, match(Ui.dp(this, 8)));

        countText = Ui.text(this, "0 / " + Net.MAX_SLAVES + " esclavos conectados", 17, Ui.TEXT, true);
        countText.setGravity(Gravity.CENTER);
        col.addView(countText, match(Ui.dp(this, 4)));

        speedText = Ui.text(this, " ", 13, Ui.MINT, false);
        speedText.setGravity(Gravity.CENTER);
        col.addView(speedText, match(Ui.dp(this, 4)));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(this));
        int cp = Ui.dp(this, 16);
        card.setPadding(cp, cp, cp, cp);

        fileText = Ui.text(this, "Ningún video elegido", 14, Ui.MUTED, false);
        fileText.setGravity(Gravity.CENTER);
        fileText.setSingleLine(true);
        fileText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        card.addView(fileText, match(0));

        pickBtn = Ui.button(this, "ELEGIR VIDEO", false);
        pickBtn.setOnClickListener(v -> pickVideo());
        card.addView(pickBtn, match(Ui.dp(this, 12)));

        sendBtn = Ui.button(this, "ENVIAR Y REPRODUCIR", true);
        sendBtn.setOnClickListener(v -> sendAll());
        card.addView(sendBtn, match(Ui.dp(this, 10)));
        Ui.setEnabledLook(sendBtn, false);

        bar = new BarView(this);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 10));
        bl.topMargin = Ui.dp(this, 16);
        card.addView(bar, bl);

        pctText = Ui.text(this, "0%", 12, Ui.MUTED, false);
        pctText.setGravity(Gravity.CENTER);
        card.addView(pctText, match(Ui.dp(this, 4)));

        col.addView(card, match(Ui.dp(this, 14)));

        LinearLayout bandRow = new LinearLayout(this);
        bandRow.setOrientation(LinearLayout.HORIZONTAL);
        bandRow.setGravity(Gravity.CENTER);
        TextView bl2 = Ui.text(this, "Red:  ", 12, Ui.MUTED, false);
        bandRow.addView(bl2);
        String[] names = {"Auto", "2.4 GHz", "5 GHz"};
        int cur = Net.prefs(this).getInt("band", 0);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView ch = Ui.chip(this, names[i]);
            Ui.chipLook(ch, i == cur);
            ch.setOnClickListener(v -> setBand(idx));
            bandChips[i] = ch;
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            l.leftMargin = Ui.dp(this, 6);
            bandRow.addView(ch, l);
        }
        col.addView(bandRow, match(Ui.dp(this, 14)));

        scroll.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        playerLayer = new FrameLayout(this);
        playerLayer.setBackgroundColor(0xFF000000);
        playerLayer.setVisibility(View.GONE);
        video = new VideoView(this);
        FrameLayout.LayoutParams vl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        vl.gravity = Gravity.CENTER;
        playerLayer.addView(video, vl);
        countdown = Ui.text(this, "", 120, Ui.TEXT, true);
        countdown.setGravity(Gravity.CENTER);
        countdown.setShadowLayer(12f, 0f, 0f, 0xFF000000);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.gravity = Gravity.CENTER;
        playerLayer.addView(countdown, cl);
        TextView stop = Ui.button(this, "DETENER", false);
        stop.setOnClickListener(v -> stopAll());
        FrameLayout.LayoutParams stl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stl.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        stl.bottomMargin = Ui.dp(this, 28);
        playerLayer.addView(stop, stl);
        root.addView(playerLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        logPanel = new LogPanel(this);
        root.addView(logPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        Ui.enter(title, 0);
        Ui.enter(status, 1);
        Ui.enter(cons, 2);
        Ui.enter(card, 3);
    }

    private LinearLayout.LayoutParams match(int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = top;
        return l;
    }

    private void setStatus(final String s) {
        runOnUiThread(() -> {
            if (status == null || s.contentEquals(status.getText())) return;
            status.animate().alpha(0f).setStartDelay(0).setDuration(120).withEndAction(() -> {
                status.setText(s);
                status.animate().alpha(1f).setStartDelay(0).setDuration(180).start();
            }).start();
        });
    }

    private void refreshUi() {
        int n = clients.size();
        countText.setText(n + " / " + Net.MAX_SLAVES + " esclavos conectados");
        boolean can = videoUri != null && videoFp != null && n > 0 && !sending && !playing;
        Ui.setEnabledLook(sendBtn, can);
        Ui.setEnabledLook(pickBtn, !sending && !playing);
    }

    private void setBand(int idx) {
        Net.prefs(this).edit().putInt("band", idx).apply();
        for (int i = 0; i < 3; i++) Ui.chipLook(bandChips[i], i == idx);
        Net.log("Banda cambiada a " + (idx == 0 ? "Auto" : idx == 1 ? "2.4 GHz" : "5 GHz") + ": reinicio la red");
        if (started) {
            setStatus("Reiniciando la red…");
            startGroup();
        }
    }

    /** Refresca la constelación, la velocidad y la barra cada 120 ms. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long total = 0;
            for (int i = 0; i < Net.MAX_SLAVES; i++) {
                Client c = slotArr[i];
                if (c == null) {
                    cons.setNode(i, ConstellationView.EMPTY, 0f);
                    continue;
                }
                float p = 0f;
                if (c.state == ConstellationView.RECEIVING) {
                    p = videoSize > 0 ? Math.min(1f, c.sentBytes / (float) videoSize) : 0f;
                } else if (c.state == ConstellationView.READY || c.state == ConstellationView.PLAYING) {
                    p = 1f;
                }
                cons.setNode(i, c.state, p);
                total += c.sentBytes;
            }

            long want = 0;
            long got = 0;
            for (Client c : participants) {
                if (c.sentBytes > 0 || c.state == ConstellationView.RECEIVING) {
                    want += videoSize;
                    got += c.sentBytes;
                }
            }
            float overall = want > 0 ? Math.min(1f, got / (float) want) : (sending ? 0f : bar.isShown() ? 1f : 0f);
            if (!sending && !playing && want == 0) overall = 0f;
            bar.setProgress(overall);
            pctText.setText((int) (overall * 100) + "%");

            long now = SystemClock.elapsedRealtime();
            if (lastTickMs > 0 && now > lastTickMs) {
                double inst = (total - lastTotal) / 1048576.0 / ((now - lastTickMs) / 1000.0);
                if (inst < 0) inst = 0;
                speed = speed * 0.7 + inst * 0.3;
            }
            lastTotal = total;
            lastTickMs = now;
            if (sending && speed > 0.05 && want > got) {
                long secs = (long) ((want - got) / 1048576.0 / speed);
                speedText.setText(String.format(Locale.US, "⚡ %.1f MB/s  ·  quedan %d:%02d", speed, secs / 60, secs % 60));
            } else if (sending) {
                speedText.setText("Preparando envío…");
            } else {
                speedText.setText(" ");
            }
            ui.postDelayed(this, 120);
        }
    };

    // ---------------------------------------------------------------- permisos

    private String[] perms() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.NEARBY_WIFI_DEVICES};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPerms() {
        for (String p : perms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQ_PERM) {
            if (hasPerms()) {
                start();
            } else {
                setStatus("Falta el permiso de Wi-Fi cercano");
            }
        }
    }

    // ---------------------------------------------------------------- red Wi-Fi Direct

    private void start() {
        if (started) return;
        started = true;
        Net.log("Maestro iniciado · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android API " + Build.VERSION.SDK_INT);
        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            setStatus("Este equipo no soporta Wi-Fi Direct");
            Net.log("WifiP2pManager no disponible");
            return;
        }
        channel = manager.initialize(this, getMainLooper(), null);
        if (!Net.locationOn(this)) {
            setStatus("Enciende la ubicación (GPS) del celular");
            Net.log("AVISO: la ubicación está apagada; Wi-Fi Direct la necesita en Android 12 o menor");
        }
        startServers();
        startGroup();
        ui.post(tick);
    }

    @SuppressLint("MissingPermission")
    private void startGroup() {
        if (creating) return;
        creating = true;
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                createGroup();
            }

            @Override
            public void onFailure(int reason) {
                createGroup();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void createGroup() {
        if (!running) return;
        int band = Net.prefs(this).getInt("band", 0);
        int b = band == 1 ? WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
                : band == 2 ? WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                : WifiP2pConfig.GROUP_OWNER_BAND_AUTO;
        WifiP2pConfig cfg = new WifiP2pConfig.Builder()
                .setNetworkName(Net.NET_NAME)
                .setPassphrase(Net.PASS)
                .setGroupOperatingBand(b)
                .enablePersistentMode(false)
                .build();
        manager.createGroup(channel, cfg, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                creating = false;
                Net.log("Grupo Wi-Fi Direct creado: " + Net.NET_NAME);
                setStatus("Red lista · esperando esclavos");
                ui.removeCallbacks(watchdog);
                ui.postDelayed(watchdog, 15000);
            }

            @Override
            public void onFailure(int reason) {
                Net.log("createGroup falló: " + Net.reason(reason) + " · reintento en 3 s");
                setStatus("No pude crear la red (" + Net.reason(reason) + ") · reintentando…");
                ui.postDelayed(() -> {
                    creating = false;
                    if (running) startGroup();
                }, 3000);
            }
        });
    }

    /** Cada 15 s comprueba que la red siga viva; si el sistema la tumbó, la vuelve a crear. */
    private final Runnable watchdog = new Runnable() {
        @SuppressLint("MissingPermission")
        @Override
        public void run() {
            if (!running || manager == null) return;
            if (!creating) {
                manager.requestGroupInfo(channel, g -> {
                    if (g == null) {
                        Net.log("La red Wi-Fi Direct se cayó: la recreo");
                        setStatus("Recreando la red…");
                        startGroup();
                    } else {
                        Net.log("Red OK · " + g.getNetworkName() + " · dispositivos P2P: " + g.getClientList().size()
                                + " · esclavos de la app: " + clients.size());
                    }
                });
            }
            ui.postDelayed(this, 15000);
        }
    };

    // ---------------------------------------------------------------- servidores

    private void startServers() {
        Thread tc = new Thread(() -> {
            try {
                ctrlServer = new ServerSocket();
                ctrlServer.setReuseAddress(true);
                ctrlServer.bind(new InetSocketAddress(Net.CTRL_PORT));
                Net.log("Servidor de control en el puerto " + Net.CTRL_PORT);
                while (running) {
                    final Socket s = ctrlServer.accept();
                    Thread h = new Thread(() -> handleCtrl(s), "ctl-client");
                    h.setDaemon(true);
                    h.start();
                }
            } catch (IOException e) {
                if (running) {
                    Net.log("Servidor de control: " + e.getMessage());
                    setStatus("Error de red: " + e.getMessage());
                }
            }
        }, "ctl-accept");
        tc.setDaemon(true);
        tc.start();

        Thread td = new Thread(() -> {
            try {
                dataServer = new ServerSocket();
                dataServer.setReuseAddress(true);
                dataServer.bind(new InetSocketAddress(Net.DATA_PORT));
                Net.log("Servidor de datos en el puerto " + Net.DATA_PORT);
                while (running) {
                    final Socket s = dataServer.accept();
                    Thread h = new Thread(() -> handleData(s), "data-client");
                    h.setDaemon(true);
                    h.start();
                }
            } catch (IOException e) {
                if (running) Net.log("Servidor de datos: " + e.getMessage());
            }
        }, "data-accept");
        td.setDaemon(true);
        td.start();
    }

    private synchronized Client register(String id, String model, Socket s, DataInputStream in, DataOutputStream out) {
        int slot = -1;
        for (int i = 0; i < Net.MAX_SLAVES; i++) {
            if (slotArr[i] == null) {
                slot = i;
                break;
            }
        }
        if (slot < 0) return null;
        Client c = new Client(slot, id, model, s, in, out);
        slotArr[slot] = c;
        clients.add(c);
        byId.put(id, c);
        return c;
    }

    private void drop(Client c, String why) {
        boolean removed;
        synchronized (this) {
            removed = clients.remove(c);
            if (removed) {
                if (slotArr[c.slot] == c) slotArr[c.slot] = null;
                byId.remove(c.id, c);
            }
        }
        if (!removed) return;
        Net.log("Esclavo " + (c.slot + 1) + " fuera (" + why + ")");
        c.close();
        ui.post(this::refreshUi);
        checkAllReady();
    }

    private void handleCtrl(Socket s) {
        Client c = null;
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(6000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 8192));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 4096));
            if (!"HELLO".equals(in.readUTF())) {
                s.close();
                return;
            }
            String id = in.readUTF();
            String model = in.readUTF();
            Client old = byId.get(id);
            if (old != null) drop(old, "reconexión");
            c = register(id, model, s, in, out);
            if (c == null) {
                out.writeUTF("FULL");
                out.flush();
                s.close();
                Net.log("Rechazado " + model + ": ya hay " + Net.MAX_SLAVES + " esclavos");
                return;
            }
            final Client cc = c;
            cc.ctl(o -> {
                o.writeUTF("WELCOME");
                o.writeInt(cc.slot);
            });
            Net.log("Esclavo " + (c.slot + 1) + " unido: " + model);
            ui.post(this::refreshUi);

            while (running) {
                String m = in.readUTF();
                if ("PING".equals(m)) {
                    final long t1 = in.readLong();
                    final long t2 = Net.nowNs();
                    cc.ctl(o -> {
                        o.writeUTF("PONG");
                        o.writeLong(t1);
                        o.writeLong(t2);
                    });
                } else if ("NEED".equals(m)) {
                    pushVideo(cc);
                } else if ("READY".equals(m)) {
                    cc.ready = true;
                    cc.state = ConstellationView.READY;
                    Net.log("Esclavo " + (cc.slot + 1) + " listo");
                    checkAllReady();
                }
            }
        } catch (Exception e) {
            if (c != null) Net.log("Esclavo " + (c.slot + 1) + ": " + e.getClass().getSimpleName());
        } finally {
            if (c != null) {
                drop(c, "desconexión");
            } else {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // nada
                }
            }
        }
    }

    private void handleData(Socket s) {
        try {
            s.setSendBufferSize(1 << 20);
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            if (!"DATA".equals(in.readUTF())) {
                s.close();
                return;
            }
            String id = in.readUTF();
            Client c = byId.get(id);
            if (c == null) {
                s.close();
                return;
            }
            s.setSoTimeout(0);
            Socket old = c.dataSock;
            c.dataOut = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), Net.CHUNK));
            c.dataSock = s;
            if (old != null) {
                try {
                    old.close();
                } catch (IOException ignored) {
                    // nada
                }
            }
            Net.log("Esclavo " + (c.slot + 1) + ": canal de datos listo");
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nada
            }
        }
    }

    /** Un esclavo conectado. Control y datos van por canales distintos. */
    private class Client {
        final int slot;
        final String id;
        final String model;
        final Socket sock;
        final DataInputStream in;
        final DataOutputStream out;
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        volatile DataOutputStream dataOut;
        volatile Socket dataSock;
        volatile boolean ready;
        volatile int state = ConstellationView.CONNECTED;
        volatile long sentBytes;

        Client(int slot, String id, String model, Socket s, DataInputStream in, DataOutputStream out) {
            this.slot = slot;
            this.id = id;
            this.model = model;
            this.sock = s;
            this.in = in;
            this.out = out;
        }

        void ctl(final W w) {
            try {
                ex.execute(() -> {
                    try {
                        synchronized (out) {
                            w.w(out);
                            out.flush();
                        }
                    } catch (IOException e) {
                        drop(this, "error de envío");
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // ya cerrado
            }
        }

        void close() {
            try {
                sock.close();
            } catch (IOException ignored) {
                // nada
            }
            Socket d = dataSock;
            if (d != null) {
                try {
                    d.close();
                } catch (IOException ignored) {
                    // nada
                }
            }
            ex.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- envío

    private void pickVideo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        startActivityForResult(i, REQ_VIDEO);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VIDEO || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        String name = "video";
        long size = -1;
        try (Cursor cur = getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = cur.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !cur.isNull(ni)) name = cur.getString(ni);
                if (si >= 0 && !cur.isNull(si)) size = cur.getLong(si);
            }
        } catch (Exception ignored) {
            // se usa el valor por defecto
        }
        if (size <= 0) {
            setStatus("No pude leer el tamaño del video");
            return;
        }
        videoUri = uri;
        videoName = name;
        videoSize = size;
        videoFp = null;
        final String shownName = name;
        final long shownSize = size;
        fileText.setText(shownName + " · " + (shownSize / 1048576) + " MB · analizando…");
        refreshUi();
        Thread t = new Thread(() -> {
            String fp = null;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                CRC32 crc = new CRC32();
                byte[] b = new byte[65536];
                long left = Math.min(shownSize, 4L << 20);
                while (left > 0 && is != null) {
                    int n = is.read(b, 0, (int) Math.min(b.length, left));
                    if (n < 0) break;
                    crc.update(b, 0, n);
                    left -= n;
                }
                fp = Long.toHexString(crc.getValue()) + "-" + shownSize;
            } catch (Exception e) {
                Net.log("No pude analizar el video: " + e.getMessage());
            }
            final String result = fp;
            ui.post(() -> {
                if (uri.equals(videoUri)) {
                    videoFp = result;
                    fileText.setText(shownName + " · " + (shownSize / 1048576) + " MB");
                    if (result == null) setStatus("No pude leer el video elegido");
                    refreshUi();
                }
            });
        }, "hash");
        t.setDaemon(true);
        t.start();
    }

    private void sendAll() {
        if (videoUri == null || videoFp == null || clients.isEmpty() || sending || playing) return;
        List<Client> snap = new ArrayList<>(clients);
        for (Client c : snap) {
            c.ready = false;
            c.sentBytes = 0;
            c.state = ConstellationView.CONNECTED;
        }
        participants = snap;
        sending = true;
        setStatus("Enviando a " + snap.size() + (snap.size() == 1 ? " esclavo…" : " esclavos…"));
        Net.log("OFFER a " + snap.size() + " esclavos: " + videoName);
        refreshUi();
        final String fp = videoFp;
        final long size = videoSize;
        final String name = videoName;
        for (Client c : snap) {
            c.ctl(o -> {
                o.writeUTF("OFFER");
                o.writeUTF(fp);
                o.writeLong(size);
                o.writeUTF(name);
            });
        }
    }

    private void pushVideo(final Client c) {
        final Uri uri = videoUri;
        final long size = videoSize;
        final String fp = videoFp;
        if (uri == null || fp == null) return;
        Thread t = new Thread(() -> {
            try {
                long end = SystemClock.elapsedRealtime() + 8000;
                while (c.dataOut == null && SystemClock.elapsedRealtime() < end) {
                    Thread.sleep(40);
                }
                DataOutputStream o = c.dataOut;
                if (o == null) throw new IOException("sin canal de datos");
                c.state = ConstellationView.RECEIVING;
                c.sentBytes = 0;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) throw new IOException("no se pudo abrir el video");
                    o.writeUTF("VIDEO");
                    o.writeUTF(fp);
                    o.writeLong(size);
                    byte[] buf = new byte[Net.CHUNK];
                    long left = size;
                    while (left > 0) {
                        int n = is.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new IOException("video incompleto");
                        o.write(buf, 0, n);
                        left -= n;
                        c.sentBytes += n;
                    }
                    o.flush();
                }
                Net.log("Esclavo " + (c.slot + 1) + ": envío completo");
            } catch (Exception e) {
                Net.log("Esclavo " + (c.slot + 1) + ": falló el envío (" + e.getMessage() + ")");
                drop(c, "envío");
            }
        }, "push-" + c.slot);
        t.setDaemon(true);
        t.start();
    }

    private synchronized void checkAllReady() {
        if (!sending) return;
        boolean any = false;
        for (Client c : participants) {
            if (!clients.contains(c)) continue;
            any = true;
            if (!c.ready) return;
        }
        sending = false;
        if (!any) {
            setStatus("Se perdieron todos los esclavos");
            ui.post(this::refreshUi);
            return;
        }
        Net.log("Todos listos");
        ui.post(this::beginPlayback);
    }

    // ---------------------------------------------------------------- reproducción sincronizada

    private void beginPlayback() {
        playing = true;
        refreshUi();
        setStatus("Todos listos · cuenta regresiva");
        playerLayer.setVisibility(View.VISIBLE);
        countdown.setText("");
        video.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            final long target = Net.nowNs() + Net.LEAD_MS * 1_000_000L;
            int n = 0;
            for (Client c : participants) {
                if (clients.contains(c)) {
                    c.state = ConstellationView.PLAYING;
                    c.ctl(o -> {
                        o.writeUTF("PLAYAT");
                        o.writeLong(target);
                        o.writeLong(Net.LEAD_MS);
                    });
                    n++;
                }
            }
            Net.log("PLAYAT enviado a " + n + " esclavos");
            if (pendingStart != null) pendingStart.cancel();
            pendingStart = new SyncStart(ui, target, countdown, this::startLocal);
            pendingStart.begin();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            Net.log("Error del reproductor: " + what + "/" + extra);
            stopAll();
            return true;
        });
        video.setOnCompletionListener(mp -> {
            Net.log("Video terminado");
            hidePlayer();
            for (Client c : participants) {
                if (c.state == ConstellationView.PLAYING) c.state = ConstellationView.READY;
            }
            playing = false;
            refreshUi();
            setStatus("Fin · puedes reproducirlo otra vez al instante");
        });
        video.setVideoURI(videoUri);
    }

    private void startLocal() {
        video.start();
        setStatus("Reproduciendo en todas las pantallas");
        Net.log("▶ inicio (maestro)");
    }

    private void stopAll() {
        for (Client c : clients) {
            c.ctl(o -> o.writeUTF("STOP"));
            if (c.state == ConstellationView.PLAYING) c.state = ConstellationView.READY;
        }
        sending = false;
        playing = false;
        hidePlayer();
        refreshUi();
        setStatus("Detenido");
    }

    private void hidePlayer() {
        if (pendingStart != null) pendingStart.cancel();
        try {
            video.stopPlayback();
        } catch (Exception ignored) {
            // nada
        }
        countdown.setText("");
        playerLayer.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    public void onBackPressed() {
        if (logPanel.isOpen()) {
            logPanel.closePanel();
        } else if (playerLayer.getVisibility() == View.VISIBLE) {
            stopAll();
        } else {
            super.onBackPressed();
        }
    }

    @SuppressLint("MissingPermission")
    @Override
    protected void onDestroy() {
        running = false;
        sending = false;
        ui.removeCallbacksAndMessages(null);
        Net.setLogListener(null);
        try {
            if (ctrlServer != null) ctrlServer.close();
        } catch (IOException ignored) {
            // nada
        }
        try {
            if (dataServer != null) dataServer.close();
        } catch (IOException ignored) {
            // nada
        }
        for (Client c : clients) c.close();
        clients.clear();
        if (manager != null && channel != null) {
            manager.removeGroup(channel, null);
        }
        super.onDestroy();
    }
}
