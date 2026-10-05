package com.tvlink.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicConvolve3x3;
import android.media.ToneGenerator;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.media.AudioManager;
import android.media.ExifInterface;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Collections;

public class ReceiverActivity extends Activity {
    private volatile String code;
    private volatile boolean running = true;
    private ServerSocket server;
    private DatagramSocket udp;
    private WifiManager.MulticastLock mlock;
    private WifiManager.WifiLock wlock;

    private FrameLayout root;
    private VideoView video;
    private FillImageView image;
    private LinearLayout multi;
    private int boostMb = 0, enhSession = -1;
    private LoudnessEnhancer enh;
    private File[] multiFiles;
    private String[] multiTypes;
    private int multiTotal, multiGot;
    private LinearLayout idle;
    private TextView info, codeView;

    private PdfRenderer pdf;
    private ParcelFileDescriptor pfd;
    private int page;
    private boolean showing;
    private WebView web;
    private File curFile;
    private String curType;
    private int quality = 2;
    private float zoom = 1f, panX = 0f, panY = 0f;
    private FillVideoView fillView;
    // مستويات الضبط (1-10) كتتحفظ فـ TV Box وكتتطبق تلقائيا
    private int lvlFit = 10, lvlBri = 5, lvlCon = 5, lvlSat = 5, lvlTxt = 6, lvlSha = 7;
    // إضاءة الظلال لعرض الهاتف (الداتا شو كتغمق الألوان): 0 = بلا / 10 = أقوى
    private volatile int lvlGam = 5;
    // تعتيم البياض فالفيديو (0-10): كيخفف الضو ديال الداتا شو باش الكتابة السوداء تبان
    private int lvlVdim = 6;
    private View dimView;
    private LinearLayout vpanel;
    private TextView vpDimVal, vpFitVal;
    private boolean panelOff = false;
    // وضع الامتحان: صورة/ورقة تتعرض بعرض الداتا شو كامل (بلا إطار زجاجي) مع وضوح الكتابة
    private boolean examFull = false, examNext = false;
    private final Runnable panelHider = new Runnable() {
        @Override public void run() { if (vpanel != null) vpanel.setVisibility(View.GONE); }
    };
    private static final int UI_FLAGS = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
    // الزجاج: خلفية ملونة + إطار شفاف حول المحتوى (المحتوى نفسو يبقى صافي باش الكتابة تبان)
    private volatile int glass = 1;
    private FrameLayout glassBg;
    private View glassFrame;
    private final int[] gamLut = new int[256];
    private int gamLutFor = -1;
    private int[] gamPx;
    private float fit = 1f;
    // عرض شاشة الهاتف: 0 = ملء (تمديد) / 1 = تغطية (قص) / 2 = النسبة الأصلية
    private ImageView liveView;
    private int liveMode = 0;
    private Rect cropRect, candRect;
    private int candCount, frameNo, cropW, cropH;

