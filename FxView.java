package com.tvlink.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * طبقة إيموجي متحركة فوق الداتا شو (مدة 5 ثواني ثم تختفي وحدها).
 * شفافة بالكامل ولا تلمس الامتحان ولا تستقبل اللمس.
 */
public class FxView extends View {
    public static final int DURATION_MS = 5000;

    // نوع الحركة
    private static final int WALK = 0, CLIMB = 1, FLY = 2, BURST = 3, FLOAT = 4, SAIL = 5, RAIN = 6;

    static class Scene {
        final String name; final int kind; final String[] em; final boolean facesLeft;
        Scene(String name, int kind, boolean facesLeft, String... em) {
            this.name = name; this.kind = kind; this.facesLeft = facesLeft; this.em = em;
        }
    }

    // 22 مشهد مختلف
    public static final Scene[] SCENES = {
            new Scene("سيارة", WALK, true, "🚗"),
            new Scene("قرد", CLIMB, false, "🐒"),
            new Scene("حصان", WALK, true, "🐎"),
            new Scene("باخرة", SAIL, false, "🚢"),
            new Scene("دراجة", WALK, true, "🚲"),
            new Scene("طفل", WALK, false, "🧒"),
            new Scene("سلحفاة", WALK, true, "🐢"),
            new Scene("أزهار", BURST, false, "🌸", "🌼", "🌷", "🌺", "🌻", "💐"),
            new Scene("قلوب", FLOAT, false, "❤️", "💖", "💗", "💕"),
            new Scene("طائرة", FLY, false, "✈️"),
            new Scene("أسد", WALK, true, "🦁"),
            new Scene("فيل", WALK, true, "🐘"),
            new Scene("زرافة", WALK, true, "🦒"),
            new Scene("قط", WALK, true, "🐈"),
            new Scene("كلب", WALK, true, "🐕"),
            new Scene("دجاجة", WALK, true, "🐔"),
            new Scene("بقرة", WALK, true, "🐄"),
            new Scene("ورود", BURST, false, "🌹", "🥀", "🌺", "🏵️", "🌷"),
            new Scene("فراشات", FLY, false, "🦋"),
            new Scene("نجوم", BURST, false, "⭐", "✨", "🌟", "💫"),
            new Scene("بالونات", FLOAT, false, "🎈", "🎉", "🎊"),
            new Scene("طلاب فرحانين", RAIN, false, "🎓", "📚", "✏️", "🏅", "👏")
    };

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private ValueAnimator anim;
    private boolean ruler;   // مسطرة السبورة: 90 سم مقسمة كل 10 سم

    public void toggleRuler() {
        ruler = !ruler;
        if (ruler) setVisibility(VISIBLE);
        else if (anim == null || !anim.isRunning()) setVisibility(GONE);
        invalidate();
    }

