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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

public class ScreenService extends Service {
    public static volatile boolean RUNNING;   // الواجهة تعرف واش العرض شغال باش تطبق الدقة فورا
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
    private int dsMode = 0;   // 0 = كيف كان · 1 = أنسب للداتاشو · 2 = صافية جدا
    private int jq = 62;
    private int dpi = 320;
    private long lastCheck, lastSum, lastSent;
    private int baseJq = 62, curJq = 62;
    private long extraWait = 0;
    private int lastW, lastH;
    // بعد ثبات الشاشة (امتحان/PDF/سبورة) نعاودو نبعثو نفس الصورة بأعلى جودة باش الكتابة تبان صافية
    private final Runnable sharp = new Runnable() {
        @Override public void run() {
            try {
                if (!running || dsMode == 0 || bmp == null || out == null || lastW <= 0) return;
                if (bmp.getWidth() < lastW || bmp.getHeight() < lastH) return;
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isInteractive()) return;
                Bitmap src = bmp.getWidth() == lastW && bmp.getHeight() == lastH ? bmp
                        : Bitmap.createBitmap(bmp, 0, 0, lastW, lastH);
                ByteArrayOutputStream bo = new ByteArrayOutputStream(256 * 1024);
                src.compress(Bitmap.CompressFormat.JPEG, 98, bo);
                if (src != bmp) src.recycle();
                out.writeInt(bo.size());
                bo.writeTo(out);
                out.flush();
                lastSent = SystemClock.uptimeMillis();
            } catch (Exception e) {
                stopAll();
            }
        }
    };
    private WifiManager.WifiLock wlock;
    private PowerManager.WakeLock wake;
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
            dsMode = in.getIntExtra("dsmode", dsMode);
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
        dsMode = in.getIntExtra("dsmode", 0);
        running = true;
        RUNNING = true;
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
            try { nm.deleteNotificationChannel("tvlink"); } catch (Exception ignored) {}
            NotificationChannel ch = new NotificationChannel("tvlink2", "TV Link (صامت)", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, ScreenService.class).setAction("stop");
        PendingIntent pi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "tvlink2") : new Notification.Builder(this);
        b.setContentTitle("TV Link")
                .setContentText("عرض الشاشة على TV Box. اضغط للإيقاف")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_SECRET);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    private void setup(int rc, Intent data, String host, String code) {
        try {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wlock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvlink");
                wlock.setReferenceCounted(false);
                wlock.acquire();
                PowerManager pm0 = (PowerManager) getSystemService(POWER_SERVICE);
                wake = pm0.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvlink:cap");
                wake.setReferenceCounted(false);
                wake.acquire();
            } catch (Exception ignored) {}
            sock = new Socket();
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { n.bindSocket(sock); break; }
                }
            } catch (Exception ignored) {}
            try { sock.setSendBufferSize(1 << 18); } catch (Exception ignored) {}
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
            int longSide = level == 0 ? 854 : level == 1 ? 1280 : level == 2 ? 1920 : level == 3 ? 2560 : 4096;
            if (compat && longSide > 1920) longSide = 1920;
            float sc;
            if (dsMode > 0) {
                // الداتاشو أصلياً XGA 1024×768 (4:3): نلتقطو بدقة أكبر منها (×1.5 أو ×1.9) باش التصغير يعطي حدة صافية
                boolean land0 = dm.widthPixels > dm.heightPixels;
                float bl = dsMode == 2 ? 1920f : 1536f, bs = dsMode == 2 ? 1440f : 1152f;
                float bw0 = land0 ? bl : bs, bh0 = land0 ? bs : bl;
                sc = Math.min(1f, Math.min(bw0 / dm.widthPixels, bh0 / dm.heightPixels));
            } else {
                sc = Math.min(1f, (float) longSide / Math.max(dm.widthPixels, dm.heightPixels));
            }
            int al = compat ? 16 : 2;
            int nw = Math.max(al, (Math.round(dm.widthPixels * sc) / al) * al);
            int nh = Math.max(al, (Math.round(dm.heightPixels * sc) / al) * al);
            baseJq = dsMode == 2 ? 97 : dsMode == 1 ? 95 : level == 0 ? 80 : level == 1 ? 88 : level == 2 ? 92 : 95;
            jq = curJq = baseJq;
            extraWait = 0;
            lastSum = 0;

            ImageReader nr = ImageReader.newInstance(nw, nh, PixelFormat.RGBA_8888, 2);
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
            long wait = (level >= 3 ? 70 : level == 2 ? 50 : 35) + extraWait - (SystemClock.uptimeMillis() - last);
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
            long t0 = SystemClock.uptimeMillis();
            out.writeInt(bo.size());
            bo.writeTo(out);
            out.flush();
            lastSent = SystemClock.uptimeMillis();
            // تكيف تلقائي: إلا الشبكة بطيئة كنخفضو الجودة والسرعة بلا ما يتبلوكا، وإلا رجعات سريعة كنرجعو
            long took = lastSent - t0;
            if (took > 150) {
                curJq = Math.max(dsMode > 0 ? 90 : 78, curJq - 4);
                extraWait = Math.min(120, extraWait + 15);
            } else if (took < 40) {
                curJq = Math.min(baseJq, curJq + 2);
                extraWait = Math.max(0, extraWait - 5);
            }
            jq = curJq;
            if (dsMode > 0) {
                lastW = w; lastH = h;
                handler.removeCallbacks(sharp);
                handler.postDelayed(sharp, 500);
            }
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
        RUNNING = false;
        try { if (reader != null) reader.setOnImageAvailableListener(null, null); } catch (Exception ignored) {}
        try { if (vd != null) vd.release(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        try { if (ht != null) ht.quitSafely(); } catch (Exception ignored) {}
        try { if (wlock != null && wlock.isHeld()) wlock.release(); } catch (Exception ignored) {}
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {}
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