    // VideoView كيملا الشاشة كاملة (ماشي غير الحجم الأصلي)
    private static class FillVideoView extends VideoView {
        boolean fill = true;
        FillVideoView(Context c) { super(c); }
        @Override
        protected void onMeasure(int w, int h) {
            if (fill) setMeasuredDimension(getDefaultSize(0, w), getDefaultSize(0, h));
            else super.onMeasure(w, h);
        }
    }
    private final AtomicInteger streamId = new AtomicInteger();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFormat(PixelFormat.RGBA_8888);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SharedPreferences sp = getSharedPreferences("tvlink", MODE_PRIVATE);
        code = sp.getString("paircode", Net.DEFAULT_CODE);
        lvlFit = sp.getInt("l_fit", 10);
        lvlBri = sp.getInt("l_bri", 5);
        lvlCon = sp.getInt("l_con", 5);
        lvlSat = sp.getInt("l_sat", 5);
        lvlTxt = sp.getInt("l_txt", 6);
        lvlSha = sp.getInt("l_sha", 7);
        lvlGam = sp.getInt("l_gam", 5);
        if (!sp.getBoolean("vdim6", false)) { sp.edit().remove("l_vdim").putBoolean("vdim6", true).apply(); }
        lvlVdim = sp.getInt("l_vdim", 6);
        panelOff = sp.getBoolean("v_paneloff", false);
        glass = sp.getInt("l_glass", 1);
        liveMode = sp.getInt("l_live", 0);
        buildUi();
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            @Override public void onSystemUiVisibilityChange(int v) {
                if ((v & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                    root.postDelayed(new Runnable() { @Override public void run() { goImmersive(); } }, 1200);
                }
            }
        });
        applyGlass();
        applyLiveMode();
        applyLevels();
        askOverlay();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            mlock = wm.createMulticastLock("tvlink");
            mlock.acquire();
            wlock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvlink");
            wlock.setReferenceCounted(false);
            wlock.acquire();
        } catch (Exception ignored) {}
        startServer();
    }

    // إذن ضروري باش التطبيق يتفتح بوحدو مني TV Box كيشعل (Android 10+)
    private void askOverlay() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        inFront = true;
        refreshInfo();
        goImmersive();
    }

    // ملء الشاشة كاملة: كيخبي أزرار النظام (رجوع/الرئيسية/التطبيقات) وشريط الحالة
    private void goImmersive() {
        try { getWindow().getDecorView().setSystemUiVisibility(UI_FLAGS); } catch (Exception ignored) {}
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    @Override
    public boolean onKeyDown(int kc, KeyEvent e) {
        if (video != null && video.getVisibility() == View.VISIBLE && vpanel != null
                && vpanel.getVisibility() != View.VISIBLE
                && (kc == KeyEvent.KEYCODE_MENU || kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER)) {
            showPanel();
            return true;
        }
        return super.onKeyDown(kc, e);
    }

    private static String newCode() {
        return String.format("%06d", new SecureRandom().nextInt(1000000));
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        glassBg = new FrameLayout(this);
        glassBg.addView(blobView(0x77EC4899, 0.34f, Gravity.TOP | Gravity.RIGHT, -0.08f, 0.02f));
        glassBg.addView(blobView(0x66F59E0B, 0.28f, Gravity.CENTER_VERTICAL | Gravity.LEFT, -0.09f, 0f));
        glassBg.addView(blobView(0x7706B6D4, 0.32f, Gravity.BOTTOM | Gravity.RIGHT, -0.07f, 0.04f));
        glassFrame = new View(this);
        glassFrame.setBackground(glassDrawable(0xFFFFFF, 0x55, 0x30, 0xCCFFFFFF, 30));
        glassBg.addView(glassFrame, new FrameLayout.LayoutParams(-1, -1));
        root.addView(glassBg, new FrameLayout.LayoutParams(-1, -1));

        fillView = new FillVideoView(this);
        video = fillView;
        fillView.setZOrderMediaOverlay(true);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.setVisibility(View.GONE);

        dimView = new View(this);
        dimView.setBackgroundColor(Color.BLACK);
        root.addView(dimView, new FrameLayout.LayoutParams(-1, -1));
        dimView.setVisibility(View.GONE);

        image = new FillImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        image.setVisibility(View.GONE);

        liveView = new ImageView(this);
        liveView.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(liveView, new FrameLayout.LayoutParams(-1, -1));
        liveView.setVisibility(View.GONE);

        multi = new LinearLayout(this);
        multi.setOrientation(LinearLayout.HORIZONTAL);
        multi.setBackgroundColor(Color.parseColor("#444444"));
        root.addView(multi, new FrameLayout.LayoutParams(-1, -1));
        multi.setVisibility(View.GONE);

        try {
            web = new WebView(this);
            WebSettings ws = web.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            web.setWebViewClient(new WebViewClient());
            root.addView(web, new FrameLayout.LayoutParams(-1, -1));
            web.setVisibility(View.GONE);
        } catch (Throwable t) {
            web = null;
        }

        idle = new LinearLayout(this);
        idle.setOrientation(LinearLayout.VERTICAL);
        idle.setGravity(Gravity.CENTER);

        info = new TextView(this);
        info.setTextColor(Color.WHITE);
        info.setTextSize(28);
        info.setGravity(Gravity.CENTER);

        codeView = new TextView(this);
        codeView.setTextColor(Color.parseColor("#4FC3F7"));
        codeView.setTextSize(64);
        codeView.setGravity(Gravity.CENTER);

        Button bNew = new Button(this);
        bNew.setText("كود عشوائي جديد");
        bNew.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                code = newCode();
                getSharedPreferences("tvlink", MODE_PRIVATE).edit().putString("paircode", code).apply();
                refreshInfo();
            }
        });
        Button bDef = new Button(this);
        bDef.setText("الكود الثابت " + Net.DEFAULT_CODE);
        bDef.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                code = Net.DEFAULT_CODE;
                getSharedPreferences("tvlink", MODE_PRIVATE).edit().putString("paircode", code).apply();
                refreshInfo();
            }
        });
        Button bMode = new Button(this);
        bMode.setText("تغيير الوضع");
        bMode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                getSharedPreferences("tvlink", MODE_PRIVATE).edit().remove("mode").apply();
                startActivity(new Intent(ReceiverActivity.this, MainActivity.class).putExtra("choose", true));
                finish();
            }
        });
        idle.addView(info);
        idle.addView(codeView);
        idle.addView(bNew);
        idle.addView(bDef);
        idle.addView(bMode);
        root.addView(idle, new FrameLayout.LayoutParams(-1, -1));
        buildVideoPanel();
        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (video.getVisibility() != View.VISIBLE) return;
                if (vpanel.getVisibility() == View.VISIBLE) { vpanel.setVisibility(View.GONE); root.removeCallbacks(panelHider); }
                else showPanel();
            }
        });
        setContentView(root);
        bNew.requestFocus();
    }

    // ---------------- لوحة ضبط الفيديو (كتبان وحدها فوق الفيديو) ----------------
    private TextView pTv(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        return t;
    }

    private Button pBtn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(20);
        b.setMinWidth(dpx(56));
        b.setMinHeight(dpx(48));
        return b;
    }

    private LinearLayout pRow(String label, final String key) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dpx(4), 0, dpx(4));
        row.addView(pTv(label, 16), new LinearLayout.LayoutParams(0, -2, 1f));
        Button m = pBtn("\u2212");
        Button p = pBtn("+");
        TextView val = pTv("", 18);
        val.setGravity(Gravity.CENTER);
        val.setMinWidth(dpx(60));
        if ("vdim".equals(key)) vpDimVal = val; else vpFitVal = val;
        m.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pStep(key, -1); } });
        p.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pStep(key, 1); } });
        row.addView(m);
        row.addView(val);
        row.addView(p);
        return row;
    }

    private void pStep(String key, int d) {
        levelCmd(key + ":" + ((("vdim".equals(key)) ? lvlVdim : lvlFit) + d));
        refreshPanel();
        root.removeCallbacks(panelHider);
        root.postDelayed(panelHider, 10000);
    }

    private void refreshPanel() {
        if (vpDimVal != null) vpDimVal.setText(lvlVdim + "/10");
        if (vpFitVal != null) vpFitVal.setText(lvlFit + "/10");
    }

    private void buildVideoPanel() {
        vpanel = new LinearLayout(this);
        vpanel.setOrientation(LinearLayout.VERTICAL);
        vpanel.setBackground(glassDrawable(0x000000, 0xC8, 0xC8, 0x88FFFFFF, 18));
        vpanel.setPadding(dpx(16), dpx(10), dpx(16), dpx(12));
        vpanel.setClickable(true);
        TextView title = pTv("\uD83C\uDFA5 ضبط الفيديو (كيتحفظ تلقائيا)", 17);
        title.setGravity(Gravity.CENTER);
        vpanel.addView(title);
        vpanel.addView(pRow("\uD83C\uDF13 تعتيم البياض", "vdim"));
        vpanel.addView(pRow("\uD83D\uDCD0 الحجم", "fit"));
        LinearLayout br = new LinearLayout(this);
        br.setOrientation(LinearLayout.HORIZONTAL);
        Button def = pBtn("\u21BA افتراضي");
        def.setTextSize(15);
        def.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { levelCmd("vdim:6"); levelCmd("fit:10"); refreshPanel(); }
        });
        Button ok = pBtn("\u2713 حفظ وإخفاء");
        ok.setTextSize(15);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                panelOff = true;
                getSharedPreferences("tvlink", MODE_PRIVATE).edit().putBoolean("v_paneloff", true).apply();
                vpanel.setVisibility(View.GONE);
                root.removeCallbacks(panelHider);
                Toast.makeText(ReceiverActivity.this, "تحفظات الإعدادات. اضغط على الشاشة باش تبان اللوحة", Toast.LENGTH_LONG).show();
            }
        });
        br.addView(def, new LinearLayout.LayoutParams(0, -2, 1f));
        br.addView(ok, new LinearLayout.LayoutParams(0, -2, 1f));
        vpanel.addView(br);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dpx(340), -2, Gravity.TOP | Gravity.LEFT);
        lp.setMargins(dpx(20), dpx(20), dpx(20), dpx(20));
        root.addView(vpanel, lp);
        vpanel.setVisibility(View.GONE);
    }

    private void showPanel() {
        if (vpanel == null) return;
        refreshPanel();
        vpanel.setVisibility(View.VISIBLE);
        root.removeCallbacks(panelHider);
        root.postDelayed(panelHider, 10000);
    }

    // كيتفعل ملي كيبدا الفيديو: ملء الشاشة، بلا إطار، تعتيم البياض، واللوحة كتبان وحدها
    private void enterVideoMode() {
        if (glassBg != null) glassBg.setVisibility(View.GONE);
        root.setBackgroundColor(Color.BLACK);
        applyLevels();
        goImmersive();
        if (!panelOff) showPanel();
    }

    private void applyDim() {
        if (dimView == null) return;
        boolean on = video != null && video.getVisibility() == View.VISIBLE && lvlVdim > 0;
        dimView.setAlpha(0.06f * lvlVdim);
        dimView.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void refreshInfo() {
        info.setText("TV Link\n\nالكود ديال هاد TV Box:");
        codeView.setText(code);
        hintView().setText("IP: " + localIp() + "\nربط الهاتف بنفس الواي فاي ثم دخل الكود");
    }

    private TextView hint;
    private TextView hintView() {
        if (hint == null) {
            hint = new TextView(this);
            hint.setTextColor(glass == 1 ? Color.WHITE : Color.LTGRAY);
            hint.setTextSize(22);
            hint.setGravity(Gravity.CENTER);
            idle.addView(hint, 2);
        }
        return hint;
    }

    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "؟";
    }

    // ---------------- server ----------------
    private void startServer() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    server = new ServerSocket();
                    server.setReuseAddress(true);
                    server.setReceiveBufferSize(1 << 18);
                    server.bind(new InetSocketAddress(Net.HTTP_PORT));
                    while (running) {
                        final Socket s = server.accept();
                        new Thread(new Runnable() {
                            @Override public void run() { handle(s); }
                        }).start();
                    }
                } catch (Exception ignored) {}
            }
        }).start();

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    udp = new DatagramSocket(null);
                    udp.setReuseAddress(true);
                    udp.bind(new InetSocketAddress(Net.UDP_PORT));
                    udp.setBroadcast(true);
                    byte[] buf = new byte[256];
                    while (running) {
                        DatagramPacket pk = new DatagramPacket(buf, buf.length);
                        udp.receive(pk);
                        String m = new String(pk.getData(), 0, pk.getLength()).trim();
                        if (m.equals("TVLINK?" + code)) {
                            byte[] r = "TVLINK!".getBytes();
                            udp.send(new DatagramPacket(r, r.length, pk.getAddress(), pk.getPort()));
                        }
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bo.write(c);
        }
        if (c == -1 && bo.size() == 0) return null;
        return bo.toString("UTF-8");
    }

    private static void reply(OutputStream out, int status, String body) throws Exception {
        byte[] b = body.getBytes("UTF-8");
        String h = "HTTP/1.1 " + status + (status == 200 ? " OK" : " ERR") + "\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }


    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            OutputStream out = s.getOutputStream();
            String line = readLine(in);
            if (line == null) return;
            String[] rl = line.split(" ");
            long len = 0;
            String c = "";
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int i = h.indexOf(':');
                if (i < 0) continue;
                String k = h.substring(0, i).trim().toLowerCase();
                String v = h.substring(i + 1).trim();
                if (k.equals("content-length")) len = Long.parseLong(v);
                else if (k.equals("x-code")) c = v;
            }
            if (!c.equals(code)) { reply(out, 403, "bad code"); return; }
            Uri u = Uri.parse("http://x" + rl[1]);
            String p = u.getPath();
            if ("/ping".equals(p)) {
                reply(out, 200, "ok");
            } else if ("/ctl".equals(p)) {
                final String cmd = u.getQueryParameter("cmd");
                runOnUiThread(new Runnable() {
                    @Override public void run() { control(cmd); }
                });
                reply(out, 200, "ok");
            } else if ("/open".equals(p)) {
                final String app = u.getQueryParameter("app");
                final String q = u.getQueryParameter("q");
                runOnUiThread(new Runnable() {
                    @Override public void run() { openApp(app, q); }
                });
                reply(out, 200, "ok");
            } else if ("/stream".equals(p)) {
                s.setSoTimeout(0);
                s.setKeepAlive(true);
                streamLoop(in);
            } else if ("/link".equals(p)) {
                final String link = u.getQueryParameter("u");
                runOnUiThread(new Runnable() {
                    @Override public void run() { showLink(link); }
                });
                reply(out, 200, "ok");
            } else if ("/send".equals(p)) {
                String name = u.getQueryParameter("name");
                if (name == null) name = "file";
                name = name.replaceAll("[^A-Za-z0-9._-]", "_");
                final String type = u.getQueryParameter("type");
                final boolean exam = "1".equals(u.getQueryParameter("exam")) && !"video".equals(type);
                File dir = new File(getCacheDir(), "in");
                dir.mkdirs();
                final File f = new File(dir, System.currentTimeMillis() + "_" + name);
                java.io.OutputStream fo = new java.io.BufferedOutputStream(new FileOutputStream(f), 1 << 18);
                byte[] buf = new byte[1 << 18];
                long left = len;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    fo.write(buf, 0, n);
                    left -= n;
                }
                fo.close();
                if (left > 0) { f.delete(); reply(out, 400, "incomplete"); return; }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (exam) applyExamLevels();
                        examNext = exam;
                        show(f, type);
                    }
                });
                reply(out, 200, "ok");
            } else if ("/multi".equals(p)) {
                String name = u.getQueryParameter("name");
                if (name == null) name = "file";
                name = name.replaceAll("[^A-Za-z0-9._-]", "_");
                final String type = u.getQueryParameter("type");
                int sl = 0, tt = 1;
                try { sl = Integer.parseInt(u.getQueryParameter("slot")); } catch (Exception ignored) {}
                try { tt = Integer.parseInt(u.getQueryParameter("total")); } catch (Exception ignored) {}
                final int slot = Math.max(0, Math.min(2, sl));
                final int total = Math.max(1, Math.min(3, tt));
                File dir = new File(getCacheDir(), "multi");
                dir.mkdirs();
                if (slot == 0) {
                    File[] oldf = dir.listFiles();
                    if (oldf != null) for (File o : oldf) o.delete();
                }
                final File f = new File(dir, slot + "_" + System.currentTimeMillis() + "_" + name);
                java.io.OutputStream fo = new java.io.BufferedOutputStream(new FileOutputStream(f), 1 << 18);
                byte[] buf = new byte[1 << 18];
                long left = len;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    fo.write(buf, 0, n);
                    left -= n;
                }
                fo.close();
                if (left > 0) { f.delete(); reply(out, 400, "incomplete"); return; }
                runOnUiThread(new Runnable() {
                    @Override public void run() { multiAdd(slot, total, f, type); }
                });
                reply(out, 200, "ok");
            } else if ("/playurl".equals(p)) {
                final String pu = u.getQueryParameter("u");
                if (pu == null || !pu.startsWith("http://")) { reply(out, 400, "bad url"); return; }
                runOnUiThread(new Runnable() {
                    @Override public void run() { playUrl(pu); }
                });
                reply(out, 200, "ok");
            } else {
                reply(out, 404, "?");
            }
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    // ---------------- display ----------------
    private void show(File f, String type) {
        killStream();
        front();
        stopMedia();
        resetZoom();
        curFile = f;
        curType = type;
        examFull = examNext;
        examNext = false;
        if (examFull && glassBg != null) glassBg.setVisibility(View.GONE);
        if (examFull) applyLevels();
        image.live = false;
        image.setMode(FillImageView.FIT);
        File[] old = f.getParentFile().listFiles();
        if (old != null) for (File o : old) if (!o.equals(f)) o.delete();
        idle.setVisibility(View.GONE);
        showing = true;
        try {
            if ("pdf".equals(type)) {
                pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                pdf = new PdfRenderer(pfd);
                page = 0;
                renderPage();
            } else if ("image".equals(type)) {
                final File imgF = f;
                image.setVisibility(View.VISIBLE);
                new Thread(new Runnable() {
                    @Override public void run() {
                        final Bitmap bm = decode(imgF);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (curFile == imgF) image.setImageBitmap(bm);
                            }
                        });
                    }
                }).start();
            } else {
                video.setVisibility(View.VISIBLE);
                enterVideoMode();
                video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override public void onPrepared(MediaPlayer mp) {
                        if (mp.getVideoWidth() <= 0 || mp.getVideoHeight() <= 0) {
                            stopMedia();
                            hintView().setText("❌ الصورة ما مدعومةش فـ TV Box (غالباً VP9 / AV1 / WebM)\nنزل الفيديو MP4 (H.264) بجودة 720p أو أقل");
                            return;
                        }
                        try { mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT); } catch (Throwable ignored) {}
                        video.start(); applyBoost();
                        Toast.makeText(ReceiverActivity.this, "▶ " + mp.getVideoWidth() + "×" + mp.getVideoHeight(), Toast.LENGTH_SHORT).show();
                    }
                });
                video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                        stopMedia();
                        hintView().setText("❌ الفيديو ما تقراش (" + what + "/" + extra + ")\nصيغة أو ترميز غير مدعوم في TV Box");
                        return true;
                    }
                });
                video.setVideoPath(f.getAbsolutePath());
            }
        } catch (Exception e) {
            stopMedia();
            hintView().setText("❌ ما قدرتش نعرض هاد الملف");
        }
    }

    // فيديو كيتشغل مباشرة من الهاتف (بث بالشبكة المحلية، بلا نسخ فـ TV Box، وبلا انتظار)
    private void playUrl(final String url) {
        killStream();
        front();
        stopMedia();
        resetZoom();
        curFile = null;
        curType = "video";
        image.live = false;
        image.setMode(FillImageView.FIT);
        idle.setVisibility(View.GONE);
        showing = true;
        try {
            video.setVisibility(View.VISIBLE);
            enterVideoMode();
            video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) {
                    if (mp.getVideoWidth() <= 0 || mp.getVideoHeight() <= 0) {
                        stopMedia();
                        hintView().setText("❌ الصورة ما مدعومةش فـ TV Box (غالباً VP9 / AV1 / WebM)\nنزل الفيديو MP4 (H.264) بجودة 720p أو أقل");
                        return;
                    }
                    try { mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT); } catch (Throwable ignored) {}
                    video.start(); applyBoost();
                    Toast.makeText(ReceiverActivity.this, "▶ " + mp.getVideoWidth() + "×" + mp.getVideoHeight(), Toast.LENGTH_SHORT).show();
                }
            });
            video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    stopMedia();
                    hintView().setText("❌ الفيديو ما تقراش (" + what + "/" + extra + ")\nتأكد أن الهاتف مشغل والتطبيق ما تقتلش، والفيديو MP4 (H.264)");
                    return true;
                }
            });
            video.setVideoURI(Uri.parse(url));
        } catch (Exception e) {
            stopMedia();
            hintView().setText("❌ ما قدرتش نشغل الفيديو من الهاتف");
        }
    }

    private void renderPage() {
        if (pdf == null) return;
        PdfRenderer.Page pg = pdf.openPage(page);
        int rh = root.getHeight() > 0 ? root.getHeight() : 1080;
        int target = Math.min((int) (rh * Math.max(1f, zoom)), quality == 2 ? 3000 : 1400);
        int ss = Math.max(target, Math.min(target * 2, quality == 2 ? 3200 : 1400));
        float scale = (float) ss / pg.getHeight();
        int w = Math.max(1, (int) (pg.getWidth() * scale));
        Bitmap bm = Bitmap.createBitmap(w, ss, Bitmap.Config.ARGB_8888);
        bm.eraseColor(Color.WHITE);
        pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        pg.close();
        if (ss != target) {
            // تصغير بدقة (2x -> 1x) باش الكتابة تبقى غليظة وواضحة بلا تقطيع
            Bitmap sm = Bitmap.createScaledBitmap(bm, Math.max(1, (int) ((long) w * target / ss)), target, true);
            if (sm != bm) bm.recycle();
            bm = sm;
        }
        bm = thicken(bm);
        image.setImageBitmap(bm);
        image.setVisibility(View.VISIBLE);
    }

    private Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1;
        int cap = quality == 2 ? 4096 : 1920;
        while (o.outWidth / s > cap || o.outHeight / s > cap) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o);
        int deg = 0;
        try {
            int ori = new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            if (ori == 6) deg = 90; else if (ori == 3) deg = 180; else if (ori == 8) deg = 270;
        } catch (Exception ignored) {}
        if (deg != 0 && bm != null) {
            Matrix m = new Matrix();
            m.postRotate(deg);
            bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
        }
        return thicken(sharpen(fitBitmap(bm)));
    }

    // غلظة الكتابة: كتوسع الخطوط الداكنة بـ 1 بيكسل (erode) بلا ما تبدل ألوان الصورة
    private static int lum(int c) { return 3 * ((c >> 16) & 0xFF) + 6 * ((c >> 8) & 0xFF) + (c & 0xFF); }

    private static void erodePass(int[] a, int[] b, int w, int h, boolean horiz, float f) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int o = a[i], best = o, bl = lum(o);
                int n1 = horiz ? (x > 0 ? i - 1 : -1) : (y > 0 ? i - w : -1);
                int n2 = horiz ? (x < w - 1 ? i + 1 : -1) : (y < h - 1 ? i + w : -1);
                if (n1 >= 0) { int l = lum(a[n1]); if (l < bl) { bl = l; best = a[n1]; } }
                if (n2 >= 0) { int l = lum(a[n2]); if (l < bl) { bl = l; best = a[n2]; } }
                if (best == o || f >= 1f) { b[i] = best; continue; }
                int r = (int) (((o >> 16) & 0xFF) + (((best >> 16) & 0xFF) - ((o >> 16) & 0xFF)) * f);
                int g = (int) (((o >> 8) & 0xFF) + (((best >> 8) & 0xFF) - ((o >> 8) & 0xFF)) * f);
                int bb = (int) ((o & 0xFF) + ((best & 0xFF) - (o & 0xFF)) * f);
                b[i] = 0xFF000000 | (r << 16) | (g << 8) | bb;
            }
        }
    }

    private Bitmap thicken(Bitmap bm) {
        if (bm == null || lvlTxt <= 0) return bm;
        try {
            int w = bm.getWidth(), h = bm.getHeight();
            int[] a = new int[w * h];
            int[] b = new int[w * h];
            bm.getPixels(a, 0, w, 0, 0, w, h);
            int passes = lvlTxt > 5 ? 2 : 1;
            for (int p = 0; p < passes; p++) {
                float f = (passes == 2 && p == 0) ? 1f : (lvlTxt > 5 ? (lvlTxt - 5) / 5f : lvlTxt / 5f);
                erodePass(a, b, w, h, true, f);
                erodePass(b, a, w, h, false, f);
            }
            Bitmap res = Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888);
            if (res != bm) bm.recycle();
            return res;
        } catch (Throwable t) {
            return bm;
        }
    }

    // حدة الصورة (للصور الملتقطة بالهاتف: امتحانات، فروض)
    private RenderScript rsCache;
    private Bitmap sharpen(Bitmap bm) {
        if (bm == null || lvlSha <= 0) return bm;
        try {
            float a = lvlSha * 0.15f;
            if (rsCache == null) rsCache = RenderScript.create(getApplicationContext());
            RenderScript rs = rsCache;
            Allocation in = Allocation.createFromBitmap(rs, bm);
            Allocation out = Allocation.createTyped(rs, in.getType());
            ScriptIntrinsicConvolve3x3 sc = ScriptIntrinsicConvolve3x3.create(rs, Element.U8_4(rs));
            sc.setCoefficients(new float[]{0, -a, 0, -a, 1f + 4f * a, -a, 0, -a, 0});
            sc.setInput(in);
            sc.forEach(out);
            Bitmap res = Bitmap.createBitmap(bm.getWidth(), bm.getHeight(), Bitmap.Config.ARGB_8888);
            out.copyTo(res);
            in.destroy(); out.destroy(); sc.destroy();
            return res;
        } catch (Throwable t) {
            return bm;
        }
    }

    private Bitmap fitBitmap(Bitmap bm) {
        if (bm == null || root.getWidth() == 0 || root.getHeight() == 0) return bm;
        float s = Math.min(root.getWidth() * 1.6f / bm.getWidth(), root.getHeight() * 1.6f / bm.getHeight());
        if (s >= 1f) return bm;
        Bitmap o = Bitmap.createScaledBitmap(bm, Math.max(1, (int) (bm.getWidth() * s)),
                Math.max(1, (int) (bm.getHeight() * s)), true);
        if (o != bm) bm.recycle();
        return o;
    }

    // إعدادات الامتحان (نفس زر "📄 امتحان"): الحجم كامل، تباين 6، غلظة الكتابة 8، حدة 8
    private void applyExamLevels() {
        lvlFit = 10; lvlBri = 5; lvlCon = 6; lvlSat = 5; lvlTxt = 8; lvlSha = 8;
        getSharedPreferences("tvlink", MODE_PRIVATE).edit()
                .putInt("l_fit", 10).putInt("l_bri", 5).putInt("l_con", 6)
                .putInt("l_sat", 5).putInt("l_txt", 8).putInt("l_sha", 8).apply();
    }

    private void stopMedia() {
        examFull = false;
        try { resetZoom(); } catch (Exception ignored) {}
        try { video.stopPlayback(); } catch (Exception ignored) {}
        video.setVisibility(View.GONE);
        image.setImageDrawable(null);
        if (liveView != null) { liveView.setImageDrawable(null); liveView.setVisibility(View.GONE); }
        image.setVisibility(View.GONE);
        try { if (pdf != null) pdf.close(); } catch (Exception ignored) {}
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        pdf = null;
        pfd = null;
        curFile = null;
        curType = null;
        if (multi != null) { multi.removeAllViews(); multi.setVisibility(View.GONE); }
        multiFiles = null;
        multiTypes = null;
        multiGot = 0;
        if (web != null) {
            try { web.loadUrl("about:blank"); } catch (Exception ignored) {}
            web.setVisibility(View.GONE);
        }
        showing = false;
        if (dimView != null) dimView.setVisibility(View.GONE);
        if (vpanel != null) { vpanel.setVisibility(View.GONE); root.removeCallbacks(panelHider); }
        applyGlass();
        idle.setVisibility(View.VISIBLE);
    }

    private void applyZoom() {
        if (zoom <= 1f) { zoom = 1f; panX = 0f; panY = 0f; }
        float eff = zoom * fit;
        float mx = Math.max(0f, (eff - 1f) * root.getWidth() / 2f);
        float my = Math.max(0f, (eff - 1f) * root.getHeight() / 2f);
        panX = Math.max(-mx, Math.min(mx, panX));
        panY = Math.max(-my, Math.min(my, panY));
        image.setScaleX(eff);
        image.setScaleY(eff);
        image.setTranslationX(panX);
        image.setTranslationY(panY);
        if (glassFrame != null) {
            float gs = Math.min(1f, fit + 0.02f);
            glassFrame.setScaleX(gs);
            glassFrame.setScaleY(gs);
        }
        float vf = 0.60f + 0.40f * (lvlFit - 1) / 9f;   // الفيديو ما كيتأثرش بالإطار الزجاجي
        if (video != null) { video.setScaleX(vf); video.setScaleY(vf); }
        if (dimView != null) { dimView.setScaleX(vf); dimView.setScaleY(vf); }
        if (liveView != null) { liveView.setScaleX(fit); liveView.setScaleY(fit); }
        if (multi != null) {
            multi.setScaleX(eff);
            multi.setScaleY(eff);
            multi.setTranslationX(panX);
            multi.setTranslationY(panY);
        }
    }

    // ---------------- مستويات الحجم / الألوان / الكتابة ----------------
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void applyLevels() {
        fit = 0.60f + 0.40f * (lvlFit - 1) / 9f;
        if (glass == 1 && !examFull) fit = Math.min(fit, 0.95f);   // يبقى هامش صغير باش الإطار الزجاجي يبان
        boolean neutral = lvlBri == 5 && lvlCon == 5 && lvlSat == 5;
        if (neutral) {
            image.setLayerType(View.LAYER_TYPE_NONE, null);
            multi.setLayerType(View.LAYER_TYPE_NONE, null);
            if (liveView != null) liveView.setLayerType(View.LAYER_TYPE_NONE, null);
        } else {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0.5f + 0.1f * lvlSat);
            float c = lvlCon <= 5 ? 0.5f + 0.1f * lvlCon : 1f + 0.25f * (lvlCon - 5);
            float t = 128f * (1f - c) + (lvlBri - 5) * 10f;
            cm.postConcat(new ColorMatrix(new float[]{
                    c, 0, 0, 0, t,
                    0, c, 0, 0, t,
                    0, 0, c, 0, t,
                    0, 0, 0, 1, 0}));
            Paint p = new Paint();
            p.setColorFilter(new ColorMatrixColorFilter(cm));
            image.setLayerType(View.LAYER_TYPE_HARDWARE, p);
            multi.setLayerType(View.LAYER_TYPE_HARDWARE, p);
            if (liveView != null) liveView.setLayerType(View.LAYER_TYPE_NONE, null); // عرض الهاتف بألوانه الطبيعية بلا فلتر
        }
        applyZoom();
        applyDim();
    }

    private int dpx(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable glassDrawable(int tint, int a1, int a2, int stroke, int radiusDp) {
        int rgb = tint & 0xFFFFFF;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(a1 << 24) | rgb, (a2 << 24) | rgb});
        g.setCornerRadius(dpx(radiusDp));
        g.setStroke(dpx(2), stroke);
        return g;
    }

    private View blobView(int color, float frac, int gravity, float mx, float my) {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int size = Math.round(sw * frac);
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, gravity);
        lp.setMargins(Math.round(sw * mx), Math.round(sw * my), Math.round(sw * mx), Math.round(sw * my));
        v.setLayoutParams(lp);
        return v;
    }

    // تفعيل / إلغاء المظهر الزجاجي (خلفية متدرجة + إطار شفاف + بطاقة زجاجية للشاشة الرئيسية)
    private void applyGlass() {
        boolean on = glass == 1;
        if (glassBg != null) glassBg.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF1E1B4B, 0xFF312E81, 0xFF0F766E}));
        } else {
            root.setBackgroundColor(Color.BLACK);
        }
        if (multi != null) multi.setBackgroundColor(on ? Color.TRANSPARENT : Color.parseColor("#444444"));
        if (idle != null) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
            int m = on ? dpx(36) : 0;
            lp.setMargins(m, m, m, m);
            idle.setLayoutParams(lp);
            idle.setBackground(on ? glassDrawable(0xFFFFFF, 0x40, 0x18, 0x88FFFFFF, 28) : null);
        }
        if (hint != null) hint.setTextColor(on ? Color.WHITE : Color.LTGRAY);
        applyLevels();
    }

    private void levelCmd(String cmd) {
        String[] kv = cmd.split(":");
        if (kv.length < 2) return;
        int n;
        try { n = Integer.parseInt(kv[1].trim()); } catch (Exception e) { return; }
        SharedPreferences.Editor ed = getSharedPreferences("tvlink", MODE_PRIVATE).edit();
        String k = kv[0];
        if ("fit".equals(k)) { lvlFit = clamp(n, 1, 10); ed.putInt("l_fit", lvlFit); }
        else if ("vdim".equals(k)) { lvlVdim = clamp(n, 0, 10); ed.putInt("l_vdim", lvlVdim); }
        else if ("bri".equals(k)) { lvlBri = clamp(n, 1, 10); ed.putInt("l_bri", lvlBri); }
        else if ("con".equals(k)) { lvlCon = clamp(n, 1, 10); ed.putInt("l_con", lvlCon); }
        else if ("sat".equals(k)) { lvlSat = clamp(n, 1, 10); ed.putInt("l_sat", lvlSat); }
        else if ("txt".equals(k)) {
            int old = lvlTxt;
            lvlTxt = clamp(n, 0, 10);
            ed.putInt("l_txt", lvlTxt);
            ed.apply();
            if (old != lvlTxt) reload();
            return;
        }
        else if ("glass".equals(k)) {
            glass = n > 0 ? 1 : 0;
            ed.putInt("l_glass", glass);
            ed.apply();
            runOnUiThread(new Runnable() { @Override public void run() { applyGlass(); } });
            return;
        }
        else if ("gam".equals(k)) { lvlGam = clamp(n, 0, 10); ed.putInt("l_gam", lvlGam); ed.apply(); return; }
        else if ("sha".equals(k)) {
            int old = lvlSha;
            lvlSha = clamp(n, 0, 10);
            ed.putInt("l_sha", lvlSha);
            ed.apply();
            if (old != lvlSha && "image".equals(curType)) reload();
            return;
        }
        else return;
        ed.apply();
        applyLevels();
    }

    private void applyLiveMode() {
        if (liveView == null) return;
        liveView.setScaleType(liveMode == 0 ? ImageView.ScaleType.FIT_XY
                : liveMode == 1 ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
    }

    private static boolean near(Rect a, Rect b) {
        return Math.abs(a.left - b.left) < 12 && Math.abs(a.top - b.top) < 12
                && Math.abs(a.right - b.right) < 12 && Math.abs(a.bottom - b.bottom) < 12;
    }

    // كيقلب على المساحة اللي فيها محتوى حقيقي (بلا الحواف السوداء)
    private static Rect contentBounds(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int sx = Math.max(1, w / 80), sy = Math.max(1, h / 80);
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y += sy) {
            for (int x = 0; x < w; x += sx) {
                int p = b.getPixel(x, y);
                int lum = ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF);
                if (lum > 60) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return null;
        int l = Math.max(0, minX - 2 * sx), t = Math.max(0, minY - 2 * sy);
        int rr = Math.min(w, maxX + 2 * sx), bb = Math.min(h, maxY + 2 * sy);
        if (rr - l < w * 0.3f || bb - t < h * 0.3f) return null;
        return new Rect(l, t, rr, bb);
    }

    // كيحيد الحواف السوداء من إطار عرض الشاشة باش المحتوى يملأ الداتا شو
    private Bitmap autoCrop(Bitmap b) {
        try {
            int w = b.getWidth(), h = b.getHeight();
            if (w != cropW || h != cropH) { cropW = w; cropH = h; cropRect = null; candRect = null; candCount = 0; frameNo = 0; }
            if (cropRect == null || (frameNo++ % 10) == 0) {
                Rect c = contentBounds(b);
                if (c != null) {
                    if (cropRect == null) {
                        cropRect = c;
                    } else if (!cropRect.contains(c)) {
                        cropRect.union(c);
                        candRect = null; candCount = 0;
                    } else if (!near(cropRect, c)) {
                        if (candRect != null && near(candRect, c)) {
                            if (++candCount >= 3) { cropRect = new Rect(c); candRect = null; candCount = 0; }
                        } else {
                            candRect = new Rect(c); candCount = 0;
                        }
                    } else {
                        candRect = null; candCount = 0;
                    }
                }
            }
            Rect r = cropRect;
            if (r == null || (r.left <= 0 && r.top <= 0 && r.right >= w && r.bottom >= h)) return b;
            return Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height());
        } catch (Throwable t) {
            return b;
        }
    }

    // اختبار الصوت + إظهار مخرج الصوت الحالي
    private void beep() {
        try {
            setMaxVolume();
            new ToneGenerator(AudioManager.STREAM_MUSIC, 100).startTone(ToneGenerator.TONE_DTMF_5, 2500);
            String out = "؟";
            if (Build.VERSION.SDK_INT >= 23) {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                StringBuilder sb = new StringBuilder();
                for (android.media.AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    int t = d.getType();
                    String n = t == 9 ? "HDMI" : (t == 3 || t == 4) ? "3.5mm" : t == 2 ? "سماعة داخلية"
                            : (t == 7 || t == 8) ? "بلوتوث" : (t == 11 || t == 12) ? "USB" : "نوع " + t;
                    if (sb.indexOf(n) < 0) { if (sb.length() > 0) sb.append(" + "); sb.append(n); }
                }
                if (sb.length() > 0) out = sb.toString();
            }
            Toast.makeText(this, "🔊 اختبار الصوت — المخرج: " + out, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    private void resetZoom() { zoom = 1f; panX = 0f; panY = 0f; applyZoom(); image.refreshQuality(); }

    private void control(String cmd) {
        if (cmd == null) return;
        if (cmd.indexOf(':') > 0) { levelCmd(cmd); return; }
        switch (cmd) {
            case "vpanel":
                if (video.getVisibility() == View.VISIBLE) showPanel();
                break;
            case "beep":
                beep();
                break;
            case "lmode":
                liveMode = (liveMode + 1) % 3;
                getSharedPreferences("tvlink", MODE_PRIVATE).edit().putInt("l_live", liveMode).apply();
                applyLiveMode();
                Toast.makeText(this, liveMode == 0 ? "🖥 ملء الشاشة (تمديد)"
                        : liveMode == 1 ? "🖥 تغطية (قص الحواف)" : "🖥 النسبة الأصلية", Toast.LENGTH_SHORT).show();
                break;
            case "pause":
                if (video.getVisibility() == View.VISIBLE) {
                    if (video.isPlaying()) video.pause(); else video.start();
                }
                break;
            case "stop":
                killStream();
                stopMedia();
                break;
            case "fwd":
                if (video.getVisibility() == View.VISIBLE) video.seekTo(video.getCurrentPosition() + 10000);
                break;
            case "back":
                if (video.getVisibility() == View.VISIBLE) video.seekTo(Math.max(0, video.getCurrentPosition() - 10000));
                break;
            case "qh":
                quality = 2;
                reload();
                break;
            case "ql":
                quality = 1;
                reload();
                break;
            case "vfill":
                if (fillView != null) { fillView.fill = !fillView.fill; fillView.requestLayout(); }
                break;
            case "zin":
                zoom = Math.min(6f, zoom * 1.25f); applyZoom();
                if (pdf != null) renderPage(); else image.refreshQuality();
                break;
            case "zout":
                zoom = zoom / 1.25f; applyZoom();
                if (pdf != null) renderPage(); else image.refreshQuality();
                break;
            case "zreset":
                resetZoom();
                break;
            case "pl":
                panX += root.getWidth() * 0.15f; applyZoom();
                break;
            case "pr":
                panX -= root.getWidth() * 0.15f; applyZoom();
                break;
            case "pu":
                if (multiShown()) { scrollMulti(-root.getHeight() * 0.25f); break; }
                if (!image.scrollContent(-root.getHeight() * 0.25f)) { panY += root.getHeight() * 0.15f; applyZoom(); }
                break;
            case "pd":
                if (multiShown()) { scrollMulti(root.getHeight() * 0.25f); break; }
                if (!image.scrollContent(root.getHeight() * 0.25f)) { panY -= root.getHeight() * 0.15f; applyZoom(); }
                break;
            case "imode":
                if (multiShown()) {
                    for (int i = 0; i < multi.getChildCount(); i++)
                        ((FillImageView) multi.getChildAt(i)).cycleMode();
                } else image.cycleMode();
                break;
            case "volup":
                volumeStep(1);
                break;
            case "voldown":
                volumeStep(-1);
                break;
            case "mute":
                adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE);
                break;
            case "volmax":
                setMaxVolume();
                boostMb = 800;
                applyBoost();
                volToast();
                break;
            case "next":
                if (pdf != null && page < pdf.getPageCount() - 1) { page++; resetZoom(); renderPage(); }
                break;
            case "prev":
                if (pdf != null && page > 0) { page--; resetZoom(); renderPage(); }
                break;
        }
    }

    // ---------------- تقسيم الشاشة (2 أو 3 فروض) ----------------
    private boolean multiShown() { return multi != null && multi.getVisibility() == View.VISIBLE; }

    private void scrollMulti(float d) {
        for (int i = 0; i < multi.getChildCount(); i++)
            ((FillImageView) multi.getChildAt(i)).scrollContent(d);
    }

    private void multiAdd(int slot, int total, File f, String type) {
        if (slot == 0 || multiFiles == null || multiFiles.length != total) {
            killStream();
            front();
            stopMedia();
            resetZoom();
            multiFiles = new File[total];
            multiTypes = new String[total];
            multiGot = 0;
            multiTotal = total;
        }
        if (slot < multiFiles.length) {
            if (multiFiles[slot] == null) multiGot++;
            multiFiles[slot] = f;
            multiTypes[slot] = type;
        }
        if (multiGot >= multiTotal) showMulti();
    }

    private void showMulti() {
        if (multiFiles == null) return;
        multi.removeAllViews();
        int h = root.getHeight() > 0 ? root.getHeight() : 1080;
        for (int i = 0; i < multiFiles.length; i++) {
            FillImageView v = new FillImageView(this);
            v.setBackgroundColor(Color.BLACK);
            Bitmap bm = null;
            try {
                bm = "pdf".equals(multiTypes[i]) ? firstPdfPage(multiFiles[i], h) : decodeMulti(multiFiles[i]);
            } catch (Throwable ignored) {}
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            if (i > 0) lp.leftMargin = 4;
            multi.addView(v, lp);
            v.setMode(FillImageView.FIT);
            if (bm != null) v.setImageBitmap(thicken(bm));
        }
        idle.setVisibility(View.GONE);
        image.setVisibility(View.GONE);
        multi.setVisibility(View.VISIBLE);
        curType = "multi";
        showing = true;
    }

    private Bitmap firstPdfPage(File f, int viewH) throws Exception {
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer r = new PdfRenderer(fd);
        try {
            PdfRenderer.Page pg = r.openPage(0);
            int hh = Math.min((int) (viewH * 1.6f), 2560);
            float sc = (float) hh / pg.getHeight();
            int w = Math.max(1, (int) (pg.getWidth() * sc));
            Bitmap bm = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
            bm.eraseColor(Color.WHITE);
            pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            pg.close();
            return bm;
        } finally {
            try { r.close(); } catch (Exception ignored) {}
            try { fd.close(); } catch (Exception ignored) {}
        }
    }

    private Bitmap decodeMulti(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1;
        while (o.outWidth / s > 2560 || o.outHeight / s > 2560) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o);
        int deg = 0;
        try {
            int ori = new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            if (ori == 6) deg = 90; else if (ori == 3) deg = 180; else if (ori == 8) deg = 270;
        } catch (Exception ignored) {}
        if (deg != 0 && bm != null) {
            Matrix m = new Matrix();
            m.postRotate(deg);
            bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
        }
        return bm;
    }

    // رفع / خفض صوت TV Box: أولا صوت النظام، وملي يوصل للأقصى (ولا كان ثابت عبر HDMI) كيتضخم الصوت بالتطبيق
    private void volumeStep(int dir) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean fixed = Build.VERSION.SDK_INT >= 21 && am.isVolumeFixed();
            if (dir > 0) {
                if (!fixed && cur < max) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, cur + 1, 0);
                    if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == cur)
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0);
                } else {
                    boostMb = Math.min(2000, boostMb + 400);
                }
            } else {
                if (boostMb > 0) {
                    boostMb = Math.max(0, boostMb - 400);
                } else if (!fixed && cur > 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, cur - 1, 0);
                    if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == cur)
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
                }
            }
        } catch (Exception ignored) {}
        applyBoost();
        volToast();
    }

    // تضخيم إضافي للصوت (للفيديو) فوق أقصى صوت النظام
    private void applyBoost() {
        try {
            if (video == null || video.getVisibility() != View.VISIBLE) return;
            int sid = video.getAudioSessionId();
            if (sid == 0) return;
            if (boostMb <= 0 && enh == null) return;   // بلا مؤثر صوتي إلا ما طلبتش تضخيم: كيمنع تقطيع الصوت
            if (enh == null || enhSession != sid) {
                if (enh != null) { try { enh.release(); } catch (Exception ignored) {} }
                enh = new LoudnessEnhancer(sid);
                enhSession = sid;
            }
            enh.setTargetGain(boostMb);
            enh.setEnabled(boostMb > 0);
        } catch (Throwable ignored) {}
    }

    private void volToast() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean fixed = Build.VERSION.SDK_INT >= 21 && am.isVolumeFixed();
            String t = "🔊 " + cur + "/" + max + (boostMb > 0 ? "  +" + (boostMb / 100) + "dB" : "")
                    + (fixed ? "  (صوت ثابت HDMI)" : "");
            Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    // رفع / خفض صوت TV Box من الهاتف
    private void adjustVolume(int dir) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
        } catch (Exception ignored) {}
    }

    private void setMaxVolume() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI);
        } catch (Exception ignored) {}
    }

    private void killStream() { streamId.incrementAndGet(); }

    private boolean inFront = false;
    @Override protected void onPause() { inFront = false; super.onPause(); }

    private void front() {
        if (inFront) return;
        try {
            Intent i = new Intent(this, ReceiverActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    // كيرفع الظلال والألوان الغامقة (الوجوه، اليدين، الأحمر) باش تبان بحال اليوتيوب فالداتا شو
    private Bitmap applyGamma(Bitmap b) {
        int g = lvlGam;
        if (g <= 0 || b == null) return b;
        try {
            if (gamLutFor != g) {
                double gm = 1.0 - 0.05 * g;
                for (int i = 0; i < 256; i++) gamLut[i] = (int) Math.round(255.0 * Math.pow(i / 255.0, gm));
                gamLutFor = g;
            }
            int w = b.getWidth(), h = b.getHeight();
            if (gamPx == null || gamPx.length != w * h) gamPx = new int[w * h];
            int[] px = gamPx;
            b.getPixels(px, 0, w, 0, 0, w, h);
            final int[] L = gamLut;
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                px[i] = 0xFF000000 | (L[(p >> 16) & 0xFF] << 16) | (L[(p >> 8) & 0xFF] << 8) | L[p & 0xFF];
            }
            Bitmap res = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            if (res != b) b.recycle();
            return res;
        } catch (Throwable t) {
            return b;
        }
    }

    // تصغير بدقة لحجم الشاشة (بدل ما يخلي الـ GPU يصغر 2560 -> 1080 فتولي الكتابة مقطعة)
    private Bitmap fitToScreen(Bitmap b) {
        try {
            int rw = root.getWidth(), rh = root.getHeight();
            if (rw == 0 || rh == 0) return b;
            float s = Math.min(1f, Math.max((float) rw / b.getWidth(), (float) rh / b.getHeight()));
            if (s > 0.98f) return b;
            Bitmap o = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)),
                    Math.max(1, Math.round(b.getHeight() * s)), true);
            if (o != b) b.recycle();
            return o;
        } catch (Throwable t) {
            return b;
        }
    }

    private void streamLoop(InputStream in) {
        final int id = streamId.incrementAndGet();
        runOnUiThread(new Runnable() {
            @Override public void run() {
                front();
                stopMedia();
                curType = "stream";
                idle.setVisibility(View.GONE);
                liveView.setVisibility(View.VISIBLE);
                showing = true;
            }
        });
        final AtomicBoolean pending = new AtomicBoolean(false);
        cropRect = null; candRect = null; candCount = 0; cropW = 0; cropH = 0; frameNo = 0;
        try {
            DataInputStream di = new DataInputStream(in);
            byte[] buf = new byte[256 * 1024];
            while (running && streamId.get() == id) {
                int n = di.readInt();
                if (n <= 0 || n > 10000000) break;
                if (buf.length < n) buf = new byte[n];
                di.readFully(buf, 0, n);
                if (pending.get()) continue;
                if (in.available() > 4) continue;   // فرام جديد وصل: نتخطى القديم باش ما يتراكمش التأخير
                BitmapFactory.Options dop = new BitmapFactory.Options();
                dop.inPreferredConfig = Bitmap.Config.ARGB_8888;
                dop.inDither = false;
                if (Build.VERSION.SDK_INT >= 26) {
                    try { dop.inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB); } catch (Throwable ignored) {}
                }
                final Bitmap raw = BitmapFactory.decodeByteArray(buf, 0, n, dop);
                if (raw == null) continue;
                final Bitmap bm = applyGamma(fitToScreen(autoCrop(raw)));
                pending.set(true);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (streamId.get() == id) liveView.setImageBitmap(bm);
                        pending.set(false);
                    }
                });
            }
        } catch (Exception ignored) {}
        // الهاتف تقفل/وقف الإرسال: كنخليو آخر صورة معروضة حتى تخرج يدويا (زر وقف أو رجوع)
    }

    private void openApp(String app, String q) {
        killStream();
        stopMedia();
        boolean fb = "facebook".equals(app);
        boolean hasQ = q != null && !q.trim().isEmpty();
        String url;
        String[] pkgs;
        if (fb) {
            url = hasQ ? "https://www.facebook.com/search/top?q=" + Uri.encode(q) : "https://www.facebook.com";
            pkgs = new String[]{"com.facebook.katana", "com.facebook.lite"};
        } else {
            url = hasQ ? "https://www.youtube.com/results?search_query=" + Uri.encode(q) : "https://www.youtube.com";
            pkgs = new String[]{"com.google.android.youtube.tv", "com.google.android.youtube", "com.liskovsoft.smarttubetv"};
        }
        for (String pk : pkgs) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.setPackage(pk);
                startActivity(i);
                return;
            } catch (Exception ignored) {}
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return;
        } catch (Exception ignored) {}
        if (web != null) {
            idle.setVisibility(View.GONE);
            showing = true;
            web.setVisibility(View.VISIBLE);
            web.loadUrl(url.replace("www.", "m."));
        } else {
            hintView().setText("❌ ما كاينش متصفح في TV Box");
        }
    }

    private void reload() {
        try {
            if ("pdf".equals(curType) && pdf != null) renderPage();
            else if ("image".equals(curType) && curFile != null) image.setImageBitmap(decode(curFile));
            else if ("multi".equals(curType) && multiFiles != null) showMulti();
        } catch (Exception ignored) {}
    }

    private void showLink(String link) {
        if (link == null) return;
        killStream();
        stopMedia();
        String id = null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:youtu\\.be/|v=|shorts/|embed/)([A-Za-z0-9_-]{11})").matcher(link);
        if (m.find()) id = m.group(1);
        String url = id != null ? "https://www.youtube.com/watch?v=" + id : link;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return;
        } catch (ActivityNotFoundException ignored) {}
        if (web != null) {
            idle.setVisibility(View.GONE);
            showing = true;
            web.setVisibility(View.VISIBLE);
            web.loadUrl(id != null ? "https://m.youtube.com/watch?v=" + id : link);
        } else {
            hintView().setText("❌ ما كاينش تطبيق يفتح الرابط في TV Box");
        }
    }

    @Override
    public void onBackPressed() {
        if (showing) { killStream(); stopMedia(); } else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        try { if (udp != null) udp.close(); } catch (Exception ignored) {}
        try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Exception ignored) {}
        try { if (wlock != null && wlock.isHeld()) wlock.release(); } catch (Exception ignored) {}
        stopMedia();
        super.onDestroy();
    }
}
