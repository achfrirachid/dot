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
import android.view.WindowManager;
import android.util.TypedValue;
import android.widget.FrameLayout;
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
        left.addView(vw(levelCell("🖋 غلظة الكتابة", "txt", 0, 10, 6), 1f));
        left.addView(vw(levelCell("🔎 حدة الصورة", "sha", 0, 10, 7), 1f));
        left.addView(vw(levelCell("🌓 إضاءة الوجوه", "gam", 0, 10, 5), 1f));
        LinearLayout lr1 = box(false);
        lr1.addView(hw(gbtn("📄\nامتحان", C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { preset(10, 5, 6, 5, 8, 8); }
        }), 1f));
        lr1.addView(hw(gbtn("🎬\nألوان", C_AMBER, new View.OnClickListener() {
            @Override public void onClick(View v) { preset(10, 5, 6, 7, 0, 3); }
        }), 1f));
        left.addView(vw(lr1, 1.1f));
        LinearLayout lr2 = box(false);
        lr2.addView(hw(gbtn("↺\nافتراضي", C_SLATE, new View.OnClickListener() {
            @Override public void onClick(View v) { preset(10, 5, 5, 5, 0, 0); setLevel("gam", 5); }
        }), 1f));
        lr2.addView(hw(gbtn("⚙️\nالوضع", C_PURPLE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                sp.edit().remove("mode").apply();
                startActivity(new Intent(SenderActivity.this, MainActivity.class).putExtra("choose", true));
                finish();
            }
        }), 1f));
        left.addView(vw(lr2, 1.1f));

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
        zr.addView(hw(holdBtn("🔍➖", "zout", C_ORANGE, 350), 1f));
        zr.addView(hw(gbtn("1x", C_ORANGE, ctl("zreset")), 0.8f));
        zr.addView(hw(holdBtn("🔍➕", "zin", C_ORANGE, 350), 1f));
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
        send.addView(vw(gbtn("🧩 2 أو 3 ملفات\n(الأفواج)", C_AMBER, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, 4);
            }
        }), 1.15f));
        send.addView(vw(gbtn("📁  مجلد الداتاشو", C_CYAN, new View.OnClickListener() {
            @Override public void onClick(View v) { openDataShowFolder(); }
        }), 1.3f));
        right.addView(vw(send, 5.2f));

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
                sp.edit().putInt("s_glass", glassOn ? 1 : 0).apply();
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
            ObjectAnimator a = ObjectAnimator.ofFloat(e, "translationY", 0f, -dp(5));
            a.setDuration(1100 + i * 140);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.start();
            anims.add(a);
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
        sp.edit().putInt("s_" + key, v).apply();
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
                    syncLevels();
                } else {
                    setStatus("❌ ما لقيتش TV Box. تأكد: نفس الواي فاي، الكود صحيح، التطبيق مفتوح في TV Box.");
                }
            }
        }).start();
    }

    // خيط واحد دايم للأوامر: بلا خلق خيط جديد وبلا bindWifi/كتابة prefs مع كل ضغطة
    private final java.util.concurrent.ExecutorService cmdPool = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile String cmdCode;

    private void sendCmd(final String cmd) {
        if (cmdCode == null) cmdCode = code();
        final String c = cmdCode;
        cmdPool.execute(new Runnable() {
            @Override public void run() {
                String host = ip;
                if (host == null) {
                    bindWifi();
                    host = findTv(c, 2500);
                    if (host == null) { setStatus("❌ اضغط اتصل أولا"); return; }
                    ip = host;
                }
                try {
                    HttpURLConnection h = (HttpURLConnection) new URL("http://" + host + ":" + Net.HTTP_PORT + "/ctl?cmd=" + cmd).openConnection();
                    h.setConnectTimeout(1500);
                    h.setReadTimeout(2500);
                    h.setRequestProperty("X-Code", c);
                    h.getResponseCode();
                    h.disconnect();
                } catch (Exception e) {
                    // مرة وحدة نعاود بلا ما نمسحو IP (الواي فاي ساعات كيتأخر شوية)
                    try {
                        HttpURLConnection h = (HttpURLConnection) new URL("http://" + host + ":" + Net.HTTP_PORT + "/ctl?cmd=" + cmd).openConnection();
                        h.setConnectTimeout(2500);
                        h.setReadTimeout(3000);
                        h.setRequestProperty("X-Code", c);
                        h.getResponseCode();
                        h.disconnect();
                    } catch (Exception e2) {
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
        if (mlevel < 2) {
            mlevel = 2;
            sp.edit().putInt("mlevel2", mlevel).apply();
            if (mBtn != null) mBtn.setText(levelText());
        }
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
        if (req == 4) {
            if (res == RESULT_OK && data != null) sendMulti(data);
            return;
        }
        if (req == 6) {
            if (res == RESULT_OK && data != null) onTreePicked(data);
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
                    if (ip == null) ip = findTv(codeNow, 2500);
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

    private void pickTree() {
        android.widget.Toast.makeText(this, "اختار أو أنشئ مجلد سميتو: مجلد الداتاشو", android.widget.Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, 6);
    }

    private void onTreePicked(Intent data) {
        Uri t = data.getData();
        if (t == null) return;
        try { getContentResolver().takePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        sp.edit().putString("tree", t.toString()).apply();
        openDataShowFolder();
    }

    private static String fileKind(String name, String mime) {
        String l = name.toLowerCase();
        if ("application/pdf".equals(mime) || l.endsWith(".pdf")) return "pdf";
        if ((mime != null && mime.startsWith("image")) || l.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp)$")) return "image";
        if ((mime != null && mime.startsWith("video")) || l.matches(".*\\.(mp4|m4v|mkv|webm|3gp|avi|mov|ts)$")) return "video";
        return null;
    }

    private void openDataShowFolder() {
        final String t = sp.getString("tree", null);
        if (t == null) { pickTree(); return; }
        final List<String[]> items = new ArrayList<String[]>();   // {نوع, اسم, uri, حجم}
        try {
            Uri tree = Uri.parse(t);
            Uri kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                    android.provider.DocumentsContract.getTreeDocumentId(tree));
            Cursor cu = getContentResolver().query(kids, new String[]{
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_SIZE,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (cu != null) {
                while (cu.moveToNext()) {
                    String k = fileKind(cu.getString(1), cu.getString(3));
                    if (k == null) continue;
                    Uri du = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, cu.getString(0));
                    items.add(new String[]{k, cu.getString(1), du.toString(), String.valueOf(cu.getLong(2))});
                }
                cu.close();
            }
        } catch (Exception e) {
            sp.edit().remove("tree").apply();
            setStatus("❌ ما قدرتش نقرا المجلد. اختارو من جديد.");
            pickTree();
            return;
        }
        java.util.Collections.sort(items, new java.util.Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) { return a[1].compareToIgnoreCase(b[1]); }
        });
        final android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("📁 مجلد الداتاشو (" + items.size() + ")");
        b.setNeutralButton("📂 تغيير المجلد", new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int w) { pickTree(); }
        });
        b.setNegativeButton("إغلاق", null);
        b.setPositiveButton("⚙ تجهيز للداتاشو", new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int w) { startPrep(items); }
        });
        if (items.isEmpty()) {
            b.setMessage("المجلد خاوي. حمل الفيديوهات بـ NewPipe فهاد المجلد (صيغة MP4)، ومن بعد رجع هنا.");
            b.show();
            return;
        }
        final List<String> labels = new ArrayList<String>();
        for (String[] it : items) {
            long mb = 0;
            try { mb = Long.parseLong(it[3]) >> 20; } catch (Exception ignored) {}
            String icon = it[0].equals("video") ? "🎬" : it[0].equals("pdf") ? "📄" : "🖼";
            String ready = (it[0].equals("video") && preparedFile(it) != null) ? " ✅" : "";
            labels.add(icon + "  " + it[1] + ready + (mb > 0 ? "   (" + mb + " MB)" : ""));
        }
        android.widget.ListView lv = new android.widget.ListView(this);
        lv.setAdapter(new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels));
        lv.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        b.setView(lv);
        final android.app.AlertDialog dlg = b.create();
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> a, View v, int pos, long id) {
                String[] it = items.get(pos);
                dlg.dismiss();
                if (it[0].equals("video")) {
                    java.io.File pf = preparedFile(it);
                    if (pf != null) playFromPhone(Uri.fromFile(pf), it[1]);
                    else playFromPhone(Uri.parse(it[2]), it[1]);
                }
                else sendUri(Uri.parse(it[2]), it[0]);
            }
        });
        dlg.show();
    }

    // النسخة الجاهزة (H.264 576p) إلا كانت كاينة
    private java.io.File preparedFile(String[] it) {
        long sz = 0;
        try { sz = Long.parseLong(it[3]); } catch (Exception ignored) {}
        java.io.File f = Prep.outFor(this, it[1], sz);
        return (f.exists() && f.length() > 0) ? f : null;
    }

    private Prep prep;

    // تجهيز كل الفيديوهات اللي بعدا ما تجهزوش: كيتحولو مرة وحدة فالهاتف، وفالقسم كيتشغلو فالحين
    private void startPrep(List<String[]> items) {
        List<Prep.Job> jobs = new ArrayList<Prep.Job>();
        for (String[] it : items) {
            if (!it[0].equals("video")) continue;
            long sz = 0;
            try { sz = Long.parseLong(it[3]); } catch (Exception ignored) {}
            java.io.File o = Prep.outFor(this, it[1], sz);
            if (!(o.exists() && o.length() > 0)) jobs.add(new Prep.Job(Uri.parse(it[2]), it[1], o));
        }
        if (jobs.isEmpty()) { setStatus("✅ كل الفيديوهات جاهزة"); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setStatus("⚙ كنبدا التجهيز (" + jobs.size() + " فيديو)... خلي الشاشة مفتوحة");
        prep = new Prep(this, jobs, new Prep.Listener() {
            @Override public void onProgress(int index, int total, String name, int percent) {
                setStatus("⚙ " + index + "/" + total + " · " + percent + "%  " + name);
            }
            @Override public void onItemDone(String name, boolean ok) {
                if (!ok) setStatus("❌ ما تجهزش: " + name);
            }
            @Override public void onAllDone(int okCount, int total) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setStatus("✅ تجهزو " + okCount + "/" + total + " — ضغط على 📁 مجلد الداتاشو وختار الفيديو (✅)");
            }
        });
        prep.start();
    }

    // الفيديو كيبقى فالهاتف وكيتبث لـ TV Box بالشبكة المحلية: كيبدا فالحين، وكتقدر تقدم وترجع فيه
    private void playFromPhone(final Uri doc, final String name) {
        final String c = code();
        setStatus("كنجهز " + name + "...");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String host = libHost(c);
                    if (host == null) { setStatus("❌ ما لقيتش TV Box. اضغط اتصل."); return; }
                    String me = phoneIp();
                    if (me == null) { setStatus("❌ ما لقيتش عنوان الهاتف فالواي فاي"); return; }
                    Intent svc = new Intent(SenderActivity.this, MediaServerService.class);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
                    String url = "http://" + me + ":" + MediaServerService.PORT + "/v?c=" + URLEncoder.encode(c, "UTF-8")
                            + "&u=" + URLEncoder.encode(doc.toString(), "UTF-8");
                    int rc = get(host, c, "/playurl?u=" + URLEncoder.encode(url, "UTF-8"));
                    setStatus(rc == 200 ? "▶ كيتعرض: " + name : "❌ خطأ " + rc + " (واش TV Box عندو آخر نسخة؟)");
                } catch (Exception e) {
                    ip = null;
                    setStatus("❌ فشل. تأكد من الواي فاي واضغط اتصل.");
                }
            }
        }).start();
    }
}
