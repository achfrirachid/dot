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
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
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
import android.view.WindowManager;
import android.util.TypedValue;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
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
import java.util.HashMap;
import java.util.Map;
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
    private Button cBtn, rBtn, gBtn;
    private boolean glassOn = true;
    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile String ip;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("tvlink", MODE_PRIVATE);
        ip = sp.getString("ip", null);
        mlevel = sp.getInt("mlevel2", 2);
        compat = sp.getBoolean("compat", false);
        autoRot = sp.getBoolean("autorot", true);
        glassOn = sp.getInt("s_glass", 1) == 1;
        hq = sp.getBoolean("hq", true);
        // مرة وحدة: القيم الافتراضية القديمة (غلظة 1، حدة 7) كتولي 0. بعدها كلشي كيبقى كيف خليتيه
        if (!sp.getBoolean("def0v1", false)) {
            SharedPreferences.Editor me = sp.edit().putBoolean("def0v1", true);
            if (sp.getInt("s_txt", 0) == 1) me.putInt("s_txt", 0);
            if (sp.getInt("s_sha", 0) == 7) me.putInt("s_sha", 0);
            me.apply();
        }
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
        ui.removeCallbacks(keep);
        ui.postDelayed(keep, 3000);
        if (videoOn) { ui.removeCallbacks(poll); ui.post(poll); }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(keep);
        ui.removeCallbacks(poll);
        super.onPause();
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
            String mime = i.getType();
            if (mime == null) mime = getContentResolver().getType(u);
            String name = displayName(u);
            String kind = fileKind(name, mime);
            if ("video".equals(kind) || (kind == null && mime != null && mime.startsWith("video"))) {
                playFromPhone(u, name);      // الفيديو كيتشغل مباشرة من الهاتف (بلا نسخ)
            } else {
                sendUri(u, kind);            // صورة / PDF
            }
            return;
        }
        String t = i.getStringExtra(Intent.EXTRA_TEXT);
        if (t != null) sendLinkText(t);
    }

    private String displayName(Uri u) {
        String name = "video.mp4";
        try {
            Cursor cur = getContentResolver().query(u, null, null, null, null);
            if (cur != null) {
                if (cur.moveToFirst()) {
                    int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (ni >= 0 && !cur.isNull(ni)) name = cur.getString(ni);
                }
                cur.close();
            }
        } catch (Exception ignored) {}
        return name;
    }

    private static String fileKind(String name, String mime) {
        String l = name == null ? "" : name.toLowerCase();
        if ("application/pdf".equals(mime) || l.endsWith(".pdf")) return "pdf";
        if ((mime != null && mime.startsWith("image")) || l.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp)$")) return "image";
        if ((mime != null && mime.startsWith("video")) || l.matches(".*\\.(mp4|m4v|mkv|webm|3gp|avi|mov|ts)$")) return "video";
        return null;
    }

    // قفل الواي فاي والمعالج كيبقاو خدامين ملي الفيديو كيتبث من الهاتف: كيقلل التقطيع والتأخر
    private static android.net.wifi.WifiManager.WifiLock castWifi;
    private static android.os.PowerManager.WakeLock castWake;

    private void holdCastLocks() {
        try {
            if (castWifi == null) {
                castWifi = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).createWifiLock(
                        Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvlink-cast");
                castWifi.setReferenceCounted(false);
            }
            if (!castWifi.isHeld()) castWifi.acquire();
            if (castWake == null) {
                castWake = ((android.os.PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "tvlink:cast");
                castWake.setReferenceCounted(false);
            }
            if (!castWake.isHeld()) castWake.acquire(3 * 60 * 60 * 1000L);
        } catch (Exception ignored) {}
    }

    private void releaseCastLocks() {
        try { if (castWifi != null && castWifi.isHeld()) castWifi.release(); } catch (Exception ignored) {}
        try { if (castWake != null && castWake.isHeld()) castWake.release(); } catch (Exception ignored) {}
    }

    // ---------------- UI (زجاجي · بلا سكرول · 3 أعمدة) ----------------
    private static final int WHITE = 0xFFFFFFFF;
    private final List<ObjectAnimator> anims = new ArrayList<ObjectAnimator>();
    // ألوان الأزرار: نفس ألوان التطبيق القديم (باستيل: تعبئة + حافة ملونة + كتابة غامقة)
    private static final int INK = 0xFF2D2D3A;
    private static final int C_BLUE = 0, C_GREEN = 1, C_RED = 2, C_AMBER = 3, C_PINK = 4, C_PURPLE = 5,
            C_SLATE = 6, C_CYAN = 7, C_ORANGE = 8, C_CREAM = 9;
    private static final int[] P_FILL = {0xFFDCEBFF, 0xFFC8E6C9, 0xFFFFCDD2, 0xFFFFF3C4, 0xFFF8BBD0,
            0xFFE1BEE7, 0xFFF1F5F9, 0xFFE3F2FD, 0xFFFFE0B2, 0xFFFFF8E1};
    private static final int[] P_STROKE = {0xFF2563EB, 0xFF2E7D32, 0xFFC62828, 0xFFF59E0B, 0xFFEC4899,
            0xFF9333EA, 0xFF94A3B8, 0xFF64B5F6, 0xFFF59E0B, 0xFFF59E0B};
    private static final int[] P_TEXT = {0xFF1E3A8A, 0xFF1B5E20, 0xFF7F1D1D, INK, INK,
            0xFF4A148C, INK, 0xFF0D47A1, INK, INK};

    @Override
    protected void onDestroy() {
        for (ObjectAnimator a : anims) a.cancel();
        if (isFinishing()) releaseCastLocks();
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private View.OnClickListener ctl(final String cmd) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) { sendCmd(cmd); }
        };
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

    // أزرار الصوت ديال الهاتف كتتحكم فصوت TV Box ملي تكون متصل وهاد الشاشة مفتوحة
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (ip != null && keyCode == KeyEvent.KEYCODE_VOLUME_UP) { sendCmd("volup"); return true; }
        if (ip != null && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { sendCmd("voldown"); return true; }
        return super.onKeyDown(keyCode, event);
    }

    private GradientDrawable shape(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    // زجاج: تدرج شفاف من لون + حافة بيضاء لامعة
    private GradientDrawable glassShape(int tint, int a1, int a2, int strokeColor, int radiusDp) {
        int rgb = tint & 0xFFFFFF;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(a1 << 24) | rgb, (a2 << 24) | rgb});
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), strokeColor);
        return g;
    }

    private int darker(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] *= 0.85f;
        return Color.HSVToColor(Color.alpha(c), hsv);
    }

    private StateListDrawable glassState(int idx) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, shape(darker(P_FILL[idx]), P_STROKE[idx], 22, 2));
        s.addState(new int[]{}, shape(P_FILL[idx], P_STROKE[idx], 22, 2));
        return s;
    }

    private void autosize(TextView t, int minSp, int maxSp) {
        if (Build.VERSION.SDK_INT >= 26) {
            t.setAutoSizeTextTypeUniformWithConfiguration(minSp, maxSp, 1, TypedValue.COMPLEX_UNIT_SP);
        } else {
            t.setTextSize(minSp + 3);
        }
    }

    private Button gbtn(String t, int tint, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(P_TEXT[tint]);
        b.setTypeface(null, Typeface.BOLD);
        b.setIncludeFontPadding(false);
        b.setGravity(Gravity.CENTER);
        b.setMaxLines(2);
        b.setPadding(dp(2), dp(2), dp(2), dp(2));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setBackground(glassState(tint));
        b.setStateListAnimator(null);
        autosize(b, 9, 17);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    // زر كيبعت الأمر مباشرة، والضغط المستمر كيعاود (للأسهم والصوت والتكبير)
    private Button holdBtn(String text, final String cmd, int tint, final int repeatMs) {
        final Runnable[] loop = new Runnable[1];
        loop[0] = new Runnable() {
            @Override public void run() { sendCmd(cmd); ui.postDelayed(loop[0], repeatMs); }
        };
        Button bt = gbtn(text, tint, null);
        bt.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        if (repeatMs > 0) loop[0].run(); else sendCmd(cmd);
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

    private LinearLayout box(boolean vertical) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        return b;
    }

    // خانة فعمود: كتاخد حصة من الطول
    private <T extends View> T vw(T v, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, weight);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        v.setLayoutParams(lp);
        return v;
    }

    // خانة فصف: كتاخد حصة من العرض
    private <T extends View> T hw(T v, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, weight);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        v.setLayoutParams(lp);
        return v;
    }

    private TextView label(String t, int sizeSp, boolean bold) {
        TextView x = new TextView(this);
        x.setText(t);
        x.setTextSize(sizeSp);
        x.setTextColor(INK);
        x.setGravity(Gravity.CENTER);
        x.setSingleLine(true);
        if (bold) x.setTypeface(null, Typeface.BOLD);
        return x;
    }

    private LinearLayout glassCard(String title, int fill, int stroke) {
        LinearLayout c = box(true);
        c.setBackground(shape(fill, stroke, 26, 2));
        c.setPadding(dp(3), dp(4), dp(3), dp(3));
        if (title != null) {
            TextView t = label(title, 13, true);
            t.setTextColor(0xFF3B3B4F);
            c.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        return c;
    }

    private void field(EditText e, int sizeSp) {
        e.setBackground(shape(0xFFFFFFFF, 0xFFCBD5E1, 16, 1));
        e.setPadding(dp(4), 0, dp(4), 0);
        e.setGravity(Gravity.CENTER);
        e.setTextColor(INK);
        e.setHintTextColor(0xFF7A6F5E);
        e.setTextSize(sizeSp);
        e.setSingleLine(true);
    }

    private View blob(int color, int sizeDp, int gravity, int mx, int my, float travelDp, long ms) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), gravity);
        lp.setMargins(dp(mx), dp(my), dp(mx), dp(my));
        v.setLayoutParams(lp);
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "translationY", 0f, dp((int) travelDp));
        a.setDuration(ms);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.start();
        anims.add(a);
        return v;
    }

    private void buildUi() {
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);

        // ===== الخانة العلوية: الاتصال بـ TV Box (صغيرة وكلماتها ظاهرة) =====
        LinearLayout top = glassCard(null, 0xE6EEF2FF, 0xFF93B4F5);
        top.setPadding(dp(8), dp(3), dp(8), dp(4));
        TextView ct = label("🔗 الاتصال بـ TV Box", 15, true);
        ct.setTextColor(0xFF3B3B4F);
        top.addView(ct, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(INK);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(2);
        status.setText("دخل الكود واضغط اتصال");

        LinearLayout t2 = box(false);
        codeF = new EditText(this);
        codeF.setHint("🔑 كود TV Box");
        codeF.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeF.setText(sp.getString("paircode", Net.DEFAULT_CODE));
        field(codeF, 15);
        ipF = new EditText(this);
        ipF.setHint("🌐 IP (اختياري)");
        ipF.setInputType(InputType.TYPE_CLASS_PHONE);
        field(ipF, 13);
        ipF.setText(sp.getString("manual_ip", ""));
        autoSave(codeF, "paircode");
        autoSave(ipF, "manual_ip");
        Button cn = gbtn("🔗 اتصال", C_BLUE, new View.OnClickListener() {
            @Override public void onClick(View v) { connect(); }
        });
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(38), 1.1f);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(38), 1.1f);
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(0, dp(38), 0.9f);
        p1.setMargins(dp(2), dp(2), dp(2), dp(2));
        p2.setMargins(dp(2), dp(2), dp(2), dp(2));
        p3.setMargins(dp(2), dp(2), dp(2), dp(2));
        t2.addView(codeF, p1);
        t2.addView(ipF, p2);
        t2.addView(cn, p3);
        top.addView(t2, new LinearLayout.LayoutParams(-1, -2));
        top.addView(status, new LinearLayout.LayoutParams(-1, -2));

        // ===== العمود اليسار: ضبط الصورة =====
        LinearLayout left = glassCard("🎛 ضبط الصورة", 0xE6E3F2FD, 0xFF64B5F6);
        left.addView(vw(levelCell("📐 الحجم", "fit", 1, 10, 10), 1f));
        left.addView(vw(levelCell("☀️ السطوع", "bri", 1, 10, 5), 1f));
        left.addView(vw(levelCell("◐ التباين", "con", 1, 10, 5), 1f));
        left.addView(vw(levelCell("🎨 الألوان", "sat", 1, 10, 5), 1f));
        left.addView(vw(levelCell("🖋 غلظة الكتابة", "txt", 0, 10, 0), 1f));
        left.addView(vw(levelCell("🔎 حدة الصورة", "sha", 0, 10, 0), 1f));
        left.addView(vw(levelCell("🌓 إضاءة الوجوه", "gam", 0, 10, 5), 1f));
        left.addView(vw(levelCell("🎥 تعتيم الفيديو", "vdim", 0, 10, 6), 1f));
        LinearLayout lr1 = box(false);
        lr1.addView(hw(gbtn("📄\nامتحان", C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { preset(10, 5, 5, 5, 0, 0); }
        }), 1f));
        lr1.addView(hw(gbtn("🎬\nألوان", C_AMBER, new View.OnClickListener() {
            @Override public void onClick(View v) { preset(10, 5, 6, 7, 0, 3); }
        }), 1f));
        left.addView(vw(lr1, 1.1f));
        LinearLayout lr2 = box(false);
        lr2.addView(hw(gbtn("↺\nافتراضي", C_SLATE, new View.OnClickListener() {
            @Override public void onClick(View v) { preset(10, 5, 5, 5, 0, 0); setLevel("gam", 5); setLevel("vdim", 6); }
        }), 1f));
        lr2.addView(hw(gbtn("⚙️\nالوضع", C_PURPLE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                sp.edit().remove("mode").apply();
                startActivity(new Intent(SenderActivity.this, MainActivity.class).putExtra("choose", true));
                finish();
            }
        }), 1f));
        left.addView(vw(lr2, 1.1f));
        bBtn = gbtn(invText(), C_SLATE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                int nv = sp.getInt("s_inv", 0) == 1 ? 0 : 1;
                sp.edit().putInt("s_inv", nv).putBoolean("s_dirty", true).apply();
                bBtn.setText(invText());
                sendCmd("inv:" + nv);
            }
        });
        left.addView(vw(bBtn, 0.9f));

        // ===== العمود الوسط: الأسهم + التكبير + الصوت + التشغيل =====
        LinearLayout center = glassCard("🎮 تحكم", 0xF2FFFDF8, 0xFFE7D7BE);
        LinearLayout dpad = box(true);
        LinearLayout d1 = box(false);
        d1.addView(hw(new View(this), 1f));
        d1.addView(hw(holdBtn("▲", "pu", C_CREAM, 220), 1f));
        d1.addView(hw(new View(this), 1f));
        LinearLayout d2 = box(false);
        d2.addView(hw(holdBtn("◀", "pl", C_CREAM, 220), 1f));
        d2.addView(hw(gbtn("🎯", C_PINK, ctl("zreset")), 1f));
        d2.addView(hw(holdBtn("▶", "pr", C_CREAM, 220), 1f));
        LinearLayout d3 = box(false);
        d3.addView(hw(new View(this), 1f));
        d3.addView(hw(holdBtn("▼", "pd", C_CREAM, 220), 1f));
        d3.addView(hw(new View(this), 1f));
        dpad.addView(vw(d1, 1f));
        dpad.addView(vw(d2, 1f));
        dpad.addView(vw(d3, 1f));
        for (int i = 0; i < 3; i++) {
            LinearLayout rr = (LinearLayout) dpad.getChildAt(i);
            for (int j = 0; j < 3; j++) {
                View ch = rr.getChildAt(j);
                if (ch instanceof Button) autosize((Button) ch, 14, 30);
            }
        }
        center.addView(vw(dpad, 3.3f));

        LinearLayout zr = box(false);
        zr.addView(hw(holdBtn("−", "zout", C_ORANGE, 350), 1f));
        zBtn = gbtn("1x", C_ORANGE, ctl("zreset"));
        zr.addView(hw(zBtn, 0.8f));
        zr.addView(hw(holdBtn("+", "zin", C_ORANGE, 350), 1f));
        center.addView(vw(zr, 1f));

        LinearLayout vr = box(false);
        vr.addView(hw(holdBtn("🔉\n−", "voldown", C_BLUE, 300), 1f));
        vr.addView(hw(holdBtn("🔇", "mute", C_BLUE, 0), 0.8f));
        vr.addView(hw(holdBtn("🔊\n+", "volup", C_BLUE, 300), 1f));
        center.addView(vw(vr, 1.15f));

        LinearLayout mr = box(false);
        mr.addView(hw(gbtn("⏪\n10ث", C_BLUE, ctl("back")), 1f));
        mr.addView(hw(gbtn("⏯", C_GREEN, ctl("pause")), 0.9f));
        mr.addView(hw(gbtn("10ث\n⏩", C_BLUE, ctl("fwd")), 1f));
        center.addView(vw(mr, 1f));

        LinearLayout pg = box(false);
        pg.addView(hw(gbtn("◀\nصفحة", C_AMBER, ctl("prev")), 1f));
        pg.addView(hw(gbtn("⏹\nوقف", C_RED, ctl("stop")), 0.9f));
        pg.addView(hw(gbtn("صفحة\n▶", C_AMBER, ctl("next")), 1f));
        center.addView(vw(pg, 1f));

        qBtn = gbtn(hq ? "🔍\nعالية" : "🔍\nعادية", C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) {
                hq = !hq;
                sp.edit().putBoolean("hq", hq).putBoolean("s_dirty", true).apply();
                qBtn.setText(hq ? "🔍\nعالية" : "🔍\nعادية");
                sendCmd(hq ? "qh" : "ql");
            }
        });
        LinearLayout ex = box(false);
        ex.addView(hw(gbtn("🔊\nMAX", C_BLUE, ctl("volmax")), 1f));
        ex.addView(hw(gbtn("🖼\nصورة", C_PINK, ctl("imode")), 1f));
        ex.addView(hw(qBtn, 1f));
        center.addView(vw(ex, 1f));

        // ===== العمود اليمين: الإرسال + عرض الهاتف =====
        LinearLayout right = box(true);
        LinearLayout send = glassCard("📤 أرسل للداتا شو", 0xE6FCE4EC, 0xFFF4A6C0);
        send.addView(vw(gbtn("🎬  فيديو", C_ORANGE, pickL("video/*", 1)), 1f));
        send.addView(vw(gbtn("🖼  صورة", C_PINK, pickL("image/*", 2)), 1f));
        send.addView(vw(gbtn("📄  PDF", C_PURPLE, pickL("application/pdf", 3)), 1f));
        right.addView(vw(send, 4.2f));

        LinearLayout disp = glassCard("📱 عرض الهاتف", 0xE6E8F5E9, 0xFF8BC34A);
        mBtn = gbtn(levelText(), C_AMBER, new View.OnClickListener() {
            @Override public void onClick(View v) {
                mlevel = (mlevel + 1) % 4;
                sp.edit().putInt("mlevel2", mlevel).apply();
                mBtn.setText(levelText());
                // تطبيق الدقة مباشرة على العرض الشغال
                try { startService(new Intent(SenderActivity.this, ScreenService.class)
                        .setAction("level").putExtra("level", mlevel)); } catch (Exception ignored) {}
            }
        });
        disp.addView(vw(mBtn, 1f));
        cBtn = gbtn(compatText(), C_SLATE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                compat = !compat;
                sp.edit().putBoolean("compat", compat).apply();
                cBtn.setText(compatText());
                setStatus(compat ? "وضع التوافق مفعل. وقف العرض وبداه من جديد." : "وضع التوافق ملغى. وقف العرض وبداه من جديد.");
            }
        });
        disp.addView(vw(cBtn, 1f));
        rBtn = gbtn(rotText(), C_SLATE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                autoRot = !autoRot;
                sp.edit().putBoolean("autorot", autoRot).remove("rot_asked").apply();
                rBtn.setText(rotText());
                applyAutoRotate();
            }
        });
        disp.addView(vw(rBtn, 1f));
        disp.addView(vw(gbtn("🖥 الشكل\nملء ← تغطية ← أصلي", C_AMBER, ctl("lmode")), 1.15f));
        gBtn = gbtn(glassText(), C_PINK, new View.OnClickListener() {
            @Override public void onClick(View v) {
                glassOn = !glassOn;
                sp.edit().putInt("s_glass", glassOn ? 1 : 0).putBoolean("s_dirty", true).apply();
                gBtn.setText(glassText());
                sendCmd("glass:" + (glassOn ? 1 : 0));
            }
        });
        disp.addView(vw(gBtn, 1.1f));
        disp.addView(vw(gbtn("🔊 اختبار الصوت", C_BLUE, ctl("beep")), 1f));
        right.addView(vw(disp, 6.4f));

        // ===== الشريط السفلي: أهم الأزرار اليومية =====
        LinearLayout bar = glassCard(null, 0xF2FFFDF8, 0xFFE7D7BE);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(hw(gbtn("📱 ابدأ عرض الشاشة", C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { startMirror(); }
        }), 1.5f));
        bar.addView(hw(gbtn("⏹ وقف العرض", C_RED, new View.OnClickListener() {
            @Override public void onClick(View v) { stopMirror(); }
        }), 1f));

        LinearLayout mid = box(false);
        LinearLayout.LayoutParams lpL = new LinearLayout.LayoutParams(0, -1, 31f);
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(0, -1, 38f);
        LinearLayout.LayoutParams lpR = new LinearLayout.LayoutParams(0, -1, 31f);
        lpL.setMargins(0, 0, dp(3), 0);
        lpC.setMargins(dp(3), 0, dp(3), 0);
        lpR.setMargins(dp(3), 0, 0, 0);
        mid.addView(left, lpL);
        mid.addView(center, lpC);
        mid.addView(right, lpR);

        LinearLayout content = box(true);
        content.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.setMargins(0, 0, 0, dp(4));
        // زخرفة متحركة + عنوان (نفس تزيين التطبيق الأصلي)
        LinearLayout deco = box(false);
        deco.setGravity(Gravity.CENTER);
        String[] em = {"🌸", "💛", "😄", "🌸", "💛", "😄", "🌸"};
        for (int i = 0; i < em.length; i++) {
            TextView e = new TextView(this);
            e.setText(em[i]);
            e.setTextSize(20);
            e.setGravity(Gravity.CENTER);
            deco.addView(hw(e, 1f));
        }
        content.addView(deco, new LinearLayout.LayoutParams(-1, dp(30)));
        TextView mainTitle = new TextView(this);
        mainTitle.setText("TV Link 📱 ← 📺");
        mainTitle.setTextSize(24);
        mainTitle.setTypeface(null, Typeface.BOLD);
        mainTitle.setTextColor(0xFF1E3A8A);
        mainTitle.setGravity(Gravity.CENTER);
        content.addView(mainTitle, new LinearLayout.LayoutParams(-1, -2));
        TextView subTitle = new TextView(this);
        subTitle.setText("🌷 شاشة كبيرة · جودة عالية · تحكم كامل 🌼");
        subTitle.setTextSize(12);
        subTitle.setTextColor(0xFF7A6F5E);
        subTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(-1, -2);
        stl.setMargins(0, 0, 0, dp(4));
        content.addView(subTitle, stl);
        content.addView(top, tl);
        content.addView(mid, new LinearLayout.LayoutParams(-1, 0, 1f));
        content.addView(buildVbar(), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(54));
        bl.setMargins(0, dp(4), 0, 0);
        content.addView(bar, bl);
        TextView foot = new TextView(this);
        foot.setText("🌷🌼🌸🌼🌷");
        foot.setTextSize(16);
        foot.setGravity(Gravity.CENTER);
        content.addView(foot, new LinearLayout.LayoutParams(-1, -2));

        // ===== الخلفية: تدرج + فقاعات ملونة كتعطي عمق للزجاج =====
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFFFF8EE, 0xFFF3E9D6}));
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        setContentView(root);
    }

    // ---------------- تحكم الفيديو (كيبان ملي فيديو كيتعرض فالداتا شو) ----------------
    private LinearLayout vbar;
    private SeekBar seek;
    private TextView tCur, tDur;
    private Button tSpd, bBtn;
    private volatile boolean videoOn, dragging;
    private volatile long videoStart;
    private static final int[] SPD = {5, 10, 15, 20};   // خطوات 0.5
    private final java.util.concurrent.atomic.AtomicBoolean polling = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ExecutorService pollPool = java.util.concurrent.Executors.newSingleThreadExecutor();

    private static String fmt(int ms) {
        int sec = Math.max(0, ms / 1000);
        return (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
    }

    private String spdText() {
        int v = sp.getInt("s_spd", 10);
        return (v / 10) + "." + (v % 10) + "x";
    }

    private String invText() {
        return "🌑 سبورة (قلب الألوان)\n" + (sp.getInt("s_inv", 0) == 1 ? "مفعل ✅" : "ملغى");
    }

    private void stepSpeed(int dir) {
        int cur = sp.getInt("s_spd", 10), idx = 0;
        for (int i = 0; i < SPD.length; i++) if (SPD[i] <= cur) idx = i;
        setSpeed(SPD[Math.max(0, Math.min(SPD.length - 1, idx + dir))]);
    }

    private void setSpeed(int v) {
        sp.edit().putInt("s_spd", v).putBoolean("s_dirty", true).apply();
        tSpd.setText(spdText());
        sendCmd("spd:" + v);
    }

    private LinearLayout buildVbar() {
        vbar = glassCard(null, 0xF2FFFDF8, 0xFFE7D7BE);
        vbar.setVisibility(View.GONE);
        LinearLayout r1 = box(false);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        tCur = label("0:00", 12, true);
        tDur = label("0:00", 12, true);
        seek = new SeekBar(this);
        seek.setMax(1);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) { if (user) tCur.setText(fmt(p)); }
            @Override public void onStartTrackingTouch(SeekBar b) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar b) { dragging = false; sendCmd("seek:" + b.getProgress()); }
        });
        r1.addView(tCur, new LinearLayout.LayoutParams(dp(46), -2));
        r1.addView(seek, new LinearLayout.LayoutParams(0, -2, 1f));
        r1.addView(tDur, new LinearLayout.LayoutParams(dp(46), -2));
        vbar.addView(r1, new LinearLayout.LayoutParams(-1, dp(36)));
        LinearLayout r2 = box(false);
        r2.addView(hw(gbtn("⏪ 10", C_BLUE, ctl("back")), 1f));
        r2.addView(hw(gbtn("⏯", C_GREEN, ctl("pause")), 1f));
        r2.addView(hw(gbtn("10 ⏩", C_BLUE, ctl("fwd")), 1f));
        r2.addView(hw(gbtn("−", C_AMBER, new View.OnClickListener() {
            @Override public void onClick(View v) { stepSpeed(-1); }
        }), 0.8f));
        tSpd = gbtn(spdText(), C_SLATE, new View.OnClickListener() {
            @Override public void onClick(View v) { setSpeed(10); }
        });
        r2.addView(hw(tSpd, 1f));
        r2.addView(hw(gbtn("+", C_AMBER, new View.OnClickListener() {
            @Override public void onClick(View v) { stepSpeed(1); }
        }), 0.8f));
        r2.addView(hw(gbtn("⏹", C_RED, new View.OnClickListener() {
            @Override public void onClick(View v) { hideVideoBar(); sendCmd("stop"); }
        }), 0.8f));
        vbar.addView(r2, new LinearLayout.LayoutParams(-1, dp(44)));
        // ألوان خاصة بالفيديو (منفصلة عن الصور والامتحانات)
        LinearLayout r3 = box(false);
        r3.addView(hw(levelCell("☀️ سطوع الفيديو", "vbri", 1, 10, 5), 1f));
        r3.addView(hw(levelCell("◐ تباين الفيديو", "vcon", 1, 10, 5), 1f));
        r3.addView(hw(levelCell("🎨 ألوان الفيديو", "vsat", 1, 10, 5), 1f));
        vbar.addView(r3, new LinearLayout.LayoutParams(-1, dp(60)));
        return vbar;
    }

    private void showVideoBar() {
        ui.post(new Runnable() {
            @Override public void run() {
                videoOn = true;
                videoStart = System.currentTimeMillis();
                tSpd.setText(spdText());
                vbar.setVisibility(View.VISIBLE);
                ui.removeCallbacks(poll);
                ui.post(poll);
            }
        });
    }

    private void hideVideoBar() {
        videoOn = false;
        ui.removeCallbacks(poll);
        ui.post(new Runnable() { @Override public void run() { vbar.setVisibility(View.GONE); } });
    }

    // كيسول TV Box كل ثانية (غير ملي الفيديو شغال والتطبيق مفتوح) باش يتحرك الشريط
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!videoOn) return;
            ui.postDelayed(this, 1000);
            final String host = ip;
            if (host == null || !polling.compareAndSet(false, true)) return;
            pollPool.execute(new Runnable() {
                @Override public void run() {
                    try {
                        String b = getBodyT(host, curCode(), "/vstat", 1200, 1500);
                        if (b == null) return;
                        String[] a = b.split(",");
                        final int pos = Integer.parseInt(a[0]), dur = Integer.parseInt(a[1]), vis = Integer.parseInt(a[4]);
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (vis == 0) {
                                    if (System.currentTimeMillis() - videoStart > 5000) { videoOn = false; vbar.setVisibility(View.GONE); }
                                    return;
                                }
                                tDur.setText(fmt(dur));
                                if (!dragging) {
                                    seek.setMax(Math.max(1, dur));
                                    seek.setProgress(pos);
                                    tCur.setText(fmt(pos));
                                }
                            }
                        });
                    } catch (Exception ignored) {
                    } finally {
                        polling.set(false);
                    }
                }
            });
        }
    };

    private String curCode() { return cmdCode != null ? cmdCode : sp.getString("paircode", Net.DEFAULT_CODE); }

    // ربط تلقائي: كل 10 ثواني كيتأكد من الاتصال، وإلا TV Box تشعل من بعد كيتصل بوحدو (بلا ما تضغط)
    private volatile boolean keepBusy;
    private final Runnable keep = new Runnable() {
        @Override public void run() {
            ui.postDelayed(this, 10000);
            if (keepBusy) return;
            keepBusy = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        String c = curCode();
                        String host = ip;
                        boolean ok = false;
                        if (host != null) { try { ok = get(host, c, "/ping") == 200; } catch (Exception ignored) {} }
                        if (ok) return;
                        bindWifi();
                        String f = null;
                        String man = sp.getString("manual_ip", "");
                        if (man != null && !man.isEmpty()) { try { if (get(man, c, "/ping") == 200) f = man; } catch (Exception ignored) {} }
                        if (f == null) f = discover(c, 1500);
                        if (f != null) {
                            ip = f;
                            sp.edit().putString("ip", f).apply();
                            setStatus("✅ متصل بـ TV Box (" + f + ")");
                            if (sp.getBoolean("s_dirty", false) || !pullLevels(f, c)) syncLevels();
                        } else if (host != null) {
                            ip = null;
                            setStatus("⏳ كنقلب على TV Box...");
                        }
                    } catch (Exception ignored) {
                    } finally {
                        keepBusy = false;
                    }
                }
            }).start();
        }
    };

    private void setStatus(final String s) {
        ui.post(new Runnable() { @Override public void run() { status.setText(s); } });
    }

    // ---------------- مستويات الضبط (كتتحفظ تلقائيا) ----------------
    private final Map<String, TextView> lvlViews = new HashMap<String, TextView>();
    private final Map<String, int[]> lvlRange = new HashMap<String, int[]>();
    private final Map<String, Integer> lvlDef = new HashMap<String, Integer>();

    private void setLevel(String key, int v) {
        int[] rg = lvlRange.get(key);
        v = Math.max(rg[0], Math.min(rg[1], v));
        sp.edit().putInt("s_" + key, v).putBoolean("s_dirty", true).apply();
        lvlViews.get(key).setText(v + "/" + rg[1]);
        sendCmd(key + ":" + v);
    }

    private void preset(int fit, int bri, int con, int sat, int txt, int sha) {
        setLevel("fit", fit); setLevel("bri", bri); setLevel("con", con);
        setLevel("sat", sat); setLevel("txt", txt); setLevel("sha", sha);
    }

    // كيبعت القيم المحفوظة فالهاتف لـ TV Box ملي كيتصل، باش كلشي يبقى مطابق
    private void syncLevels() {
        ui.post(new Runnable() {
            @Override public void run() {
                for (String k : lvlRange.keySet()) {
                    sendCmd(k + ":" + sp.getInt("s_" + k, lvlDef.get(k)));
                }
                sendCmd("glass:" + (glassOn ? 1 : 0));
                sendCmd("spd:" + sp.getInt("s_spd", 10));
                sendCmd("inv:" + sp.getInt("s_inv", 0));
                sendCmd(hq ? "qh" : "ql");
            }
        });
    }

    private View levelCell(String lab, final String key, int min, int max, final int def) {
        lvlRange.put(key, new int[]{min, max});
        lvlDef.put(key, def);
        LinearLayout c = box(true);
        c.setBackground(shape(0xE6FFFFFF, 0xFF93B4F5, 14, 1));
        c.setPadding(dp(2), dp(1), dp(2), dp(2));
        TextView lb = label(lab, 10, true);
        c.addView(lb, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout r = box(false);
        Button m = gbtn("−", C_BLUE, new View.OnClickListener() {
            @Override public void onClick(View v) { setLevel(key, sp.getInt("s_" + key, def) - 1); }
        });
        TextView val = label(sp.getInt("s_" + key, def) + "/" + max, 13, true);
        val.setGravity(Gravity.CENTER);
        lvlViews.put(key, val);
        Button p = gbtn("+", C_BLUE, new View.OnClickListener() {
            @Override public void onClick(View v) { setLevel(key, sp.getInt("s_" + key, def) + 1); }
        });
        autosize(m, 14, 24);
        autosize(p, 14, 24);
        r.addView(hw(m, 1f));
        r.addView(hw(val, 1.2f));
        r.addView(hw(p, 1f));
        c.addView(r, new LinearLayout.LayoutParams(-1, 0, 1f));
        return c;
    }

    // ---------------- network ----------------
    // كيربط التطبيق كامل بالواي فاي حتى وهو "ما فيه أنترنيت" (بلا ما ينتظر تحقق النظام)
    private static volatile Network wifiNet;
    private static ConnectivityManager.NetworkCallback wifiCb;

    private void bindWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiNet = n;
                    cm.bindProcessToNetwork(n);
                    break;
                }
            }
            if (wifiCb == null && Build.VERSION.SDK_INT >= 21) {
                android.net.NetworkRequest rq = new android.net.NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
                wifiCb = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network n) {
                        wifiNet = n;
                        try { ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).bindProcessToNetwork(n); } catch (Exception ignored) {}
                    }
                };
                cm.requestNetwork(rq, wifiCb);
            }
        } catch (Exception ignored) {}
    }

    // إلا الـ broadcast ما خدمش (راوترات كتمنعو)، كنجربو كل العناوين ديال الشبكة مباشرة
    private String scanSubnet(final String code, int timeoutMs) {
        try {
            DhcpInfo d = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).getDhcpInfo();
            int own = d != null ? d.ipAddress : 0;
            int mask = d != null ? d.netmask : 0;
            if (own == 0) {
                // الهاتف هو الهوتسبوت (ولا DHCP فارغ): كناخدو العنوان من واجهات الشبكة، و /24
                for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    String nn = ni.getName();
                    if (!(nn.startsWith("wlan") || nn.startsWith("ap") || nn.startsWith("swlan") || nn.startsWith("eth"))) continue;
                    for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                        if (a instanceof java.net.Inet4Address) {
                            byte[] b = a.getAddress();
                            own = (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
                            mask = 0x00FFFFFF;
                        }
                    }
                }
            }
            if (own == 0) return null;
            final int ownF = own;
            final int base = own & mask;
            final java.util.concurrent.atomic.AtomicReference<String> hit = new java.util.concurrent.atomic.AtomicReference<String>();
            java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(48);
            for (int i = 1; i < 255; i++) {
                final int ipInt = (base & 0x00FFFFFF) | (i << 24);
                if (ipInt == ownF) continue;
                ex.execute(new Runnable() {
                    @Override public void run() {
                        if (hit.get() != null) return;
                        String h = (ipInt & 0xFF) + "." + ((ipInt >> 8) & 0xFF) + "." + ((ipInt >> 16) & 0xFF) + "." + ((ipInt >> 24) & 0xFF);
                        java.net.Socket sk = new java.net.Socket();
                        try {
                            sk.connect(new java.net.InetSocketAddress(h, Net.HTTP_PORT), 400);
                            sk.close();
                            if (get(h, code, "/ping") == 200) hit.compareAndSet(null, h);
                        } catch (Exception ignored) {
                            try { sk.close(); } catch (Exception ignored2) {}
                        }
                    }
                });
            }
            ex.shutdown();
            ex.awaitTermination(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            ex.shutdownNow();
            return hit.get();
        } catch (Exception e) { return null; }
    }

    private String findTv(String code, int udpMs) {
        String f = discover(code, udpMs);
        if (f == null) f = scanSubnet(code, 6000);
        return f;
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

    private void flag(String cmd, boolean dirty) {
        if (cmd.indexOf(':') > 0 || "qh".equals(cmd) || "ql".equals(cmd))
            sp.edit().putBoolean("s_dirty", dirty).apply();
    }

    private void autoSave(final EditText e, final String key) {
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable t) {
                String v = t.toString().trim();
                sp.edit().putString(key, v).apply();
                if ("paircode".equals(key)) cmdCode = v;
            }
        });
    }

    private String getBody(String host, String code, String path) throws Exception {
        return getBodyT(host, code, path, 4000, 8000);
    }

    private String getBodyT(String host, String code, String path, int ct, int rt) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + Net.HTTP_PORT + path).openConnection();
        c.setConnectTimeout(ct);
        c.setReadTimeout(rt);
        c.setRequestProperty("X-Code", code);
        try {
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[512];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // الإعدادات المحفوظة فـ TV Box هي المرجع: كنجبدوها للهاتف ملي كنتصلو (باش تغييرات لوحة TV ما تضيعش)
    private boolean pullLevels(String host, String code) {
        try {
            String body = getBody(host, code, "/levels");
            if (body == null || body.isEmpty()) return false;
            SharedPreferences.Editor ed = sp.edit();
            for (String kv : body.split(",")) {
                String[] p = kv.split("=");
                if (p.length != 2) continue;
                String k = p[0].trim();
                int v = Integer.parseInt(p[1].trim());
                if (lvlRange.containsKey(k)) ed.putInt("s_" + k, v);
                else if ("glass".equals(k)) ed.putInt("s_glass", v);
                else if ("q".equals(k)) ed.putBoolean("hq", v == 2);
                else if ("spd".equals(k)) ed.putInt("s_spd", v);
                else if ("inv".equals(k)) ed.putInt("s_inv", v);
            }
            ed.apply();
            ui.post(new Runnable() {
                @Override public void run() {
                    for (String k : lvlRange.keySet()) {
                        TextView tv = lvlViews.get(k);
                        if (tv != null) tv.setText(sp.getInt("s_" + k, lvlDef.get(k)) + "/" + lvlRange.get(k)[1]);
                    }
                    glassOn = sp.getInt("s_glass", 1) == 1;
                    hq = sp.getBoolean("hq", true);
                    if (gBtn != null) gBtn.setText(glassText());
                    if (qBtn != null) qBtn.setText(hq ? "🔍\nعالية" : "🔍\nعادية");
                    if (tSpd != null) tSpd.setText(spdText());
                    if (bBtn != null) bBtn.setText(invText());
                }
            });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String code() {
        String c = codeF.getText().toString().trim();
        cmdCode = c;
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
                    found = findTv(c, 2500);
                }
                if (found != null) {
                    ip = found;
                    sp.edit().putString("ip", found).apply();
                    setStatus("✅ متصل بـ TV Box (" + found + ")");
                    if (sp.getBoolean("s_dirty", false) || !pullLevels(found, c)) syncLevels();
                } else {
                    setStatus("❌ ما لقيتش TV Box. تأكد: نفس الواي فاي، الكود صحيح، التطبيق مفتوح في TV Box.");
                }
            }
        }).start();
    }

    // خيط واحد دايم للأوامر: بلا خلق خيط جديد وبلا bindWifi/كتابة prefs مع كل ضغطة
    private final java.util.concurrent.ExecutorService cmdPool = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile String cmdCode;

    private Button zBtn;
    private float zl = 1f;

    private void sendCmd(final String cmd) {
        if ("zin".equals(cmd)) zl = Math.min(6f, zl + 0.5f);
        else if ("zout".equals(cmd)) zl = Math.max(1f, zl - 0.5f);
        else if ("zreset".equals(cmd)) zl = 1f;
        if (zBtn != null && cmd.startsWith("z")) ui.post(new Runnable() {
            @Override public void run() { zBtn.setText((zl == (int) zl ? String.valueOf((int) zl) : String.valueOf(zl)) + "x"); }
        });
        if ("stop".equals(cmd)) releaseCastLocks();
        if (cmdCode == null) cmdCode = code();
        final String c = cmdCode;
        cmdPool.execute(new Runnable() {
            @Override public void run() {
                String host = ip;
                if (host == null) {
                    bindWifi();
                    host = findTv(c, 2500);
                    if (host == null) { flag(cmd, true); setStatus("❌ اضغط اتصل أولا"); return; }
                    ip = host;
                }
                try {
                    HttpURLConnection h = (HttpURLConnection) new URL("http://" + host + ":" + Net.HTTP_PORT + "/ctl?cmd=" + cmd).openConnection();
                    h.setConnectTimeout(1500);
                    h.setReadTimeout(2500);
                    h.setRequestProperty("X-Code", c);
                    h.getResponseCode();
                    h.disconnect();
                    flag(cmd, false);
                } catch (Exception e) {
                    // مرة وحدة نعاود بلا ما نمسحو IP (الواي فاي ساعات كيتأخر شوية)
                    try {
                        HttpURLConnection h = (HttpURLConnection) new URL("http://" + host + ":" + Net.HTTP_PORT + "/ctl?cmd=" + cmd).openConnection();
                        h.setConnectTimeout(2500);
                        h.setReadTimeout(3000);
                        h.setRequestProperty("X-Code", c);
                        h.getResponseCode();
                        h.disconnect();
                        flag(cmd, false);
                    } catch (Exception e2) {
                        flag(cmd, true);
                        ip = null;
                        setStatus("❌ فقدت الاتصال، اضغط اتصل");
                    }
                }
            }
        });
    }

    // ---------------- apps + mirroring ----------------
    private View.OnClickListener openL(final String app) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) { sendOpen(app); }
        };
    }

    private String levelText() {
        String q = mlevel == 0 ? "منخفضة 854" : mlevel == 2 ? "Full HD 1920"
                : mlevel == 3 ? "فائقة 2560" : "عادية 1280";
        return "📺 دقة العرض\n" + q;
    }

    private String glassText() {
        return "🪟 زجاج الداتا شو\n" + (glassOn ? "مفعل ✅" : "ملغى");
    }

    private String compatText() {
        return "🛠 توافق (هواتف قديمة)\n" + (compat ? "مفعل ✅" : "ملغى");
    }

    private String rotText() {
        return "🔄 تدوير الشاشة\n" + (autoRot ? "تلقائي ✅" : "ملغى");
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
                        if (ip == null) ip = findTv(c, 2500);
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
        // عرض الهاتف (يوتيوب...) كيبدا على الأقل بـ Full HD باش الألوان والكتابة تبقى نقية
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
                if (ip == null) ip = findTv(c, 2500);
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
                        if (ip == null) ip = findTv(c, 2500);
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
        if (res == RESULT_OK && data != null && data.getData() != null) {
            if (req == 1) { playFromPhone(data.getData(), displayName(data.getData())); return; }   // الفيديو كيتبث فالحين بلا نسخ
            String t = req == 2 ? "image" : req == 3 ? "pdf" : null;
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
                        if (ip == null) ip = findTv(codeNow, 2500);
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
        return uploadTo("/send", "", host, code, uri, type, name, size);
    }

    private int uploadTo(String path, String tag, String host, String code, Uri uri, String type, String name, long size) throws Exception {
        android.net.wifi.WifiManager.WifiLock wl = null;
        android.os.PowerManager.WakeLock wk = null;
        try {
            wl = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).createWifiLock(
                    Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvlink-up");
            wl.acquire();
            wk = ((android.os.PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "tvlink:up");
            wk.acquire(6 * 60 * 60 * 1000L);
        } catch (Exception ignored) {}
        try {
            URL url = new URL("http://" + host + ":" + Net.HTTP_PORT + path + "?type=" + type
                    + (type.equals("video") ? "" : "&exam=1")
                    + "&name=" + URLEncoder.encode(name, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(size);
            c.setRequestProperty("X-Code", code);
            c.setConnectTimeout(5000);
            c.setReadTimeout(600000);
            InputStream in = getContentResolver().openInputStream(uri);
            OutputStream o = new java.io.BufferedOutputStream(c.getOutputStream(), 1 << 18);
            byte[] buf = new byte[1 << 18];
            long done = 0;
            long t0 = System.currentTimeMillis();
            long lastUi = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastUi > 700) {
                    lastUi = now;
                    int pct = size > 0 ? (int) (done * 100 / size) : 0;
                    double mbs = done / 1048576.0 / Math.max(1, (now - t0) / 1000.0);
                    setStatus(tag + "كنبعث... " + pct + "%  (" + (done >> 20) + "/" + (size >> 20) + " MB، " + String.format(java.util.Locale.US, "%.1f", mbs) + " MB/s)");
                }
            }
            o.flush();
            o.close();
            in.close();
            int rc = c.getResponseCode();
            c.disconnect();
            return rc;
        } finally {
            try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) {}
            try { if (wk != null && wk.isHeld()) wk.release(); } catch (Exception ignored) {}
        }
    }

    // ---------------- مجلد الداتاشو (فالهاتف) ----------------
    private String libHost(String code) {
        bindWifi();
        if (ip != null) {
            try { if (get(ip, code, "/ping") != 200) ip = null; } catch (Exception e) { ip = null; }
        }
        if (ip == null) ip = findTv(code, 2500);
        return ip;
    }

    private String phoneIp() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String nn = ni.getName();
                if (!(nn.startsWith("wlan") || nn.startsWith("ap") || nn.startsWith("swlan") || nn.startsWith("eth"))) continue;
                for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // الفيديو كيبقى فالهاتف وكيتبث لـ TV Box بالشبكة المحلية: كيبدا فالحين، وكتقدر تقدم وترجع فيه
    private void playFromPhone(final Uri doc, final String name) {
        final String c = code();
        holdCastLocks();
        setStatus("كنجهز " + name + "...");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String host = libHost(c);
                    if (host == null) { setStatus("كنقلب على TV Box..."); host = libHost(c); }
                    if (host == null) { setStatus("❌ ما لقيتش TV Box. اضغط اتصل."); return; }
                    String me = phoneIp();
                    if (me == null) { setStatus("❌ ما لقيتش عنوان الهاتف فالواي فاي"); return; }
                    Intent svc = new Intent(SenderActivity.this, MediaServerService.class);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
                    String url = "http://" + me + ":" + MediaServerService.PORT + "/v?c=" + URLEncoder.encode(c, "UTF-8")
                            + "&u=" + URLEncoder.encode(doc.toString(), "UTF-8");
                    int rc = get(host, c, "/playurl?u=" + URLEncoder.encode(url, "UTF-8"));
                    setStatus(rc == 200 ? "▶ كيتعرض: " + name : "❌ خطأ " + rc + " (واش TV Box عندو آخر نسخة؟)");
                    if (rc == 200) showVideoBar();
                } catch (Exception e) {
                    ip = null;
                    setStatus("❌ فشل. تأكد من الواي فاي واضغط اتصل.");
                }
            }
        }).start();
    }
}