    private void drawRuler(Canvas c) {
        float w = getWidth(), h = getHeight(), cell = w / 9.3f;   // عرض السبورة 93 سم: خط كل 10 سم
        Paint l = new Paint(Paint.ANTI_ALIAS_FLAG);
        l.setStyle(Paint.Style.STROKE);
        l.setColor(0xCCFFEB3B);
        l.setStrokeWidth(Math.max(2f, w / 500f));
        for (int i = 0; i <= 9; i++) c.drawLine(cell * i, 0, cell * i, h, l);
        for (float y = 0; y <= h; y += cell) c.drawLine(0, y, w, y, l);
        l.setColor(0xFFFF5252);
        l.setStrokeWidth(Math.max(4f, w / 250f));
        c.drawRect(2, 2, w - 2, h - 2, l);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0xFFFFFFFF);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTextSize(Math.max(18f, w / 40f));
        t.setShadowLayer(4f, 0, 0, 0xFF000000);
        for (int i = 1; i <= 9; i++) c.drawText(String.valueOf(i * 10), cell * i, t.getTextSize() * 1.3f, t);
        t.setTextSize(Math.max(24f, w / 28f));
        c.drawText("93 cm", w / 2f, h - t.getTextSize(), t);
    }
    private float t;                 // 0..1
    private Scene scene;
    private boolean leftToRight;
    private float baseSize;

    // جسيمات (BURST / FLOAT / RAIN)
    private float[] px, py, pvx, pvy, ps, pr, pw, pd;   // موقع، سرعة، حجم، دوران، سرعة دوران، تأخير
    private String[] pe;

    // ترتيب عشوائي بلا تكرار: كل ضغطة مشهد مختلف
    private final List<Integer> bag = new ArrayList<Integer>();
    private int last = -1;

    public FxView(Context c) {
        super(c);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT);
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);
    }

    @Override public boolean onTouchEvent(MotionEvent e) { return false; }

    public int nextRandom() {
        if (bag.isEmpty()) {
            for (int i = 0; i < SCENES.length; i++) bag.add(i);
            Collections.shuffle(bag, rnd);
            if (bag.size() > 1 && bag.get(0) == last) { Integer x = bag.remove(0); bag.add(x); }
        }
        last = bag.remove(0);
        return last;
    }

    public void playRandom() { play(nextRandom()); }

    public void play(int id) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (id < 0 || id >= SCENES.length) id = nextRandom();
        last = id;
        scene = SCENES[id];
        leftToRight = rnd.nextBoolean();
        baseSize = Math.min(getWidth(), getHeight()) * 0.26f;
        if (scene.kind == BURST || scene.kind == FLOAT || scene.kind == RAIN) initParticles();
        if (anim != null) anim.cancel();
        setVisibility(VISIBLE);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(DURATION_MS);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) { t = (Float) a.getAnimatedValue(); invalidate(); }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) { if (!cancelled) { scene = null; if (!ruler) setVisibility(GONE); invalidate(); } }
        });
        anim.start();
    }

    public void clear() {
        if (anim != null) anim.cancel();
        if (!ruler) setVisibility(GONE);
    }

    private void initParticles() {
        int n = scene.kind == BURST ? 34 : scene.kind == FLOAT ? 22 : 26;
        px = new float[n]; py = new float[n]; pvx = new float[n]; pvy = new float[n];
        ps = new float[n]; pr = new float[n]; pw = new float[n]; pd = new float[n];
        pe = new String[n];
        float w = getWidth(), h = getHeight();
        for (int i = 0; i < n; i++) {
            pe[i] = scene.em[rnd.nextInt(scene.em.length)];
            ps[i] = baseSize * (0.35f + rnd.nextFloat() * 0.55f);
            pw[i] = (rnd.nextFloat() - 0.5f) * 360f;
            if (scene.kind == BURST) {
                // تنبثق من الوسط ثم تتشتت كالضوء
                double ang = rnd.nextDouble() * Math.PI * 2;
                float sp = (0.25f + rnd.nextFloat() * 0.75f) * Math.max(w, h) * 0.62f;
                pvx[i] = (float) Math.cos(ang) * sp;
                pvy[i] = (float) Math.sin(ang) * sp;
                pd[i] = rnd.nextFloat() * 0.08f;
            } else if (scene.kind == FLOAT) {
                // تظهر من الوسط وتطلع وتتفرق فوق الامتحان
                px[i] = w / 2f + (rnd.nextFloat() - 0.5f) * w * 0.12f;
                py[i] = h / 2f;
                pvx[i] = (rnd.nextFloat() - 0.5f) * w * 0.55f;
                pvy[i] = -(0.18f + rnd.nextFloat() * 0.6f) * h;
                pd[i] = rnd.nextFloat() * 0.35f;
            } else {
                // مطر خفيف من الأعلى
                px[i] = rnd.nextFloat() * w;
                py[i] = -baseSize;
                pvx[i] = (rnd.nextFloat() - 0.5f) * w * 0.08f;
                pvy[i] = (0.55f + rnd.nextFloat() * 0.6f) * h;
                pd[i] = rnd.nextFloat() * 0.45f;
            }
        }
    }

    private static float easeOutBack(float x) {
        float c1 = 1.70158f, c3 = c1 + 1f;
        float y = x - 1f;
        return 1f + c3 * y * y * y + c1 * y * y;
    }

    private static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    @Override
    protected void onDraw(Canvas c) {
        if (ruler) drawRuler(c);
        if (scene == null || getVisibility() != VISIBLE) return;
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        switch (scene.kind) {
            case WALK: drawWalk(c, w, h, cx, cy); break;
            case CLIMB: drawClimb(c, w, h, cx, cy); break;
            case FLY: drawFly(c, w, h, cx, cy); break;
            case SAIL: drawSail(c, w, h, cx, cy); break;
            default: drawParticles(c, w, h); break;
        }
    }

    private void emoji(Canvas c, String s, float x, float y, float size, float rot, boolean flip, float alpha) {
        p.setTextSize(size);
        p.setAlpha((int) (255 * clamp01(alpha)));
        c.save();
        c.translate(x, y);
        c.rotate(rot);
        if (flip) c.scale(-1f, 1f);
        // مركزة عمودية تقريبية
        c.drawText(s, 0, size * 0.35f, p);
        c.restore();
    }

    // تنبثق من وسط الامتحان ثم تمشي كأنها في الطريق حتى تخرج من الشاشة
    private void drawWalk(Canvas c, float w, float h, float cx, float cy) {
        float pop = 0.14f;
        float size = baseSize;
        float x, y, rot = 0f, a = 1f;
        float roadY = h * 0.72f;
        boolean flip = leftToRight == scene.facesLeft;   // اتجاه النظر حسب جهة المشي
        if (t < pop) {
            float k = easeOutBack(t / pop);
            size = baseSize * k;
            x = cx; y = cy;
        } else {
            float k = (t - pop) / (1f - pop);
            float e = k * k * (3f - 2f * k) * 0.35f + k * 0.65f;     // انسياب ناعم
            float x0 = cx, x1 = leftToRight ? w + baseSize : -baseSize;
            x = x0 + (x1 - x0) * e;
            float down = clamp01(k * 3f);
            y = cy + (roadY - cy) * (down * down * (3f - 2f * down));
            y -= Math.abs((float) Math.sin(k * Math.PI * 9)) * baseSize * 0.12f;   // خطوات/اهتزاز الطريق
            rot = (float) Math.sin(k * Math.PI * 9) * 3.5f;
            if (k > 0.9f) a = 1f - (k - 0.9f) / 0.1f;
        }
        emoji(c, scene.em[0], x, y, size, rot, flip, a);
    }

    // القرد: ينبثق ثم يتسلق للأعلى ويتمايل حتى يختفي
    private void drawClimb(Canvas c, float w, float h, float cx, float cy) {
        float pop = 0.14f;
        float size = baseSize, x, y, a = 1f, rot = 0f;
        if (t < pop) {
            size = baseSize * easeOutBack(t / pop);
            x = cx; y = cy;
        } else {
            float k = (t - pop) / (1f - pop);
            y = cy - (cy + baseSize) * k * (0.5f + 0.5f * k);
            x = cx + (float) Math.sin(k * Math.PI * 7) * baseSize * 0.45f;
            rot = (float) Math.sin(k * Math.PI * 7) * 14f;
            if (k > 0.88f) a = 1f - (k - 0.88f) / 0.12f;
        }
        emoji(c, scene.em[0], x, y, size, rot, false, a);
    }

    // الطائرة / الفراشات: تنبثق ثم تطير في منحنى نحو الأعلى
    private void drawFly(Canvas c, float w, float h, float cx, float cy) {
        int n = scene.em[0].equals("🦋") ? 5 : 1;
        for (int i = 0; i < n; i++) {
            float delay = i * 0.06f;
            float tt = clamp01((t - delay) / (1f - delay));
            float pop = 0.14f;
            float size = baseSize * (n > 1 ? 0.6f : 1f), x, y, rot = 0f, a = 1f;
            float ox = n > 1 ? (i - 2) * baseSize * 0.35f : 0f;
            if (tt < pop) {
                size *= easeOutBack(tt / pop);
                x = cx + ox; y = cy;
            } else {
                float k = (tt - pop) / (1f - pop);
                float dir = leftToRight ? 1f : -1f;
                x = cx + ox + dir * (w * 0.62f) * k;
                y = cy - (h * 0.62f) * k + (float) Math.sin(k * Math.PI * (n > 1 ? 6 : 2)) * baseSize * (n > 1 ? 0.5f : 0.15f);
                rot = n > 1 ? (float) Math.sin(k * Math.PI * 6) * 20f : (leftToRight ? -22f : 22f) - 45f * 0f;
                size *= 1f - 0.35f * k;
                if (k > 0.88f) a = 1f - (k - 0.88f) / 0.12f;
            }
            // ✈️ يشير لليمين-الأعلى افتراضيا؛ نعكسو إلى اليسار عند الحاجة
            emoji(c, scene.em[0], x, y, size, rot, !leftToRight && n == 1, a);
        }
    }

    // الباخرة: تنبثق ثم تبحر ببطء وتتمايل على الأمواج
    private void drawSail(Canvas c, float w, float h, float cx, float cy) {
        float pop = 0.14f, size = baseSize, x, y, a = 1f, rot = 0f;
        if (t < pop) {
            size = baseSize * easeOutBack(t / pop);
            x = cx; y = cy;
        } else {
            float k = (t - pop) / (1f - pop);
            float x1 = leftToRight ? w + baseSize : -baseSize;
            x = cx + (x1 - cx) * k;
            float down = clamp01(k * 3f);
            y = cy + (h * 0.78f - cy) * down + (float) Math.sin(k * Math.PI * 8) * baseSize * 0.08f;
            rot = (float) Math.sin(k * Math.PI * 8) * 5f;
            if (k > 0.9f) a = 1f - (k - 0.9f) / 0.1f;
        }
        emoji(c, scene.em[0], x, y, size, rot, !leftToRight, a);
        if (t >= pop) {
            // أمواج صغيرة تحت الباخرة
            for (int i = -2; i <= 2; i++) emoji(c, "🌊", x + i * baseSize * 0.5f, y + baseSize * 0.55f, baseSize * 0.4f, 0f, false, a * 0.9f);
        }
    }

    private void drawParticles(Canvas c, float w, float h) {
        if (px == null) return;
        float cx = w / 2f, cy = h / 2f;
        for (int i = 0; i < px.length; i++) {
            float tt = clamp01((t - pd[i]) / (1f - pd[i]));
            if (tt <= 0f) continue;
            float x, y, a, size = ps[i];
            if (scene.kind == BURST) {
                float k = (float) (1.0 - Math.pow(1.0 - tt, 2.2));   // تنطلق بسرعة ثم تتباطأ كالضوء
                x = cx + pvx[i] * k;
                y = cy + pvy[i] * k + 0.18f * h * tt * tt;
                size *= tt < 0.12f ? easeOutBack(tt / 0.12f) : 1f;
                a = tt < 0.7f ? 1f : 1f - (tt - 0.7f) / 0.3f;
            } else if (scene.kind == FLOAT) {
                x = px[i] + pvx[i] * tt + (float) Math.sin(tt * 9 + i) * baseSize * 0.12f;
                y = py[i] + pvy[i] * tt;
                size *= tt < 0.12f ? easeOutBack(tt / 0.12f) : 1f;
                a = tt < 0.75f ? 1f : 1f - (tt - 0.75f) / 0.25f;
            } else {
                x = px[i] + pvx[i] * tt + (float) Math.sin(tt * 8 + i) * baseSize * 0.1f;
                y = py[i] + pvy[i] * tt;
                a = tt < 0.85f ? 1f : 1f - (tt - 0.85f) / 0.15f;
            }
            emoji(c, pe[i], x, y, size, pw[i] * tt, false, a);
        }
    }
}
