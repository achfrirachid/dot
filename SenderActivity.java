package com.tvlink.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class SenderActivity extends Activity {
    private EditText codeF, ipF;
    private TextView status;
    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile String ip;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("tvlink", MODE_PRIVATE);
        ip = sp.getString("ip", null);
        bindWifi();
        buildUi();
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    private void handle(Intent i) {
        if (i != null && Intent.ACTION_SEND.equals(i.getAction())) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) sendUri(u);
        }
    }

    // ---------------- UI ----------------
    private Button btn(String t, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(t);
        bt.setOnClickListener(l);
        return bt;
    }

    private void buildUi() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(32, 48, 32, 32);

        TextView title = new TextView(this);
        title.setText("TV Link 📱 ← 📺");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        l.addView(title);

        codeF = new EditText(this);
        codeF.setHint("كود TV Box (6 أرقام)");
        codeF.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeF.setText(sp.getString("code", ""));
        codeF.setTextSize(24);
        l.addView(codeF);

        ipF = new EditText(this);
        ipF.setHint("IP ديال TV Box (اختياري، غير إلا ما لقاهش)");
        ipF.setInputType(InputType.TYPE_CLASS_PHONE);
        l.addView(ipF);

        l.addView(btn("🔗 اتصل بـ TV Box", new View.OnClickListener() {
            @Override public void onClick(View v) { connect(); }
        }));
        l.addView(btn("📂 اختر فيديو / PDF / صورة", new View.OnClickListener() {
            @Override public void onClick(View v) { pick(); }
        }));

        LinearLayout r1 = new LinearLayout(this);
        r1.addView(weight(btn("⏪ 10ث", ctl("back"))));
        r1.addView(weight(btn("⏯", ctl("pause"))));
        r1.addView(weight(btn("10ث ⏩", ctl("fwd"))));
        l.addView(r1);
        LinearLayout r2 = new LinearLayout(this);
        r2.addView(weight(btn("◀ صفحة", ctl("prev"))));
        r2.addView(weight(btn("⏹ وقف", ctl("stop"))));
        r2.addView(weight(btn("صفحة ▶", ctl("next"))));
        l.addView(r2);

        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(0, 24, 0, 24);
        status.setText(ip != null ? "آخر TV Box: " + ip : "دخل الكود واضغط اتصل");
        l.addView(status);

        l.addView(btn("تغيير الوضع", new View.OnClickListener() {
            @Override public void onClick(View v) {
                sp.edit().remove("mode").apply();
                startActivity(new Intent(SenderActivity.this, MainActivity.class).putExtra("choose", true));
                finish();
            }
        }));

        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        setContentView(sv);
    }

    private Button weight(Button b) {
        b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        return b;
    }

    private View.OnClickListener ctl(final String cmd) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) { sendCmd(cmd); }
        };
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() { @Override public void run() { status.setText(s); } });
    }

    // ---------------- network ----------------
    private void bindWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    cm.bindProcessToNetwork(n);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    private String discover(String code, int timeoutMs) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setBroadcast(true);
            s.setSoTimeout(1000);
            byte[] msg = ("TVLINK?" + code).getBytes();
            List<InetAddress> targets = new ArrayList<InetAddress>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            try {
                DhcpInfo d = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).getDhcpInfo();
                int b = (d.ipAddress & d.netmask) | ~d.netmask;
                targets.add(InetAddress.getByAddress(new byte[]{
                        (byte) b, (byte) (b >> 8), (byte) (b >> 16), (byte) (b >> 24)}));
            } catch (Exception ignored) {}
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[64];
            while (System.currentTimeMillis() < end) {
                for (InetAddress t : targets) {
                    try { s.send(new DatagramPacket(msg, msg.length, t, Net.UDP_PORT)); } catch (Exception ignored) {}
                }
                try {
                    DatagramPacket r = new DatagramPacket(buf, buf.length);
                    s.receive(r);
                    if (new String(r.getData(), 0, r.getLength()).equals("TVLINK!"))
                        return r.getAddress().getHostAddress();
                } catch (SocketTimeoutException ignored) {}
            }
        } catch (Exception ignored) {
        } finally {
            if (s != null) s.close();
        }
        return null;
    }

    private int get(String host, String code, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + Net.HTTP_PORT + path).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(8000);
        c.setRequestProperty("X-Code", code);
        int rc = c.getResponseCode();
        c.disconnect();
        return rc;
    }

    private String code() {
        String c = codeF.getText().toString().trim();
        sp.edit().putString("code", c).apply();
        return c;
    }

    private void connect() {
        final String c = code();
        final String manual = ipF.getText().toString().trim();
        if (c.isEmpty()) { setStatus("دخل الكود أولا"); return; }
        setStatus("كنقلب على TV Box...");
        new Thread(new Runnable() {
            @Override public void run() {
                bindWifi();
                String found = null;
                if (!manual.isEmpty()) {
                    try { if (get(manual, c, "/ping") == 200) found = manual; } catch (Exception ignored) {}
                } else {
                    found = discover(c, 6000);
                }
                if (found != null) {
                    ip = found;
                    sp.edit().putString("ip", found).apply();
                    setStatus("✅ متصل بـ TV Box (" + found + ")");
                } else {
                    setStatus("❌ ما لقيتش TV Box. تأكد: نفس الواي فاي، الكود صحيح، التطبيق مفتوح في TV Box. أو دخل IP.");
                }
            }
        }).start();
    }

    private void sendCmd(final String cmd) {
        final String c = code();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    bindWifi();
                    String host = ip != null ? ip : discover(c, 4000);
                    if (host == null) { setStatus("❌ اضغط اتصل أولا"); return; }
                    ip = host;
                    get(host, c, "/ctl?cmd=" + cmd);
                } catch (Exception e) {
                    ip = null;
                    setStatus("❌ فقدت الاتصال، اضغط اتصل");
                }
            }
        }).start();
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "image/*", "application/pdf"});
        startActivityForResult(i, 1);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1 && res == RESULT_OK && data != null && data.getData() != null) sendUri(data.getData());
    }

    private void sendUri(final Uri uri) {
        final String c = sp.getString("code", "");
        final String codeNow = codeF != null && !codeF.getText().toString().trim().isEmpty()
                ? codeF.getText().toString().trim() : c;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    bindWifi();
                    if (codeNow.isEmpty()) { setStatus("دخل الكود أولا"); return; }

                    String name = "file";
                    long size = -1;
                    Cursor cur = getContentResolver().query(uri, null, null, null, null);
                    if (cur != null) {
                        if (cur.moveToFirst()) {
                            int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            int si = cur.getColumnIndex(OpenableColumns.SIZE);
                            if (ni >= 0 && !cur.isNull(ni)) name = cur.getString(ni);
                            if (si >= 0 && !cur.isNull(si)) size = cur.getLong(si);
                        }
                        cur.close();
                    }
                    if (size < 0) {
                        AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r");
                        if (afd != null) { size = afd.getLength(); afd.close(); }
                    }
                    if (size < 0) { setStatus("❌ ما قدرتش نعرف حجم الملف"); return; }

                    String mime = getContentResolver().getType(uri);
                    String lower = name.toLowerCase();
                    String type = "video";
                    if ((mime != null && mime.startsWith("image")) || lower.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp)$")) type = "image";
                    else if ("application/pdf".equals(mime) || lower.endsWith(".pdf")) type = "pdf";
                    if (!name.contains(".")) name += type.equals("pdf") ? ".pdf" : type.equals("image") ? ".jpg" : ".mp4";

                    for (int attempt = 0; attempt < 2; attempt++) {
                        if (ip == null) ip = discover(codeNow, 5000);
                        if (ip == null) { setStatus("❌ ما لقيتش TV Box. اضغط اتصل."); return; }
                        try {
                            int rc = upload(ip, codeNow, uri, type, name, size);
                            if (rc == 200) { setStatus("✅ تبعث وكيتعرض في الداتا شو"); return; }
                            if (rc == 403) { setStatus("❌ الكود غلط"); return; }
                            setStatus("❌ خطأ " + rc);
                            return;
                        } catch (Exception e) {
                            ip = null;
                        }
                    }
                    setStatus("❌ فشل الإرسال. تأكد من الواي فاي واضغط اتصل.");
                } catch (Exception e) {
                    setStatus("❌ " + e.getMessage());
                }
            }
        }).start();
    }

    private int upload(String host, String code, Uri uri, String type, String name, long size) throws Exception {
        URL url = new URL("http://" + host + ":" + Net.HTTP_PORT + "/send?type=" + type
                + "&name=" + URLEncoder.encode(name, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(size);
        c.setRequestProperty("X-Code", code);
        c.setConnectTimeout(5000);
        c.setReadTimeout(120000);
        InputStream in = getContentResolver().openInputStream(uri);
        OutputStream o = c.getOutputStream();
        byte[] buf = new byte[65536];
        long done = 0;
        int lastPct = -1;
        int n;
        while ((n = in.read(buf)) > 0) {
            o.write(buf, 0, n);
            done += n;
            int pct = size > 0 ? (int) (done * 100 / size) : 0;
            if (pct != lastPct && pct % 5 == 0) { lastPct = pct; setStatus("كنبعث... " + pct + "%"); }
        }
        o.close();
        in.close();
        int rc = c.getResponseCode();
        c.disconnect();
        return rc;
    }
}
