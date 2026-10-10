package com.tvlink.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * طبقة شفافة فوق الداتا شو: فيها فقط مسطرة السبورة (93 سم).
 * الإيموجي المتحركة (سيارة/قرد/حصان...) تم حذفها نهائياً من كل الملفات.
 */
public class FxView extends View {
    private boolean ruler;   // مسطرة السبورة: 90 سم مقسمة كل 10 سم

    public void toggleRuler() {
        ruler = !ruler;
        if (ruler) setVisibility(VISIBLE);
        else setVisibility(INVISIBLE);
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

    public FxView(Context c) {
        super(c);
        setClickable(false);
        setFocusable(false);
        setVisibility(INVISIBLE);
    }

    @Override public boolean onTouchEvent(MotionEvent e) { return false; }

    public void clear() {
        if (!ruler) setVisibility(INVISIBLE);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (ruler) drawRuler(c);
    }
}
