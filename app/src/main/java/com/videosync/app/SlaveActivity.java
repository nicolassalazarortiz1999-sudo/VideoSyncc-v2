package com.videosync.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Esclavo: se une solo al maestro (Wi-Fi Direct), recibe el video, lo deja listo y lo arranca
 * en el instante exacto que ordena el maestro. Sin controles.
 */
public class SlaveActivity extends Activity {
    private interface W {
        void w(DataOutputStream o) throws IOException;
    }

    private static final int REQ_PERM = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final String myId = UUID.randomUUID().toString().substring(0, 8);
    private final Object wlock = new Object();

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;

    private volatile boolean running = true;
    private volatile boolean linkUp = false;
    private volatile boolean sessionAlive = false;
    private volatile boolean viaB = false;
    private volatile int linkGen = 0;
    private volatile String masterHost = Net.GO_IP;
    private volatile Socket ctrl;
    private volatile Socket dataSock;
    private volatile DataOutputStream ctrlOut;
    private boolean started = false;
    private int attempt = 0;
    private int sessionFails = 0;

    // sincronización de reloj con el maestro
    private final long[] sRtt = new long[12];
    private final long[] sOff = new long[12];
    private int sCount = 0;
    private volatile long offsetNs = 0;
    private volatile boolean synced = false;

