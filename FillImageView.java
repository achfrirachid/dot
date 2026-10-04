package com.tvlink.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drop-in replacement for the ImageView in ReceiverActivity.
 * - No more black bars: images / PDF pages / mirror frames fill the screen automatically.
 * - Portrait pages (exams, documents) fill the full WIDTH and scroll up/down (pu/pd from the phone).
 * - Text/image quality boost: high-quality resize to the real screen size + sharpening.
 *   For live mirror frames the boost is applied as soon as the picture stops changing.
 * Modes (cmd "imode" cycles): AUTO -> FIT -> FILL_WIDTH -> FILL.
 */
public class FillImageView extends ImageView {
    public static final int AUTO = 0, FIT = 1, FILL_WIDTH = 2, FILL = 3;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final int MAX_DIM = 4096;
    private static final double MAX_PIXELS = 6000000.0;

    /** true while showing a live mirror stream */
    public volatile boolean live;

    private boolean enhance = true;
    private int mode = AUTO, resolved = FIT;
    private int gen, taskSeq;
    private Bitmap src, shown;
    private float base = 1f, scrollOff = 0f, lastZ = -1f;
    private final Matrix mx = new Matrix();
    private Runnable pendingTask;

    public FillImageView(Context c) {
        super(c);
        super.setScaleType(ScaleType.MATRIX);
    }

    /** The old code sets FIT_CENTER; ignore it, this view handles scaling itself. */
    @Override public void setScaleType(ScaleType t) { }

    public void setEnhance(boolean on) { enhance = on; refresh(true); }
    public void setMode(int m) { mode = m; scrollOff = 0f; relayout(); refresh(true); }
    public void cycleMode() { setMode((mode + 1) % 4); }

    @Override public void setImageBitmap(Bitmap bm) {
        gen++;
        if (bm == null) {
            src = null; shown = null;
            super.setImageDrawable(null);
            return;
        }
        src = bm; shown = bm; scrollOff = 0f; lastZ = -1f;
        super.setImageBitmap(bm);
        relayout();
        prepare(live ? 400 : 0);
    }

    @Override public void setImageDrawable(Drawable d) {
        if (d == null) { gen++; src = null; shown = null; }
        super.setImageDrawable(d);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        relayout();
        refresh(true);
    }

    /** Call after the zoom changed so the sharp bitmap matches the new size. */
    public void refreshQuality() {
        if (src == null) return;
        float z = Math.max(1f, getScaleX());
        if (z == lastZ) return;
        prepare(0);
    }

    /** Scrolls the picture vertically inside the screen. Returns false if it could not move. */
    public boolean scrollContent(float d) {
        if (src == null) return false;
        float[] r = range();
        float n = Math.max(r[0], Math.min(r[1], scrollOff + d));
        boolean moved = Math.abs(n - scrollOff) > 0.5f;
        scrollOff = n;
        updateMatrix();
        return moved;
    }

    // ---------- layout ----------
    private void refresh(boolean force) {
        if (force) lastZ = -1f;
        prepare(0);
    }

    private void relayout() {
        if (src == null || getWidth() == 0 || getHeight() == 0) return;
        float vw = getWidth(), vh = getHeight();
        float bw = src.getWidth(), bh = src.getHeight();
        float ia = bw / bh;
        float r = ia / (vw / vh);
        resolved = mode;
        if (mode == AUTO) {
            if (r >= 0.85f && r <= 1.18f) resolved = FILL;          // almost screen-shaped: cover the screen
            else if (ia < 1f && !live) resolved = FILL_WIDTH;       // portrait page: full width, scroll
            else resolved = FIT;                                    // wide picture / live portrait: show all
        }
        if (resolved == FILL_WIDTH) base = vw / bw;
        else if (resolved == FILL) base = Math.max(vw / bw, vh / bh);
        else base = Math.min(vw / bw, vh / bh);
        float[] rg = range();
        scrollOff = Math.max(rg[0], Math.min(rg[1], scrollOff));
        updateMatrix();
    }

    private float[] range() {
        float ch = src.getHeight() * base, vh = getHeight();
        if (ch <= vh + 0.5f) return new float[]{0f, 0f};
        if (resolved == FILL_WIDTH) return new float[]{0f, ch - vh};
        return new float[]{-(ch - vh) / 2f, (ch - vh) / 2f};
    }

    private void updateMatrix() {
        if (src == null || shown == null || getWidth() == 0) return;
        float vw = getWidth(), vh = getHeight();
        float s = base * src.getWidth() / shown.getWidth();
        float cw = src.getWidth() * base, ch = src.getHeight() * base;
        float tx = (vw - cw) / 2f, ty;
        if (ch <= vh) ty = (vh - ch) / 2f;
        else if (resolved == FILL_WIDTH) ty = -scrollOff;
        else ty = (vh - ch) / 2f - scrollOff;
        mx.reset();
        mx.postScale(s, s);
        mx.postTranslate(tx, ty);
        setImageMatrix(mx);
    }

