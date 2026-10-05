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
import android.os.PowerManager;
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
    private int level = 1;
    private boolean compat;
    private int jq = 62;
    private int dpi = 320;
    private long lastCheck, lastSum, lastSent;
    private final ImageReader.OnImageAvailableListener frameListener = new ImageReader.OnImageAvailableListener() {
        @Override public void onImageAvailable(ImageReader r) { onFrame(r); }
    };

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        if (in == null || "stop".equals(in.getAction())) {
            cleanup();
            stopSelf();
            return START_NOT_STICKY;
        }
        if ("level".equals(in.getAction())) {
            if (!running || handler == null) { stopSelf(); return START_NOT_STICKY; }
            level = in.getIntExtra("level", 1);
            handler.post(new Runnable() {
                @Override public void run() { reconfigure(false); }
            });
            return START_NOT_STICKY;
        }
        startForegroundNotif();
        if (running) cleanup();
        final int rc = in.getIntExtra("rc", 0);
        final Intent data = in.getParcelableExtra("data");
        final String host = in.getStringExtra("ip");
        final String code = in.getStringExtra("code");
        level = in.getIntExtra("level", 1);
        compat = in.getBooleanExtra("compat", false);
        running = true;
        ht = new HandlerThread("tvlink-cap");
        ht.start();
        handler = new Handler(ht.getLooper());
        handler.post(new Runnable() {
            @Override public void run() { setup(rc, data, host, code); }
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

    private void setup(int rc, Intent data, String host, String code) {
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
            reconfigure(true);
        } catch (Exception e) {
            stopAll();
        }
    }

    // كيحسب الحجم من دوران الشاشة الحالي والدقة المختارة، وكيطبقو بلا ما يوقف العرض
    private void reconfigure(boolean initial) {
        if (!running || projection == null) return;
        try {
            DisplayMetrics dm = new DisplayMetrics();
            ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
            dpi = dm.densityDpi;
            int longSide = level == 0 ? 854 : level == 1 ? 1280 : level == 2 ? 1920 : 2560;
            if (compat && longSide > 1280) longSide = 1280;
            float sc = Math.min(1f, (float) longSide / Math.max(dm.widthPixels, dm.heightPixels));
            int al = compat ? 16 : 2;
            int nw = Math.max(al, (Math.round(dm.widthPixels * sc) / al) * al);
            int nh = Math.max(al, (Math.round(dm.heightPixels * sc) / al) * al);
            jq = level == 0 ? 75 : level == 1 ? 90 : level == 2 ? 95 : 98;
            lastSum = 0;

            ImageReader nr = ImageReader.newInstance(nw, nh, PixelFormat.RGBA_8888, compat ? 3 : 2);
            nr.setOnImageAvailableListener(frameListener, handler);
            if (vd == null) {
                vd = projection.createVirtualDisplay("tvlink", nw, nh, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, nr.getSurface(), null, handler);
                if (vd == null) throw new IllegalStateException("no display");
            } else {
                vd.resize(nw, nh, dpi);
                vd.setSurface(nr.getSurface());
            }
            ImageReader old = reader;
            reader = nr;
            bmp = null;
            if (old != null) {
                try { old.setOnImageAvailableListener(null, null); } catch (Exception ignored) {}
                try { old.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            if (initial && !compat) {
                // أول محاولة فشلت: نعاودو بوضع التوافق
                compat = true;
                try { if (vd != null) vd.release(); } catch (Exception ignored) {}
                vd = null;
                reconfigure(true);
            } else {
                stopAll();
            }
        }
    }

    private void onFrame(ImageReader r) {
        Image img = null;
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastCheck > 400) {
                lastCheck = now;
                DisplayMetrics dm = new DisplayMetrics();
                ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
                boolean land = dm.widthPixels > dm.heightPixels;
                if (land != (r.getWidth() > r.getHeight())) {
                    Image t = r.acquireLatestImage();
                    if (t != null) t.close();
                    reconfigure(false);
                    return;
                }
            }
            img = r.acquireLatestImage();
            if (img == null || !running) return;
            // الهاتف مقفل: ما نبعثوش إطارات سوداء، كتبقى آخر صورة معروضة فالداتا شو
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return;
            long wait = (level >= 3 ? 130 : level == 2 ? 100 : 70) - (SystemClock.uptimeMillis() - last);
            if (wait > 0) SystemClock.sleep(wait);
            last = SystemClock.uptimeMillis();
            int w = r.getWidth();
            int h = r.getHeight();
            Image.Plane pl = img.getPlanes()[0];
            ByteBuffer buf = pl.getBuffer();
            int bw = pl.getRowStride() / pl.getPixelStride();
            if (bmp == null || bmp.getWidth() != bw || bmp.getHeight() != h) {
                bmp = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888);
            }
            // الشاشة ما تغيراتش؟ ما نعاودوش نرسلو (كيوفر الشبكة ويخلي الجودة العالية تخدم)
            long sum = 17;
            int cap = buf.limit();
            for (int i = 0; i < cap; i += 61) sum = sum * 31 + buf.get(i);
            if (sum == lastSum && SystemClock.uptimeMillis() - lastSent < 1500) return;
            lastSum = sum;
            buf.rewind();
            bmp.copyPixelsFromBuffer(buf);
            Bitmap src = bw == w ? bmp : Bitmap.createBitmap(bmp, 0, 0, w, h);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(96 * 1024);
            src.compress(Bitmap.CompressFormat.JPEG, jq, bo);
            if (src != bmp) src.recycle();
            out.writeInt(bo.size());
            bo.writeTo(out);
            out.flush();
            lastSent = SystemClock.uptimeMillis();
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
