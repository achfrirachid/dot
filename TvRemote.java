package com.tvlink.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Phone-side remote for the TV Box volume (uses the existing /ctl endpoint of ReceiverActivity).
 *
 * In SenderActivity (after you know the TV Box ip + code):
 *     layout.addView(TvRemote.volumeBar(this, ip, code));
 *     @Override public boolean onKeyDown(int k, KeyEvent e) {
 *         if (TvRemote.handleKey(k, ip, code)) return true;
 *         return super.onKeyDown(k, e);
 *     }
 * With handleKey the phone's own volume buttons control the TV Box volume.
 */
public final class TvRemote {
    private TvRemote() {}

    public static void send(final String ip, final String code, final String cmd) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    URL u = new URL("http://" + ip + ":" + Net.HTTP_PORT + "/ctl?cmd=" + cmd);
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(2000);
                    c.setReadTimeout(2000);
                    c.setRequestProperty("X-Code", code);
                    c.getResponseCode();
                    c.disconnect();
                } catch (Exception ignored) { }
            }
        }).start();
    }

    public static boolean handleKey(int keyCode, String ip, String code) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { send(ip, code, "volup"); return true; }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { send(ip, code, "voldown"); return true; }
        return false;
    }

    public static LinearLayout volumeBar(Context c, final String ip, final String code) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);
        row.addView(holdButton(c, "🔉  −", ip, code, "voldown", true));
        row.addView(holdButton(c, "🔇", ip, code, "mute", false));
        row.addView(holdButton(c, "🔊  +", ip, code, "volup", true));
        row.addView(holdButton(c, "MAX", ip, code, "volmax", false));
        return row;
    }

    /** Button; if repeat==true, holding it keeps sending. */
    private static Button holdButton(Context c, String text, final String ip, final String code,
                                     final String cmd, final boolean repeat) {
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable[] loop = new Runnable[1];
        loop[0] = new Runnable() {
            @Override public void run() { send(ip, code, cmd); h.postDelayed(loop[0], 250); }
        };
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(18);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        if (repeat) loop[0].run(); else send(ip, code, cmd);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        h.removeCallbacks(loop[0]);
                        return true;
                }
                return false;
            }
        });
        return b;
    }
}
