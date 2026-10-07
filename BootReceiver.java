package com.tvlink.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** كيشعل ReceiverActivity بوحدو مني TV Box كتشعل (بلا ريموت). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent in) {
        // نفتحو غير فـ TV Box (اللي فيه leanback)، ماشي فالهاتف
        boolean tv = c.getPackageManager().hasSystemFeature("android.software.leanback")
                || c.getSharedPreferences("tvlink", Context.MODE_PRIVATE).getBoolean("is_tv", false);
        if (!tv) return;
        Intent i = new Intent(c, ReceiverActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            fallback(c, i);
        }
        // محاولة ثانية بعد 15 ثانية (بعض الأجهزة كتبقى ما واجداش فالبداية)
        final Context app = c.getApplicationContext();
        final Intent again = i;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { try { app.startActivity(again); } catch (Exception ignored) {} }
        }, 15000);
    }

    private void fallback(Context c, Intent i) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(new NotificationChannel("boot", "TV Link", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent pi = PendingIntent.getActivity(c, 1, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, "boot") : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("TV Link")
                    .setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true);
            nm.notify(7, b.build());
        } catch (Exception ignored) {}
    }
}
