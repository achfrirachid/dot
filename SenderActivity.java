package com.tvlink.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SenderActivity extends Activity {
    private EditText codeF, ipF;
    private TextView status;
    private Button qBtn;
    private boolean hq = true;
    private EditText qF;
    private Button mBtn;
    private int mlevel = 1;
    private boolean compat = false, autoRot = true;
    private Button cBtn, rBtn;
    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile String ip;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("tvlink", MODE_PRIVATE);
        ip = sp.getString("ip", null);
        mlevel = sp.getInt("mlevel", 1);
        compat = sp.getBoolean("compat", false);
        autoRot = sp.getBoolean("autorot", true);
        if (!sp.contains("paircode")) sp.edit().putString("paircode", Net.DEFAULT_CODE).apply();
        bindWifi();
        buildUi();
        applyAutoRotate();
        Intent in = getIntent();
        if (in != null && Intent.ACTION_SEND.equals(in.getAction())) {
            handle(in);
        } else {
            connect();   // ربط تلقائي بالكود المحفوظ
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyAutoRotate();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    private void handle(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u != null) {
            sendUri(u, null);
            return;
        }
        String t = i.getStringExtra(Intent.EXTRA_TEXT);
        if (t != null) sendLinkText(t);
    }

    // ---------------- UI ----------------
    private Button btn(String t, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(t);
        bt.setOnClickListener(l);
        return bt;
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

    // زر صوت: ضغطة وحدة = خطوة، والضغط الطويل كيعاود (للأزرار + و −)
    private Button volBtn(String text, final String cmd, final boolean repeat) {
        final Runnable[] loop = new Runnable[1];
        loop[0] = new Runnable() {
            @Override public void run() { sendCmd(cmd); ui.postDelayed(loop[0], 300); }
        };
        Button bt = compact(sbtn(text, 0xFFDCEBFF, 0xFF2563EB, 0xFF1E3A8A, null));
        bt.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        if (repeat) loop[0].run(); else sendCmd(cmd);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        ui.removeCallbacks(loop[0]);
                        return true;
                }
                return false;
            }
        });
        return bt;
    }

    // أزرار الصوت ديال الهاتف كتتحكم فصوت TV Box ملي تكون متصل وهاد الشاشة مفتوحة
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (ip != null && keyCode == KeyEvent.KEYCODE_VOLUME_UP) { sendCmd("volup"); return true; }
        if (ip != null && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { sendCmd("voldown"); return true; }
        return super.onKeyDown(keyCode, event);
    }

    private View.OnClickListener pickL(final String mime, final int req) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime);
                startActivityForResult(i, req);
            }
        };
    }

    // ---------------- تصميم الواجهة (مزوق، بطاقات شفافة، ألوان باستيل) ----------------
    private final List<ObjectAnimator> anims = new ArrayList<ObjectAnimator>();

    @Override
    protected void onDestroy() {
        for (ObjectAnimator a : anims) a.cancel();
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable shape(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private int darker(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] *= 0.85f;
        return Color.HSVToColor(Color.alpha(c), hsv);
    }

    private StateListDrawable pill(int fill, int stroke) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, shape(darker(fill), stroke, 22, 2));
        s.addState(new int[]{}, shape(fill, stroke, 22, 2));
        return s;
    }

    private Button sbtn(String t, int fill, int stroke, int textColor, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(16);
        b.setTypeface(null, Typeface.BOLD);
        b.setBackground(pill(fill, stroke));
        b.setStateListAnimator(null);
        b.setPadding(dp(10), dp(12), dp(10), dp(12));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(48));
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    private Button compact(Button b) {
        b.setTextSize(15);
        b.setPadding(dp(6), dp(6), dp(6), dp(6));
        b.setMinimumHeight(dp(42));
        return b;
    }

    private Button tile(String t, int fill, int stroke, View.OnClickListener l) {
        Button b = sbtn(t, fill, stroke, 0xFF2D2D3A, l);
        b.setTextSize(17);
        b.setMinimumHeight(dp(86));
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(3), 0, dp(3));
        return r;
    }

    private View w(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private View full(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(3), dp(4), dp(3), dp(4));
        v.setLayoutParams(lp);
        return v;
    }

    private void field(EditText e) {
        e.setBackground(shape(0xFFFFFFFF, 0xFFCBD5E1, 16, 1));
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        e.setGravity(Gravity.CENTER);
        e.setTextColor(0xFF2D2D3A);
        full(e);
    }

    private LinearLayout card(String title, int fill, int stroke) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(shape(fill, stroke, 26, 2));
        c.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, dp(8));
        c.setLayoutParams(lp);
        if (title != null) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextSize(19);
            t.setTypeface(null, Typeface.BOLD);
            t.setTextColor(0xFF3B3B4F);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, 0, 0, dp(6));
            c.addView(t);
        }
        return c;
    }

    private void buildUi() {
        final int INK = 0xFF2D2D3A;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(10), dp(14), dp(8));

        // زخرفة متحركة فوق
        LinearLayout deco = row();
        deco.setGravity(Gravity.CENTER);
        String[] em = {"🌸", "💛", "😄", "🌸", "💛", "😄", "🌸"};
        for (int i = 0; i < em.length; i++) {
            TextView e = new TextView(this);
            e.setText(em[i]);
            e.setTextSize(26);
            e.setGravity(Gravity.CENTER);
            deco.addView(w(e));
            ObjectAnimator a = ObjectAnimator.ofFloat(e, "translationY", 0f, -dp(6));
            a.setDuration(1100 + i * 140);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.start();
            anims.add(a);
        }
        l.addView(deco);

        TextView title = new TextView(this);
        title.setText("TV Link 📱 ← 📺");
        title.setTextSize(28);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF1E3A8A);
        title.setGravity(Gravity.CENTER);
        l.addView(title);
        TextView sub = new TextView(this);
        sub.setText("🌷 شاشة كبيرة · جودة عالية · تحكم كامل 🌼");
        sub.setTextSize(14);
        sub.setTextColor(0xFF7A6F5E);
        sub.setGravity(Gravity.CENTER);
        l.addView(sub);

        // 1) الاتصال
        LinearLayout c1 = card("🔗 الاتصال بـ TV Box", 0xE6EEF2FF, 0xFF93B4F5);
        codeF = new EditText(this);
        codeF.setHint("كود TV Box");
        codeF.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeF.setText(sp.getString("paircode", Net.DEFAULT_CODE));
        codeF.setTextSize(24);
        field(codeF);
        c1.addView(codeF);
        ipF = new EditText(this);
        ipF.setHint("IP ديال TV Box (اختياري)");
        ipF.setInputType(InputType.TYPE_CLASS_PHONE);
        ipF.setTextSize(16);
        field(ipF);
        c1.addView(ipF);
        c1.addView(full(sbtn("🔗 اتصل بـ TV Box", 0xFFBFDBFE, 0xFF2563EB, 0xFF1E3A8A, new View.OnClickListener() {
            @Override public void onClick(View v) { connect(); }
        })));
        status = new TextView(this);
        status.setTextSize(16);
        status.setTextColor(INK);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(8), 0, 0);
        status.setText("دخل الكود واضغط اتصل");
        c1.addView(status);
        l.addView(c1);

        // 2) إرسال الملفات
        LinearLayout c2 = card("📤 أرسل للداتا شو", 0xE6FCE4EC, 0xFFF4A6C0);
        LinearLayout pk = row();
        pk.addView(w(tile("🎬\nفيديو", 0xFFFFE0B2, 0xFFF59E0B, pickL("video/*", 1))));
        pk.addView(w(tile("🖼\nصورة", 0xFFF8BBD0, 0xFFEC4899, pickL("image/*", 2))));
        pk.addView(w(tile("📄\nPDF", 0xFFE1BEE7, 0xFF9333EA, pickL("application/pdf", 3))));
        c2.addView(pk);
        c2.addView(full(sbtn("🧩 2 أو 3 صور/PDF فنفس الشاشة (الأفواج)", 0xFFFFF3C4, 0xFFF59E0B, INK,
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, 4);
            }
        })));
        l.addView(c2);

        // 3) مشاركة شاشة الهاتف
        LinearLayout c3 = card("📱 عرض شاشة الهاتف", 0xE6E8F5E9, 0xFF8BC34A);
        c3.addView(full(sbtn("📱 عرض شاشة الهاتف على TV Box", 0xFFC8E6C9, 0xFF2E7D32, 0xFF1B5E20,
                new View.OnClickListener() {
            @Override public void onClick(View v) { startMirror(); }
        })));
        LinearLayout mr = row();
        mr.addView(w(sbtn("⏹ وقف عرض الشاشة", 0xFFFFCDD2, 0xFFC62828, 0xFF7F1D1D, new View.OnClickListener() {
            @Override public void onClick(View v) { stopMirror(); }
        })));
        mBtn = sbtn(levelText(), 0xFFFFF3C4, 0xFFF59E0B, INK, new View.OnClickListener() {
            @Override public void onClick(View v) {
                mlevel = (mlevel + 1) % 3;
                sp.edit().putInt("mlevel", mlevel).apply();
                mBtn.setText(levelText());
                // تطبيق الدقة مباشرة على العرض الشغال
                try { startService(new Intent(SenderActivity.this, ScreenService.class)
                        .setAction("level").putExtra("level", mlevel)); } catch (Exception ignored) {}
            }
        });
        mBtn.setTextSize(14);
        mr.addView(w(mBtn));
        c3.addView(mr);
        cBtn = sbtn(compatText(), 0xFFF1F5F9, 0xFF94A3B8, INK, new View.OnClickListener() {
            @Override public void onClick(View v) {
                compat = !compat;
                sp.edit().putBoolean("compat", compat).apply();
                cBtn.setText(compatText());
                setStatus(compat ? "وضع التوافق مفعل. وقف العرض وبداه من جديد." : "وضع التوافق ملغى. وقف العرض وبداه من جديد.");
            }
        });
        cBtn.setTextSize(14);
        c3.addView(full(cBtn));
        rBtn = sbtn(rotText(), 0xFFF1F5F9, 0xFF94A3B8, INK, new View.OnClickListener() {
            @Override public void onClick(View v) {
                autoRot = !autoRot;
                sp.edit().putBoolean("autorot", autoRot).remove("rot_asked").apply();
                rBtn.setText(rotText());
                applyAutoRotate();
            }
        });
        rBtn.setTextSize(14);
        c3.addView(full(rBtn));
        l.addView(c3);

        // 4) العرض والتكبير
        LinearLayout c4 = card("🔍 العرض والتكبير (صورة / PDF)", 0xE6FFF3E0, 0xFFF5B25B);
        LinearLayout z1 = row();
        z1.addView(w(sbtn("➖", 0xFFFFE0B2, 0xFFF59E0B, INK, ctl("zout"))));
        z1.addView(w(sbtn("⟲ 1x", 0xFFFFE0B2, 0xFFF59E0B, INK, ctl("zreset"))));
        z1.addView(w(sbtn("➕", 0xFFFFE0B2, 0xFFF59E0B, INK, ctl("zin"))));
        c4.addView(z1);
        LinearLayout z2 = row();
        z2.addView(w(sbtn("◀", 0xFFFFF8E1, 0xFFF59E0B, INK, ctl("pl"))));
        z2.addView(w(sbtn("▲", 0xFFFFF8E1, 0xFFF59E0B, INK, ctl("pu"))));
        z2.addView(w(sbtn("▼", 0xFFFFF8E1, 0xFFF59E0B, INK, ctl("pd"))));
        z2.addView(w(sbtn("▶", 0xFFFFF8E1, 0xFFF59E0B, INK, ctl("pr"))));
        c4.addView(z2);
        c4.addView(full(sbtn("🖼 شكل الصورة: تلقائي ← كاملة ← عرض كامل ← تغطية", 0xFFFFF3C4, 0xFFF59E0B, INK, ctl("imode"))));
        qBtn = sbtn("🔍 الجودة: عالية", 0xFFC8E6C9, 0xFF2E7D32, 0xFF1B5E20, new View.OnClickListener() {
            @Override public void onClick(View v) {
                hq = !hq;
                qBtn.setText(hq ? "🔍 الجودة: عالية" : "🔍 الجودة: عادية");
                sendCmd(hq ? "qh" : "ql");
            }
        });
        c4.addView(full(qBtn));
        l.addView(c4);

        l.addView(full(sbtn("⚙️ تغيير الوضع", 0xFF2F4B7C, 0xFF2F4B7C, 0xFFFFFFFF, new View.OnClickListener() {
            @Override public void onClick(View v) {
                sp.edit().remove("mode").apply();
                startActivity(new Intent(SenderActivity.this, MainActivity.class).putExtra("choose", true));
                finish();
            }
        })));
        TextView foot = new TextView(this);
        foot.setText("🌷🌼🌸🌼🌷");
        foot.setTextSize(22);
        foot.setGravity(Gravity.CENTER);
        l.addView(foot);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(l);

        // الشريط السفلي الثابت: الفيديو + الصفحات + الصوت
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackground(shape(0xF2FFFDF8, 0xFFE7D7BE, 26, 2));
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout r1 = row();
        r1.addView(w(compact(sbtn("⏪ 10ث", 0xFFDCEBFF, 0xFF2563EB, 0xFF1E3A8A, ctl("back")))));
        r1.addView(w(compact(sbtn("⏯", 0xFFDCEBFF, 0xFF2563EB, 0xFF1E3A8A, ctl("pause")))));
        r1.addView(w(compact(sbtn("10ث ⏩", 0xFFDCEBFF, 0xFF2563EB, 0xFF1E3A8A, ctl("fwd")))));
        bar.addView(r1);
        LinearLayout r2 = row();
        r2.addView(w(compact(sbtn("◀ صفحة", 0xFFFFF3C4, 0xFFF59E0B, INK, ctl("prev")))));
        r2.addView(w(compact(sbtn("⏹ وقف", 0xFFFFCDD2, 0xFFC62828, 0xFF7F1D1D, ctl("stop")))));
        r2.addView(w(compact(sbtn("صفحة ▶", 0xFFFFF3C4, 0xFFF59E0B, INK, ctl("next")))));
        bar.addView(r2);
        LinearLayout vr = row();
        vr.addView(w(volBtn("🔉 −", "voldown", true)));
        vr.addView(w(volBtn("🔇", "mute", false)));
        vr.addView(w(volBtn("🔊 +", "volup", true)));
        vr.addView(w(volBtn("MAX", "volmax", false)));
        bar.addView(vr);

        LinearLayout rootL = new LinearLayout(this);
        rootL.setOrientation(LinearLayout.VERTICAL);
        rootL.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFFFF8EE, 0xFFF3E9D6}));
        rootL.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.setMargins(dp(8), dp(2), dp(8), dp(8));
        rootL.addView(bar, blp);
        setContentView(rootL);
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
        sp.edit().putString("paircode", c).apply();
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
                    setStatus("❌ ما لقيتش TV Box. تأكد: نفس الواي فاي، الكود صحيح، التطبيق مفتوح في TV Box.");
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

    // ---------------- apps + mirroring ----------------
    private View.OnClickListener openL(final String app) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) { sendOpen(app); }
        };
    }

    private String levelText() {
        return mlevel == 0 ? "📺 دقة الشاشة: منخفضة (854)"
                : mlevel == 2 ? "📺 دقة الشاشة: عالية (1920)" : "📺 دقة الشاشة: عادية (1280)";
    }

    private String compatText() {
        return compat ? "🛠 وضع التوافق (هواتف قديمة/Redmi/Samsung): مفعل" : "🛠 وضع التوافق (هواتف قديمة/Redmi/Samsung): ملغى";
    }

    private String rotText() {
        return autoRot ? "🔄 التدوير التلقائي: مفعل (اضغط للإلغاء)" : "🔄 التدوير التلقائي: ملغى (اضغط للتفعيل)";
    }

    // كيفعل auto-rotate فالهاتف بوحدو، وكيرجعو لحالتو الأصلية إلا تلغات الخاصية
    private void applyAutoRotate() {
        try {
            boolean can = Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this);
            if (autoRot) {
                if (!can) {
                    if (!sp.getBoolean("rot_asked", false)) {
                        sp.edit().putBoolean("rot_asked", true).apply();
                        startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    }
                    return;
                }
                if (!sp.contains("rot_prev")) {
                    sp.edit().putInt("rot_prev", Settings.System.getInt(getContentResolver(),
                            Settings.System.ACCELEROMETER_ROTATION, 0)).apply();
                }
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1);
            } else if (can && sp.contains("rot_prev")) {
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION,
                        sp.getInt("rot_prev", 0));
                sp.edit().remove("rot_prev").apply();
            }
        } catch (Exception ignored) {}
    }

    private void sendOpen(final String app) {
        final String c = code();
        final String q = qF.getText().toString().trim();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    bindWifi();
                    for (int attempt = 0; attempt < 2; attempt++) {
                        if (ip == null) ip = discover(c, 5000);
                        if (ip == null) { setStatus("❌ ما لقيتش TV Box. اضغط اتصل."); return; }
                        try {
                            int rc = get(ip, c, "/open?app=" + app + "&q=" + URLEncoder.encode(q, "UTF-8"));
                            setStatus(rc == 200 ? "✅ كيتفتح في TV Box (خاصو إنترنت)" : "❌ خطأ " + rc);
                            return;
                        } catch (Exception e) {
                            ip = null;
                        }
                    }
                    setStatus("❌ فشل. اضغط اتصل.");
                } catch (Exception e) {
                    setStatus("❌ " + e.getMessage());
                }
            }
        }).start();
    }

    private void startMirror() {
        try {
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), 9);
        } catch (Exception e) {
            setStatus("❌ " + e.getMessage());
        }
    }

    private void stopMirror() {
        try { startService(new Intent(this, ScreenService.class).setAction("stop")); } catch (Exception ignored) {}
        sendCmd("stop");
        setStatus("تم إيقاف عرض الشاشة");
    }

    private void beginMirror(final int rc, final Intent data) {
        final String c = code();
        setStatus("كنبدا عرض الشاشة...");
        new Thread(new Runnable() {
            @Override public void run() {
                bindWifi();
                if (ip != null) {
                    try { if (get(ip, c, "/ping") != 200) ip = null; } catch (Exception e) { ip = null; }
                }
                if (ip == null) ip = discover(c, 5000);
                final String host = ip;
                if (host == null) { setStatus("❌ ما لقيتش TV Box. اضغط اتصل."); return; }
                ui.post(new Runnable() {
                    @Override public void run() {
                        Intent s = new Intent(SenderActivity.this, ScreenService.class);
                        s.putExtra("rc", rc);
                        s.putExtra("data", data);
                        s.putExtra("ip", host);
                        s.putExtra("code", c);
                        s.putExtra("level", mlevel);
                        s.putExtra("compat", compat);
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
                        setStatus("✅ عرض الشاشة شغال. استعمل الهاتف عادي.");
                        moveTaskToBack(true);
                    }
                });
            }
        }).start();
    }

    // ---------------- YouTube link ----------------
    private void pasteLink() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData cd = cm.getPrimaryClip();
                if (cd != null && cd.getItemCount() > 0) {
                    CharSequence t = cd.getItemAt(0).getText();
                    if (t != null) { sendLinkText(t.toString()); return; }
                }
            }
        } catch (Exception ignored) {}
        setStatus("ما لقيتش رابط منسوخ. انسخ رابط يوتيوب أولا.");
    }

    private void sendLinkText(String text) {
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        if (!m.find()) { setStatus("ما لقيتش رابط"); return; }
        final String url = m.group();
        final String c = code();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    bindWifi();
                    for (int attempt = 0; attempt < 2; attempt++) {
                        if (ip == null) ip = discover(c, 5000);
                        if (ip == null) { setStatus("❌ ما لقيتش TV Box. اضغط اتصل."); return; }
                        try {
                            int rc = get(ip, c, "/link?u=" + URLEncoder.encode(url, "UTF-8"));
                            setStatus(rc == 200 ? "✅ تبعث الرابط لـ TV Box" : "❌ خطأ " + rc);
                            return;
                        } catch (Exception e) {
                            ip = null;
                        }
                    }
                    setStatus("❌ فشل الإرسال. اضغط اتصل.");
                } catch (Exception e) {
                    setStatus("❌ " + e.getMessage());
                }
            }
        }).start();
    }

    // ---------------- files ----------------
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 9) {
            if (res == RESULT_OK && data != null) beginMirror(res, data);
            else setStatus("تلغى عرض الشاشة");
            return;
        }
        if (req == 4) {
            if (res == RESULT_OK && data != null) sendMulti(data);
            return;
        }
        if (res == RESULT_OK && data != null && data.getData() != null) {
            String t = req == 1 ? "video" : req == 2 ? "image" : req == 3 ? "pdf" : null;
            sendUri(data.getData(), t);
        }
    }

    private void sendUri(final Uri uri, final String forcedType) {
        final String codeNow = codeF != null && !codeF.getText().toString().trim().isEmpty()
                ? codeF.getText().toString().trim() : sp.getString("paircode", Net.DEFAULT_CODE);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    bindWifi();
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
                    String type = forcedType;
                    if (type == null) {
                        type = "video";
                        if ((mime != null && mime.startsWith("image")) || lower.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp)$")) type = "image";
                        else if ("application/pdf".equals(mime) || lower.endsWith(".pdf")) type = "pdf";
                    }
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

    // ---------------- 2 أو 3 ملفات فنفس الشاشة ----------------
    private void sendMulti(Intent data) {
        final List<Uri> us = new ArrayList<Uri>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount() && us.size() < 3; i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) us.add(u);
            }
        } else if (data.getData() != null) {
            us.add(data.getData());
        }
        if (us.isEmpty()) return;
        if (us.size() == 1) { sendUri(us.get(0), null); return; }
        final String codeNow = codeF != null && !codeF.getText().toString().trim().isEmpty()
                ? codeF.getText().toString().trim() : sp.getString("paircode", Net.DEFAULT_CODE);
        final boolean tooMany = cd != null && cd.getItemCount() > 3;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    bindWifi();
                    if (ip == null) ip = discover(codeNow, 5000);
                    if (ip == null) { setStatus("❌ ما لقيتش TV Box. اضغط اتصل."); return; }
                    int total = us.size();
                    for (int i = 0; i < total; i++) {
                        Uri uri = us.get(i);
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
                        if (size < 0) { setStatus("❌ ما قدرتش نعرف حجم الملف " + (i + 1)); return; }
                        String mime = getContentResolver().getType(uri);
                        String type = "image";
                        if ("application/pdf".equals(mime) || name.toLowerCase().endsWith(".pdf")) type = "pdf";
                        if (!name.contains(".")) name += type.equals("pdf") ? ".pdf" : ".jpg";
                        setStatus("كنبعث " + (i + 1) + "/" + total + "...");
                        int rc = uploadMulti(ip, codeNow, uri, type, name, size, i, total);
                        if (rc == 403) { setStatus("❌ الكود غلط"); return; }
                        if (rc != 200) { setStatus("❌ خطأ " + rc); return; }
                    }
                    setStatus("✅ تبعثو " + total + " فالشاشة" + (tooMany ? " (خدمت غير أول 3)" : ""));
                } catch (Exception e) {
                    ip = null;
                    setStatus("❌ فشل الإرسال. تأكد من الواي فاي واضغط اتصل.");
                }
            }
        }).start();
    }

    private int uploadMulti(String host, String code, Uri uri, String type, String name, long size,
                            int slot, int total) throws Exception {
        URL url = new URL("http://" + host + ":" + Net.HTTP_PORT + "/multi?type=" + type
                + "&slot=" + slot + "&total=" + total
                + "&name=" + URLEncoder.encode(name, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(size);
        c.setRequestProperty("X-Code", code);
        c.setConnectTimeout(5000);
        c.setReadTimeout(180000);
        InputStream in = getContentResolver().openInputStream(uri);
        OutputStream o = c.getOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        o.close();
        in.close();
        int rc = c.getResponseCode();
        c.disconnect();
        return rc;
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
        c.setReadTimeout(180000);
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
