package com.tvlink.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// سيرفر صغير فالهاتف: كيبث الفيديوهات ديال "مجلد الداتاشو" لـ TV Box (مع Range باش التقديم والتأخير يخدمو)
public class MediaServerService extends Service {
    public static final int PORT = 8766;
    private ServerSocket server;
    private volatile boolean running;
    private ExecutorService pool;
    private WifiManager.WifiLock wlock;
    private PowerManager.WakeLock wake;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        if (in != null && "stop".equals(in.getAction())) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        startFg();
        if (!running) startServer();
        return START_STICKY;
    }

    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            try { nm.deleteNotificationChannel("tvlinkmedia"); } catch (Exception ignored) {}
            NotificationChannel ch = new NotificationChannel("tvlinkmedia2", "TV Link (صامت)", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getService(this, 2, new Intent(this, MediaServerService.class).setAction("stop"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "tvlinkmedia2") : new Notification.Builder(this);
        b.setContentTitle("TV Link")
                .setContentText("الفيديوهات جاهزة للعرض. اضغط للإيقاف")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_SECRET);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(2, n);
    }

    private void startServer() {
        running = true;
        pool = Executors.newCachedThreadPool();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wlock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvlink-media");
            wlock.setReferenceCounted(false);
            wlock.acquire();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvlink:media");
            wake.setReferenceCounted(false);
            wake.acquire();
        } catch (Exception ignored) {}
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    server = new ServerSocket();
                    server.setReuseAddress(true);
                    server.bind(new InetSocketAddress(PORT));
                    while (running) {
                        final Socket s = server.accept();
                        pool.execute(new Runnable() { @Override public void run() { handle(s); } });
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static void err(OutputStream o, int code, String msg) throws Exception {
        o.write(("HTTP/1.1 " + code + " " + msg + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        o.flush();
    }

    private static String mimeOf(String n) {
        String l = n.toLowerCase();
        if (l.endsWith(".mp4") || l.endsWith(".m4v")) return "video/mp4";
        if (l.endsWith(".mkv")) return "video/x-matroska";
        if (l.endsWith(".webm")) return "video/webm";
        if (l.endsWith(".3gp")) return "video/3gpp";
        if (l.endsWith(".avi")) return "video/x-msvideo";
        if (l.endsWith(".mov")) return "video/quicktime";
        if (l.endsWith(".ts")) return "video/mp2t";
        return "application/octet-stream";
    }

    private void handle(Socket s) {
        ParcelFileDescriptor pfd = null;
        FileInputStream fis = null;
        try {
            s.setSoTimeout(15000);
            try { s.setSendBufferSize(1 << 20); } catch (Exception ignored) {}
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String line = readLine(in);
            if (line == null) return;
            String[] rl = line.split(" ");
            String range = null;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                if (h.toLowerCase().startsWith("range:")) range = h.substring(6).trim();
            }
            if (rl.length < 2) { err(out, 400, "Bad"); return; }
            boolean head = "HEAD".equals(rl[0]);
            Uri u = Uri.parse("http://x" + rl[1]);
            SharedPreferences sp = getSharedPreferences("tvlink", MODE_PRIVATE);
            String code = sp.getString("paircode", Net.DEFAULT_CODE);
            String tree = sp.getString("tree", null);
            String doc = u.getQueryParameter("u");
            if (!"/v".equals(u.getPath()) || !code.equals(u.getQueryParameter("c"))) { err(out, 403, "Forbidden"); return; }
            // ما كنخدمو غير الملفات اللي داخل مجلد الداتاشو
            String prepDir = new java.io.File(getFilesDir(), "dsh").getAbsolutePath();
            boolean isPrep = doc != null && doc.startsWith("file://" + prepDir + "/") && !doc.contains("..");
            // الملفات المشاركة (content://) مسموحة، والكود السري ديال الربط كيحميها
            boolean isShared = doc != null && doc.startsWith("content://") && !doc.contains("..");
            boolean inTree = doc != null && tree != null && doc.startsWith(tree);
            if (doc == null || (!isPrep && !isShared && !inTree)) { err(out, 403, "Forbidden"); return; }
            long size;
            if (isPrep) {
                java.io.File pf = new java.io.File(Uri.parse(doc).getPath());
                if (!pf.isFile()) { err(out, 404, "Not Found"); return; }
                fis = new FileInputStream(pf);
                size = pf.length();
            } else {
                Uri docUri = Uri.parse(doc);
                pfd = getContentResolver().openFileDescriptor(docUri, "r");
                if (pfd == null) { err(out, 404, "Not Found"); return; }
                size = pfd.getStatSize();
                fis = new FileInputStream(pfd.getFileDescriptor());
            }
            long start = 0, end = size - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String r = range.substring(6).split(",")[0].trim();
                int d = r.indexOf('-');
                String a = r.substring(0, d), b = r.substring(d + 1);
                if (a.isEmpty()) {
                    start = Math.max(0, size - Long.parseLong(b));
                } else {
                    start = Long.parseLong(a);
                    if (!b.isEmpty()) end = Math.min(end, Long.parseLong(b));
                }
                partial = true;
            }
            if (start >= size || end < start) {
                out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + size + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                out.flush();
                return;
            }
            String name = doc;
            int sl = Math.max(doc.lastIndexOf('/'), doc.lastIndexOf("%2F"));
            if (sl >= 0) name = doc.substring(sl);
            StringBuilder hd = new StringBuilder();
            hd.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            hd.append("Content-Type: ").append(mimeOf(name)).append("\r\n");
            hd.append("Accept-Ranges: bytes\r\n");
            hd.append("Content-Length: ").append(end - start + 1).append("\r\n");
            if (partial) hd.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n");
            hd.append("Connection: close\r\n\r\n");
            out.write(hd.toString().getBytes("UTF-8"));
            if (head) { out.flush(); return; }
            s.setSoTimeout(0);
            FileChannel ch = fis.getChannel();
            ch.position(start);
            ByteBuffer bb = ByteBuffer.allocate(512 * 1024);
            long left = end - start + 1;
            while (left > 0 && running) {
                bb.clear();
                bb.limit((int) Math.min(bb.capacity(), left));
                int n = ch.read(bb);
                if (n < 0) break;
                out.write(bb.array(), 0, n);
                left -= n;
            }
            out.flush();
        } catch (Exception ignored) {
            // TV Box سد الاتصال (تقديم/تأخير): عادي
        } finally {
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void shutdown() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        try { if (pool != null) pool.shutdownNow(); } catch (Exception ignored) {}
        try { if (wlock != null && wlock.isHeld()) wlock.release(); } catch (Exception ignored) {}
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }
}
