package com.tvlink.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

// كيشغل تطبيق TV Link تلقائيا ملي TV Box كيشعل (ولا ملي كيتحدث التطبيق).
// الإعداد "تشغيل تلقائي" كاين فـ ⚙ الإعدادات داخل التطبيق (مفعل افتراضيا).
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context ctx, Intent in) {
        SharedPreferences sp = ctx.getSharedPreferences("tvlink", Context.MODE_PRIVATE);
        if (!sp.getBoolean("autostart", true)) return;
        String mode = sp.getString("mode", null);
        boolean tv = "tv".equals(mode)
                || (mode == null && ctx.getPackageManager().hasSystemFeature("android.software.leanback"));
        if (!tv) return;
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        // تأخير صغير: باش الواي فاي والنظام يكونو واجدين
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    Intent i = new Intent(app, ReceiverActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    app.startActivity(i);
                } catch (Exception ignored) {
                } finally {
                    pr.finish();
                }
            }
        }, 4000);
    }
}
