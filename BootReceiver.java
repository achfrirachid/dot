package com.tvlink.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String mode = c.getSharedPreferences("tvlink", Context.MODE_PRIVATE).getString("mode", null);
        if ("tv".equals(mode)) {
            try {
                Intent a = new Intent(c, ReceiverActivity.class);
                a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(a);
            } catch (Exception ignored) {}
        }
    }
}
