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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SharedPreferences sp = getSharedPreferences("tvlink", MODE_PRIVATE);
        code = sp.getString("paircode", Net.DEFAULT_CODE);
        buildUi();
        askOverlay();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            mlock = wm.createMulticastLock("tvlink");
            mlock.acquire();
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
        refreshInfo();
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private static String newCode() {
        return String.format("%06d", new SecureRandom().nextInt(1000000));
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        fillView = new FillVideoView(this);
        video = fillView;
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.setVisibility(View.GONE);

        image = new FillImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        image.setVisibility(View.GONE);

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
        setContentView(root);
        bNew.requestFocus();
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
            hint.setTextColor(Color.LTGRAY);
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
                    server = new ServerSocket(Net.HTTP_PORT);
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
                File dir = new File(getCacheDir(), "in");
                dir.mkdirs();
                final File f = new File(dir, System.currentTimeMillis() + "_" + name);
                FileOutputStream fo = new FileOutputStream(f);
                byte[] buf = new byte[65536];
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
                    @Override public void run() { show(f, type); }
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
                FileOutputStream fo = new FileOutputStream(f);
                byte[] buf = new byte[65536];
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
        image.live = false;
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
                image.setImageBitmap(decode(f));
                image.setVisibility(View.VISIBLE);
            } else {
                video.setVisibility(View.VISIBLE);
                video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override public void onPrepared(MediaPlayer mp) { video.start(); applyBoost(); }
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

    private void renderPage() {
        if (pdf == null) return;
        PdfRenderer.Page pg = pdf.openPage(page);
        int h = root.getHeight() > 0 ? root.getHeight() : 1080;
        h = Math.min((int) (h * 2.5f * Math.max(1f, zoom)), quality == 2 ? 4096 : 1600);
        float scale = (float) h / pg.getHeight();
        int w = Math.max(1, (int) (pg.getWidth() * scale));
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bm.eraseColor(Color.WHITE);
        pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        pg.close();
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
        return bm;
    }

    private void stopMedia() {
        try { resetZoom(); } catch (Exception ignored) {}
        try { video.stopPlayback(); } catch (Exception ignored) {}
        video.setVisibility(View.GONE);
        image.setImageDrawable(null);
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
        idle.setVisibility(View.VISIBLE);
    }

    private void applyZoom() {
        if (zoom <= 1f) { zoom = 1f; panX = 0f; panY = 0f; }
        float mx = Math.max(0f, (zoom - 1f) * root.getWidth() / 2f);
        float my = Math.max(0f, (zoom - 1f) * root.getHeight() / 2f);
        panX = Math.max(-mx, Math.min(mx, panX));
        panY = Math.max(-my, Math.min(my, panY));
        image.setScaleX(zoom);
        image.setScaleY(zoom);
        image.setTranslationX(panX);
        image.setTranslationY(panY);
        if (multi != null) {
            multi.setScaleX(zoom);
            multi.setScaleY(zoom);
            multi.setTranslationX(panX);
            multi.setTranslationY(panY);
        }
    }

    private void resetZoom() { zoom = 1f; panX = 0f; panY = 0f; applyZoom(); image.refreshQuality(); }

    private void control(String cmd) {
        if (cmd == null) return;
        switch (cmd) {
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
            if (bm != null) v.setImageBitmap(bm);
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

    private void front() {
        try {
            Intent i = new Intent(this, ReceiverActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    private void streamLoop(InputStream in) {
        final int id = streamId.incrementAndGet();
        runOnUiThread(new Runnable() {
            @Override public void run() {
                front();
                stopMedia();
                curType = "stream";
                image.live = true;
                idle.setVisibility(View.GONE);
                image.setVisibility(View.VISIBLE);
                showing = true;
            }
        });
        final AtomicBoolean pending = new AtomicBoolean(false);
        try {
            DataInputStream di = new DataInputStream(in);
            byte[] buf = new byte[256 * 1024];
            while (running && streamId.get() == id) {
                int n = di.readInt();
                if (n <= 0 || n > 10000000) break;
                if (buf.length < n) buf = new byte[n];
                di.readFully(buf, 0, n);
                if (pending.get()) continue;
                final Bitmap bm = BitmapFactory.decodeByteArray(buf, 0, n);
                if (bm == null) continue;
                pending.set(true);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (streamId.get() == id) image.setImageBitmap(bm);
                        pending.set(false);
                    }
                });
            }
        } catch (Exception ignored) {}
        if (streamId.get() == id) {
            runOnUiThread(new Runnable() {
                @Override public void run() { if (streamId.get() == id) stopMedia(); }
            });
        }
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
        stopMedia();
        super.onDestroy();
    }
}