    // interfaz
    private RadarView radar;
    private TextView title;
    private TextView slotText;
    private TextView status;
    private BarView bar;
    private TextView ver;
    private FrameLayout playerLayer;
    private VideoView video;
    private TextView countdown;
    private LogPanel logPanel;
    private SyncStart pendingStart;
    private int taps = 0;
    private long firstTap = 0;

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            if (running && !linkUp) join();
        }
    };

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

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 28);
        col.setPadding(pad, Ui.dp(this, 40), pad, Ui.dp(this, 24));

        slotText = Ui.text(this, "", 44, Ui.MINT, true);
        slotText.setGravity(Gravity.CENTER);
        col.addView(slotText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 64)));

        radar = new RadarView(this);
        radar.setColor(Ui.ACCENT);
        col.addView(radar, new LinearLayout.LayoutParams(Ui.dp(this, 230), Ui.dp(this, 230)));

        title = Ui.text(this, "ESCLAVO", 26, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setOnClickListener(v -> {
            long n = SystemClock.elapsedRealtime();
            if (n - firstTap > 4000) {
                taps = 0;
                firstTap = n;
            }
            taps++;
            if (taps >= 5) {
                taps = 0;
                logPanel.toggle();
            }
        });
        col.addView(title, top(Ui.dp(this, 22)));

        status = Ui.text(this, "Iniciando…", 16, Ui.MUTED, false);
        status.setGravity(Gravity.CENTER);
        col.addView(status, top(Ui.dp(this, 10)));

        bar = new BarView(this);
        bar.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Ui.dp(this, 220), Ui.dp(this, 8));
        bl.topMargin = Ui.dp(this, 16);
        col.addView(bar, bl);

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        ver = Ui.text(this, "VideoSync v2 · Wi-Fi Direct", 11, 0x66FFFFFF, false);
        ver.setGravity(Gravity.CENTER);
        ver.setOnLongClickListener(v -> {
            Net.prefs(this).edit().remove("role").apply();
            finish();
            return true;
        });
        col.addView(ver, top(0));

        root.addView(col, new FrameLayout.LayoutParams(
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
        root.addView(playerLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        logPanel = new LogPanel(this);
        root.addView(logPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        Ui.enter(radar, 0);
        Ui.enter(title, 1);
        Ui.enter(status, 2);
    }

    private LinearLayout.LayoutParams top(int t) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = t;
        return l;
    }

    private void setStatus(final String s, final boolean fade) {
        runOnUiThread(() -> {
            if (status == null) return;
            if (!fade) {
                status.setText(s);
                return;
            }
            if (s.contentEquals(status.getText())) return;
            status.animate().alpha(0f).setStartDelay(0).setDuration(120).withEndAction(() -> {
                status.setText(s);
                status.animate().alpha(1f).setStartDelay(0).setDuration(180).start();
            }).start();
        });
    }

    private void setConnectedLook(final boolean connected) {
        runOnUiThread(() -> radar.setColor(connected ? Ui.MINT : Ui.ACCENT));
    }

    private void showProgress(float p, double mbps) {
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(p);
        if (p >= 1f) {
            ui.postDelayed(() -> bar.setVisibility(View.INVISIBLE), 700);
        } else if (mbps >= 0) {
            status.setText(String.format(java.util.Locale.US, "Recibiendo video… %d%%  ·  %.1f MB/s",
                    (int) (p * 100), mbps));
        }
    }

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
                setStatus("Falta el permiso de Wi-Fi cercano", true);
            }
        }
    }

    // ---------------------------------------------------------------- unión al maestro

    private void start() {
        if (started) return;
        started = true;
        Net.log("Esclavo iniciado · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android API " + Build.VERSION.SDK_INT);
        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            setStatus("Este equipo no soporta Wi-Fi Direct", true);
            Net.log("WifiP2pManager no disponible");
            return;
        }
        channel = manager.initialize(this, getMainLooper(), null);
        if (!Net.locationOn(this)) {
            setStatus("Enciende la ubicación (GPS) del celular", true);
            Net.log("AVISO: la ubicación está apagada; Wi-Fi Direct la necesita en Android 12 o menor");
        }

        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
                    onConnectionChanged();
                } else if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(a)) {
                    int st = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    if (st == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                        setStatus("Enciende el Wi-Fi", true);
                        Net.log("Wi-Fi Direct desactivado");
                    } else if (st == WifiP2pManager.WIFI_P2P_STATE_ENABLED && !linkUp) {
                        Net.log("Wi-Fi Direct activado");
                        ui.removeCallbacks(retry);
                        ui.postDelayed(retry, 500);
                    }
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
        join();
    }

    private void join() {
        ui.removeCallbacks(retry);
        if (!running || linkUp || manager == null) return;
        int a = attempt++;
        if (a % 5 == 4) {
            joinB();
        } else {
            joinA();
        }
    }

    private class Quiet implements WifiP2pManager.ActionListener {
        private final String what;

        Quiet(String what) {
            this.what = what;
        }

        @Override
        public void onSuccess() {
        }

        @Override
        public void onFailure(int reason) {
            Net.log(what + " falló: " + Net.reason(reason));
        }
    }

    /** Estrategia A: Wi-Fi Direct puro. Entra al grupo del maestro por nombre y clave, sin pedir nada. */
    @SuppressLint("MissingPermission")
    private void joinA() {
        releaseB();
        setStatus("Buscando al maestro…", true);
        setConnectedLook(false);
        Net.log("Intento " + attempt + " · estrategia A (Wi-Fi Direct)");
        manager.cancelConnect(channel, null);
        manager.discoverPeers(channel, new Quiet("discoverPeers"));
        ui.postDelayed(this::connectA, 2000);
    }

    @SuppressLint("MissingPermission")
    private void connectA() {
        if (!running || linkUp) return;
        manager.stopPeerDiscovery(channel, null);
        WifiP2pConfig cfg = new WifiP2pConfig.Builder()
                .setNetworkName(Net.NET_NAME)
                .setPassphrase(Net.PASS)
                .build();
        manager.connect(channel, cfg, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Net.log("connect(): solicitud enviada, esperando al grupo");
                ui.postDelayed(retry, 14000);
            }

            @Override
            public void onFailure(int reason) {
                Net.log("connect() falló: " + Net.reason(reason));
                ui.postDelayed(retry, 3500);
            }
        });
    }

    /** Estrategia B (respaldo): se une a la red del grupo como Wi-Fi normal. Android pide confirmar una vez. */
    @SuppressLint("MissingPermission")
    private void joinB() {
        setStatus("Probando conexión alterna… acepta el aviso", true);
        Net.log("Intento " + attempt + " · estrategia B (acepta el aviso de Android si aparece)");
        manager.cancelConnect(channel, null);
        releaseB();
        try {
            WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                    .setSsid(Net.NET_NAME)
                    .setWpa2Passphrase(Net.PASS)
                    .build();
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(spec)
                    .build();
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network n) {
                    Net.log("B: red disponible");
                    cm.bindProcessToNetwork(n);
                    viaB = true;
                    ui.post(() -> onLink(Net.GO_IP));
                }

                @Override
                public void onUnavailable() {
                    Net.log("B: red no disponible");
                    ui.post(retry);
                }

                @Override
                public void onLost(Network n) {
                    Net.log("B: red perdida");
                    ui.post(() -> onLinkLost());
                }
            };
            cm.requestNetwork(req, netCb, 40000);
        } catch (Exception e) {
            Net.log("B falló: " + e.getMessage());
            ui.postDelayed(retry, 2000);
        }
    }

    private void releaseB() {
        if (netCb != null) {
            try {
                cm.unregisterNetworkCallback(netCb);
            } catch (Exception ignored) {
                // nada
            }
            netCb = null;
            try {
                cm.bindProcessToNetwork(null);
            } catch (Exception ignored) {
                // nada
            }
            viaB = false;
        }
    }

    @SuppressLint("MissingPermission")
    private void onConnectionChanged() {
        if (manager == null) return;
        manager.requestConnectionInfo(channel, info -> {
            if (info != null && info.groupFormed && !info.isGroupOwner && info.groupOwnerAddress != null) {
                if (!linkUp) onLink(info.groupOwnerAddress.getHostAddress());
            } else if (info != null && info.groupFormed && info.isGroupOwner && !linkUp) {
                Net.log("Quedé como dueño de grupo (no debería): reinicio la unión");
                manager.removeGroup(channel, null);
                ui.postDelayed(retry, 1500);
            } else if (linkUp && !viaB) {
                Net.log("Se perdió el grupo Wi-Fi Direct");
                onLinkLost();
            }
        });
    }

    private void onLink(String host) {
        if (linkUp) return;
        ui.removeCallbacks(retry);
        linkUp = true;
        masterHost = host;
        sessionFails = 0;
        final int gen = ++linkGen;
        Net.log("Enlace Wi-Fi listo → maestro " + host + (viaB ? " (estrategia B)" : " (estrategia A)"));
        setStatus("Conectando con el maestro…", true);
        startNet(gen);
    }

    @SuppressLint("MissingPermission")
    private void onLinkLost() {
        if (!linkUp && !sessionAlive) {
            ui.postDelayed(retry, 1500);
            return;
        }
        linkUp = false;
        linkGen++;
        closeSession();
        hidePlayer();
        setConnectedLook(false);
        setStatus("Buscando al maestro…", true);
        releaseB();
        if (manager != null) manager.removeGroup(channel, null);
        ui.postDelayed(retry, 1500);
    }

    // ---------------------------------------------------------------- sockets

    private void startNet(final int gen) {
        Thread t = new Thread(() -> {
            while (running && linkUp && gen == linkGen) {
                try {
                    session(gen);
                } catch (Exception e) {
                    Net.log("Sesión: " + e.getClass().getSimpleName() + " " + e.getMessage());
                }
                closeSession();
                if (!(running && linkUp && gen == linkGen)) break;
                setConnectedLook(false);
                setStatus("Reconectando…", true);
                sessionFails++;
                if (sessionFails >= 6) {
                    Net.log("El maestro no responde: reinicio la unión");
                    ui.post(this::onLinkLost);
                    return;
                }
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "ctrl");
        t.setDaemon(true);
        t.start();
    }

    private void session(final int gen) throws Exception {
        final Socket c = new Socket();
        c.connect(new InetSocketAddress(masterHost, Net.CTRL_PORT), 4000);
        c.setTcpNoDelay(true);
        c.setSoTimeout(8000);
        ctrl = c;
        DataInputStream in = new DataInputStream(new BufferedInputStream(c.getInputStream(), 8192));
        ctrlOut = new DataOutputStream(new BufferedOutputStream(c.getOutputStream(), 4096));
        sessionAlive = true;
        sCount = 0;
        synced = false;
        Net.log("TCP control conectado");
        ctrlWrite(o -> {
            o.writeUTF("HELLO");
            o.writeUTF(myId);
            o.writeUTF(Build.MODEL);
        });
        startPing(c);
        startData(c);

        while (running && sessionAlive && gen == linkGen) {
            String cmd = in.readUTF();
            switch (cmd) {
                case "WELCOME": {
                    final int slot = in.readInt();
                    sessionFails = 0;
                    Net.log("Maestro me asignó el lugar #" + (slot + 1));
                    setConnectedLook(true);
                    setStatus("Conectado · esperando video", true);
                    runOnUiThread(() -> Ui.pop(slotText, "#" + (slot + 1)));
                    break;
                }
                case "PONG": {
                    long t1 = in.readLong();
                    long t2 = in.readLong();
                    onPong(t1, t2, Net.nowNs());
                    break;
                }
                case "OFFER": {
                    String fp = in.readUTF();
                    long size = in.readLong();
                    String name = in.readUTF();
                    handleOffer(fp, size, name);
                    break;
                }
                case "PLAYAT": {
                    long target = in.readLong();
                    long lead = in.readLong();
                    onPlayAt(target, lead);
                    break;
                }
                case "STOP":
                    ui.post(this::hidePlayer);
                    setStatus("Detenido", true);
                    break;
                case "FULL":
                    Net.log("El maestro está lleno (8/8)");
                    setStatus("El maestro ya tiene 8 esclavos", true);
                    throw new IOException("maestro lleno");
                default:
                    Net.log("Mensaje desconocido: " + cmd);
                    break;
            }
        }
    }

    private void ctrlWrite(W w) {
        DataOutputStream o = ctrlOut;
        if (o == null) return;
        synchronized (wlock) {
            try {
                w.w(o);
                o.flush();
            } catch (IOException e) {
                closeSession();
            }
        }
    }

    private void closeSession() {
        sessionAlive = false;
        try {
            Socket s = ctrl;
            if (s != null) s.close();
        } catch (IOException ignored) {
            // nada
        }
        try {
            Socket d = dataSock;
            if (d != null) d.close();
        } catch (IOException ignored) {
            // nada
        }
        ctrlOut = null;
    }

    private void startPing(final Socket owner) {
        Thread t = new Thread(() -> {
            while (sessionAlive && ctrl == owner) {
                ctrlWrite(o -> {
                    o.writeUTF("PING");
                    o.writeLong(Net.nowNs());
                });
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "ping");
        t.setDaemon(true);
        t.start();
    }

    /** Canal de datos aparte: así el video no frena los avisos ni la sincronización. */
    private void startData(final Socket owner) {
        Thread t = new Thread(() -> {
            while (sessionAlive && ctrl == owner) {
                Socket d = null;
                try {
                    d = new Socket();
                    d.setReceiveBufferSize(1 << 20);
                    d.connect(new InetSocketAddress(masterHost, Net.DATA_PORT), 4000);
                    dataSock = d;
                    DataOutputStream dout = new DataOutputStream(d.getOutputStream());
                    dout.writeUTF("DATA");
                    dout.writeUTF(myId);
                    dout.flush();
                    Net.log("TCP datos conectado");
                    DataInputStream din = new DataInputStream(new BufferedInputStream(d.getInputStream(), Net.CHUNK));
                    while (sessionAlive && ctrl == owner) {
                        receiveVideo(din);
                    }
                } catch (Exception e) {
                    if (sessionAlive) Net.log("Datos: " + e.getClass().getSimpleName() + " " + e.getMessage());
                } finally {
                    if (d != null) {
                        try {
                            d.close();
                        } catch (IOException ignored) {
                            // nada
                        }
                    }
                }
                if (sessionAlive && ctrl == owner) {
                    try {
                        Thread.sleep(800);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "data");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- reloj

    private void onPong(long t1, long t2, long t3) {
        long rtt = t3 - t1;
        if (rtt < 0) return;
        long off = t2 - (t1 + rtt / 2);
        sRtt[sCount % 12] = rtt;
        sOff[sCount % 12] = off;
        sCount++;
        int n = Math.min(sCount, 12);
        int best = 0;
        for (int i = 1; i < n; i++) {
            if (sRtt[i] < sRtt[best]) best = i;
        }
        offsetNs = sOff[best];
        synced = true;
    }

    // ---------------------------------------------------------------- video

    private File cacheFile(String fp) {
        return new File(getFilesDir(), "v_" + fp.replaceAll("[^A-Za-z0-9-]", "_") + ".mp4");
    }

    private void cleanCache(File keep) {
        File[] fs = getFilesDir().listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith("v_") && n.endsWith(".mp4") && !f.equals(keep)) {
                f.delete();
            }
        }
    }

    private void handleOffer(String fp, long size, String name) throws Exception {
        Net.log("OFFER " + name + " (" + (size / 1048576) + " MB)");
        ui.post(this::hidePlayer);
        File f = cacheFile(fp);
        if (f.exists() && f.length() == size) {
            Net.log("Ese video ya está guardado: no hace falta recibirlo");
            setStatus("Video ya guardado · preparando…", true);
            prepareBlocking(f);
            ctrlWrite(o -> o.writeUTF("READY"));
            setStatus("Listo · esperando al maestro", true);
        } else {
            setStatus("Recibiendo video…", true);
            ctrlWrite(o -> o.writeUTF("NEED"));
        }
    }

    private void receiveVideo(DataInputStream din) throws Exception {
        String h = din.readUTF();
        if (!"VIDEO".equals(h)) throw new IOException("encabezado inesperado: " + h);
        String fp = din.readUTF();
        long size = din.readLong();
        ui.post(this::hidePlayer);
        File tmp = new File(getFilesDir(), "video.tmp");
        byte[] buf = new byte[Net.CHUNK];
        long got = 0;
        long t0 = SystemClock.elapsedRealtime();
        long lastUi = 0;
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            while (got < size) {
                int n = din.read(buf, 0, (int) Math.min(buf.length, size - got));
                if (n < 0) throw new EOFException();
                fo.write(buf, 0, n);
                got += n;
                long now = SystemClock.elapsedRealtime();
                if (now - lastUi > 120) {
                    lastUi = now;
                    final float p = got / (float) size;
                    final double mbps = got / 1048576.0 / Math.max(0.001, (now - t0) / 1000.0);
                    ui.post(() -> showProgress(p, mbps));
                }
            }
        }
        File dst = cacheFile(fp);
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) throw new IOException("No se pudo guardar el video");
        cleanCache(dst);
        ui.post(() -> showProgress(1f, -1));
        Net.log("Video recibido (" + (size / 1048576) + " MB)");
        setStatus("Preparando video…", true);
        prepareBlocking(dst);
        ctrlWrite(o -> o.writeUTF("READY"));
        setStatus("Listo · esperando al maestro", true);
    }

    private void prepareBlocking(final File f) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        ui.post(() -> prepare(f, latch));
        if (!latch.await(20, TimeUnit.SECONDS)) Net.log("AVISO: el video tardó demasiado en prepararse");
    }

    private void prepare(File f, final CountDownLatch latch) {
        playerLayer.setVisibility(View.VISIBLE);
        countdown.setText("");
        video.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            video.seekTo(1);
            Net.log("Video preparado");
            latch.countDown();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            Net.log("Error del reproductor: " + what + "/" + extra);
            latch.countDown();
            hidePlayer();
            return true;
        });
        video.setOnCompletionListener(mp -> {
            hidePlayer();
            setStatus("Fin · esperando otro video", true);
        });
        video.setVideoPath(f.getAbsolutePath());
    }

    private void onPlayAt(long targetMaster, long leadMs) {
        long local;
        if (synced) {
            local = targetMaster - offsetNs;
        } else {
            local = Net.nowNs() + leadMs * 1_000_000L;
        }
        final long lt = local;
        Net.log("PLAYAT: faltan " + ((lt - Net.nowNs()) / 1_000_000L) + " ms · sincronizado=" + synced);
        ui.post(() -> {
            if (pendingStart != null) pendingStart.cancel();
            pendingStart = new SyncStart(ui, lt, countdown, this::startVideo);
            pendingStart.begin();
        });
    }

    private void startVideo() {
        if (playerLayer.getVisibility() != View.VISIBLE) return;
        video.start();
        setStatus("Reproduciendo", true);
        Net.log("▶ inicio");
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
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    /** El esclavo no puede salir ni hacer nada (solo cierra el registro si está abierto). */
    @Override
    public void onBackPressed() {
        if (logPanel.isOpen()) logPanel.closePanel();
    }

    @SuppressLint("MissingPermission")
    @Override
    protected void onDestroy() {
        running = false;
        linkUp = false;
        linkGen++;
        ui.removeCallbacksAndMessages(null);
        Net.setLogListener(null);
        closeSession();
        releaseB();
        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
                // nada
            }
        }
        if (manager != null && channel != null) {
            manager.cancelConnect(channel, null);
            manager.removeGroup(channel, null);
        }
        super.onDestroy();
    }
}