    // ---------- quality ----------
    private void prepare(long delayMs) {
        if (pendingTask != null) removeCallbacks(pendingTask);
        final Bitmap s = src;
        if (s == null || getWidth() == 0 || !enhance) return;
        float z = Math.max(1f, getScaleX());
        lastZ = z;
        float f = base * z;
        double tw = s.getWidth() * (double) f, th = s.getHeight() * (double) f;
        double k = Math.min(1.0, MAX_DIM / Math.max(tw, th));
        k = Math.min(k, Math.sqrt(MAX_PIXELS / (tw * th)));
        final int ftw = Math.max(1, (int) Math.round(tw * k));
        final int fth = Math.max(1, (int) Math.round(th * k));
        if (shown != null && shown != s && shown.getWidth() == ftw) return;
        final int g = gen;
        final int t = ++taskSeq;
        pendingTask = new Runnable() {
            @Override public void run() {
                EXEC.execute(new Runnable() {
                    @Override public void run() {
                        if (g != gen || t != taskSeq) return;
                        final Bitmap out;
                        try { out = process(s, ftw, fth); }
                        catch (Throwable e) { return; }
                        post(new Runnable() {
                            @Override public void run() {
                                if (g == gen && t == taskSeq && src == s) {
                                    shown = out;
                                    FillImageView.super.setImageBitmap(out);
                                    updateMatrix();
                                }
                            }
                        });
                    }
                });
            }
        };
        if (delayMs > 0) postDelayed(pendingTask, delayMs); else pendingTask.run();
    }

    private static Bitmap process(Bitmap s, int tw, int th) {
        Bitmap sc = scaleHQ(s, tw, th);
        Bitmap out = sharpen(sc, tw > s.getWidth() ? 1.0f : 0.7f);
        if (sc != s && sc != out) sc.recycle();
        return out;
    }

    private static Bitmap scaleHQ(Bitmap src, int dw, int dh) {
        Bitmap cur = src;
        while (cur.getWidth() / 2 >= dw && cur.getHeight() / 2 >= dh) {
            Bitmap next = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
            if (cur != src) cur.recycle();
            cur = next;
        }
        if (cur.getWidth() != dw || cur.getHeight() != dh) {
            Bitmap next = Bitmap.createScaledBitmap(cur, dw, dh, true);
            if (cur != src) cur.recycle();
            cur = next;
        }
        return cur;
    }

    /** Unsharp mask: crisp small text and thin lines. */
    private static Bitmap sharpen(Bitmap src, float amount) {
        final int w = src.getWidth(), h = src.getHeight();
        int[] p = new int[w * h];
        src.getPixels(p, 0, w, 0, 0, w, h);
        int[] o = new int[w * h];
        for (int y = 0; y < h; y++) {
            int ra = Math.max(y - 1, 0) * w, rb = y * w, rc = Math.min(y + 1, h - 1) * w;
            for (int x = 0; x < w; x++) {
                int xa = Math.max(x - 1, 0), xc = Math.min(x + 1, w - 1);
                int sr = 0, sg = 0, sb = 0, q;
                q = p[ra + xa]; sr += (q >> 16) & 255; sg += (q >> 8) & 255; sb += q & 255;
                q = p[ra + x];  sr += (q >> 16) & 255; sg += (q >> 8) & 255; sb += q & 255;
                q = p[ra + xc]; sr += (q >> 16) & 255; sg += (q >> 8) & 255; sb += q & 255;
                q = p[rb + xa]; sr += (q >> 16) & 255; sg += (q >> 8) & 255; sb += q & 255;
                q = p[rb + x];  sr += (q >> 16) & 255; sg += (q >> 8) & 255; sb += q & 255;
                q = p[rb + xc]; sr += (q >> 16) & 255; sg += (q >> 8) & 255; sb += q & 255;
                q = p[rc + xa]; sr += (q >> 16) & 255; sg += (q >> 8) & 255; sb += q & 255;
                q = p[rc + x];  sr += (q >> 16) & 255; sg += (q >> 8) & 255; sb += q & 255;
                q = p[rc + xc]; sr += (q >> 16) & 255; sg += (q >> 8) & 255; sb += q & 255;
                int c = p[rb + x];
                int cr = (c >> 16) & 255, cg = (c >> 8) & 255, cb = c & 255;
                o[rb + x] = (c & 0xFF000000)
                        | (clamp(cr + amount * (cr - sr / 9f)) << 16)
                        | (clamp(cg + amount * (cg - sg / 9f)) << 8)
                        | clamp(cb + amount * (cb - sb / 9f));
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(o, 0, w, 0, 0, w, h);
        return out;
    }

    private static int clamp(float v) {
        int i = (int) (v + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }
}
