package com.tvlink.app;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final SharedPreferences sp = getSharedPreferences("tvlink", MODE_PRIVATE);
        String mode = sp.getString("mode", null);
        boolean choose = getIntent().getBooleanExtra("choose", false);
        if (mode == null) {
            UiModeManager um = (UiModeManager) getSystemService(UI_MODE_SERVICE);
            boolean tv = getPackageManager().hasSystemFeature("android.software.leanback")
                    || (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION);
            if (tv) mode = "tv";
        }
        if (mode != null && !choose) {
            go(mode);
            return;
        }
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(40, 40, 40, 40);
        TextView t = new TextView(this);
        t.setText("TV Link\n\nشنو هاد الجهاز؟");
        t.setTextSize(26);
        t.setGravity(Gravity.CENTER);
        l.addView(t);
        l.addView(pick(sp, "📺  هذا TV Box (يستقبل)", "tv"));
        l.addView(pick(sp, "📱  هذا هاتف (يرسل)", "phone"));
        setContentView(l);
    }

    private Button pick(final SharedPreferences sp, String text, final String mode) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setTextSize(20);
        bt.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sp.edit().putString("mode", mode).apply();
                go(mode);
            }
        });
        return bt;
    }

    private void go(String mode) {
        getSharedPreferences("tvlink", MODE_PRIVATE).edit().putString("mode", mode).apply();
        startActivity(new Intent(this, "tv".equals(mode) ? ReceiverActivity.class : SenderActivity.class));
        finish();
    }
}
