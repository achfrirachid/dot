package com.tvlink.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.provider.Settings;

// تشغيل تلقائي فـ TV Box بلا ريموت: كيتفتح التطبيق وحدو ملي الجهاز كيشعل (وكيعاود المحاولة إلا فشل)
public class BootReceiver extends BroadcastReceiver {
    private static final String RETRY = "com.tvlink.app.BOOT_RETRY";

    @Override
    public void onReceive(Context ctx, Intent in) {
        try {
            String a = in == null ? null : in.getAction();
            SharedPreferences sp = ctx.getSharedPreferences("tvlink", Context.MODE_PRIVATE);
            if (!sp.getBoolean("autostart", true)) return;
            if (!isTv(ctx, sp)) return;   // الهاتف ما كيتفتحش وحدو
            launch(ctx);
            // إعادة المحاولة بعد 12 و 40 ثانية (الشبكة والنظام ماشي واجدين مباشرة بعد الإقلاع)
            if (!RETRY.equals(a)) {
                schedule(ctx, 12000, 1);
                schedule(ctx, 40000, 2);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isTv(Context ctx, SharedPreferences sp) {
        if (sp.getBoolean("is_tv", false) || "tv".equals(sp.getString("mode", null))) return true;
        if ("phone".equals(sp.getString("mode", null))) return false;
        UiModeManager um = (UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
        return ctx.getPackageManager().hasSystemFeature("android.software.leanback")
                || (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
                || !ctx.getPackageManager().hasSystemFeature("android.hardware.touchscreen");
    }

    private static void launch(Context ctx) {
        Intent i = new Intent(ctx, ReceiverActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        boolean canDirect = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(ctx);
        try { ctx.startActivity(i); } catch (Throwable ignored) {}
        if (!canDirect) fullScreenNotification(ctx, i);   // Android 10+ بلا إذن "الظهور فوق التطبيقات"
    }

    // طريقة بديلة: إشعار بملء الشاشة كيفتح التطبيق وحدو بلا لمس
    private static void fullScreenNotification(Context ctx, Intent i) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("tvlink_boot", "TV Link", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getActivity(ctx, 7, i, fl);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, "tvlink_boot") : new Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("TV Link").setContentText("TV Box")
                    .setCategory(Notification.CATEGORY_CALL).setAutoCancel(true)
                    .setContentIntent(pi).setFullScreenIntent(pi, true);
            nm.notify(7, b.build());
        } catch (Throwable ignored) {}
    }

    private static void schedule(Context ctx, long delay, int code) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            Intent r = new Intent(ctx, BootReceiver.class).setAction(RETRY);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 100 + code, r, fl);
            long at = System.currentTimeMillis() + delay;
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.set(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Throwable ignored) {}
    }
}
