package com.tvlink.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

public class ScreenService extends Service {
    private MediaProjection projection;
    private VirtualDisplay vd;
    private ImageReader reader;
    private HandlerThread ht;
    private Handler handler;
    private Socket sock;
    private DataOutputStream out;
    private volatile boolean running;
    private boolean cleaning;
    private long last;
    private Bitmap bmp;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        if (in == null || "stop".equals(in.getAction())) {
            cleanup();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundNotif();
        if (running) cleanup();
        final int rc = in.getIntExtra("rc", 0);
        final Intent data = in.getParcelableExtra("data");
        final String host = in.getStringExtra("ip");
        final String code = in.getStringExtra("code");
        final int level = in.getIntExtra("level", 1);
        running = true;
        ht = new HandlerThread("tvlink-cap");
        ht.start();
        handler = new Handler(ht.getLooper());
        handler.post(new Runnable() {
            @Override public void run() { setup(rc, data, host, code, level); }
        });
        return START_NOT_STICKY;
    }

    private void startForegroundNotif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("tvlink", "TV Link", NotificationManager.IMPORTANCE_LOW));
        }
        Intent stop = new Intent(this, ScreenService.class).setAction("stop");
        PendingIntent pi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "tvlink") : new Notification.Builder(this);
        b.setContentTitle("TV Link")
                .setContentText("عرض الشاشة على TV Box. اضغط للإيقاف")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    private void setup(int rc, Intent data, String host, String code, int level) {
        try {
            sock = new Socket();
            sock.connect(new InetSocketAddress(host, Net.HTTP_PORT), 5000);
            sock.setTcpNoDelay(true);
            out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream(), 1 << 16));
            out.write(("POST /stream HTTP/1.1\r\nX-Code: " + code + "\r\nContent-Length: 0\r\n\r\n").getBytes("UTF-8"));
            out.flush();

            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(rc, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopAll(); }
            }, handler);

            DisplayMetrics dm = new DisplayMetrics();
            ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
            int longSide = level == 0 ? 854 : level == 2 ? 1920 : 1280;
            float sc = Math.min(1f, (float) longSide / Math.max(dm.widthPixels, dm.heightPixels));
            final int w = Math.max(2, Math.round(dm.widthPixels * sc));
            final int h = Math.max(2, Math.round(dm.heightPixels * sc));
            final int jq = level == 0 ? 50 : level == 2 ? 75 : 62;

            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            vd = projection.createVirtualDisplay("tvlink", w, h, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader r) { onFrame(r, w, h, jq); }
            }, handler);
        } catch (Exception e) {
            stopAll();
        }
    }

    private void onFrame(ImageReader r, int w, int h, int jq) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null || !running) return;
            long wait = 70 - (SystemClock.uptimeMillis() - last);
            if (wait > 0) SystemClock.sleep(wait);
            last = SystemClock.uptimeMillis();
            Image.Plane pl = img.getPlanes()[0];
            ByteBuffer buf = pl.getBuffer();
            int bw = pl.getRowStride() / pl.getPixelStride();
            if (bmp == null || bmp.getWidth() != bw || bmp.getHeight() != h) {
                bmp = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888);
            }
            buf.rewind();
            bmp.copyPixelsFromBuffer(buf);
            Bitmap src = bw == w ? bmp : Bitmap.createBitmap(bmp, 0, 0, w, h);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(96 * 1024);
            src.compress(Bitmap.CompressFormat.JPEG, jq, bo);
            if (src != bmp) src.recycle();
            out.writeInt(bo.size());
            bo.writeTo(out);
            out.flush();
        } catch (Exception e) {
            stopAll();
        } finally {
            if (img != null) img.close();
        }
    }

    private void stopAll() {
        cleanup();
        stopSelf();
    }

    private synchronized void cleanup() {
        if (cleaning) return;
        cleaning = true;
        running = false;
        try { if (reader != null) reader.setOnImageAvailableListener(null, null); } catch (Exception ignored) {}
        try { if (vd != null) vd.release(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        try { if (ht != null) ht.quitSafely(); } catch (Exception ignored) {}
        vd = null;
        projection = null;
        reader = null;
        sock = null;
        ht = null;
        cleaning = false;
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }
}
