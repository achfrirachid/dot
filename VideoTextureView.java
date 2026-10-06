package com.tvlink.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;

// مشغل فيديو خفيف (MediaPlayer + TextureView): كيشغل الفيديو بجودته الأصلية بلا إعادة ترميز،
// وكيسمح بتطبيق السطوع/التباين/الألوان وتغيير السرعة. نفس أوامر VideoView اللي كان مستعمل.
public class VideoTextureView extends TextureView implements TextureView.SurfaceTextureListener {
    private MediaPlayer mp;
    private Surface surface;
    private Uri uri;
    private boolean prepared;
    private float speed = 1f;
    private int vw, vh;
    public boolean fill = false;   // false = النسبة الأصلية (بلا تمديد) / true = ملء الشاشة
    private MediaPlayer.OnPreparedListener prepL;
    private MediaPlayer.OnErrorListener errL;

    public VideoTextureView(Context c) {
        super(c);
        setSurfaceTextureListener(this);
    }

    public void setOnPreparedListener(MediaPlayer.OnPreparedListener l) { prepL = l; }
    public void setOnErrorListener(MediaPlayer.OnErrorListener l) { errL = l; }
    public void setVideoPath(String p) { setVideoURI(Uri.fromFile(new File(p))); }
    public void setVideoURI(Uri u) { uri = u; open(); }
    public void setFill(boolean f) { fill = f; applyTransform(); }

    private void open() {
        release();
        if (uri == null || surface == null) return;   // كنتسناو الشاشة تجهز
        try {
            mp = new MediaPlayer();
            mp.setSurface(surface);
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build());
            mp.setDataSource(getContext(), uri);
            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override public void onVideoSizeChanged(MediaPlayer m, int w, int h) {
                    vw = w; vh = h; applyTransform();
                }
            });
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) {
                    prepared = true;
                    vw = m.getVideoWidth(); vh = m.getVideoHeight();
                    applyTransform();
                    if (prepL != null) prepL.onPrepared(m);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    if (errL != null) errL.onError(m, what, extra);
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            if (errL != null) errL.onError(mp, -1, 0);
        }
    }

    private void release() {
        prepared = false;
        vw = 0; vh = 0;
        if (mp != null) {
            try { mp.reset(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
    }

    private void applyTransform() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || vw == 0 || vh == 0 || fill) { setTransform(null); return; }
        float va = (float) vw / vh, ra = (float) w / h;
        float sx = 1f, sy = 1f;
        if (va > ra) sy = ra / va; else sx = va / ra;
        Matrix m = new Matrix();
        m.setScale(sx, sy, w / 2f, h / 2f);
        setTransform(m);
    }

    public void start() {
        if (mp == null || !prepared) return;
        try { mp.start(); } catch (Exception ignored) {}
        applySpeed();
    }

    public void pause() {
        try { if (mp != null && prepared) mp.pause(); } catch (Exception ignored) {}
    }

    public boolean isPlaying() {
        try { return mp != null && prepared && mp.isPlaying(); } catch (Exception e) { return false; }
    }

    public void seekTo(int ms) {
        try { if (mp != null && prepared) mp.seekTo(Math.max(0, ms)); } catch (Exception ignored) {}
    }

    public int getCurrentPosition() {
        try { return mp != null && prepared ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    public int getDuration() {
        try { return mp != null && prepared ? mp.getDuration() : 0; } catch (Exception e) { return 0; }
    }

    public int getAudioSessionId() {
        try { return mp != null ? mp.getAudioSessionId() : 0; } catch (Exception e) { return 0; }
    }

    public void stopPlayback() {
        uri = null;
        release();
    }

    public float getSpeed() { return speed; }

    public void setSpeed(float s) {
        speed = s;
        if (isPlaying()) applySpeed();
    }

    private void applySpeed() {
        if (Build.VERSION.SDK_INT < 23 || mp == null || !prepared) return;
        try {
            android.media.PlaybackParams pp = mp.getPlaybackParams();
            pp.setSpeed(speed);
            mp.setPlaybackParams(pp);
        } catch (Exception ignored) {}
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        if (uri != null && mp == null) open();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { applyTransform(); }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release();
        if (surface != null) { try { surface.release(); } catch (Exception ignored) {} surface = null; }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
}
