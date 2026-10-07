package com.tvlink.app;

import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

// يربط كل اتصالات التطبيق (هاتف + TV Box) بشبكة الراوتر المحلية (Wi-Fi / كابل)،
// حتى ولو الراوتر بلا نت. بدون هادشي أندرويد كيدير الاتصالات عبر داتا الهاتف
// ملي كيلقى الواي فاي "بلا إنترنت"، وكتفشل الاتصالات مع TV Box (الفيديو، الصور، البحث عن الجهاز).
public class TvLinkApp extends Application {
    private ConnectivityManager cm;
    private Network bound;

    @Override
    public void onCreate() {
        super.onCreate();
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        watch(NetworkCapabilities.TRANSPORT_WIFI);
        watch(NetworkCapabilities.TRANSPORT_ETHERNET);
    }

    private void watch(int transport) {
        try {
            NetworkRequest.Builder b = new NetworkRequest.Builder().addTransportType(transport);
            try { b.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET); } catch (Exception ignored) {}
            cm.registerNetworkCallback(b.build(), new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { bind(n); }
                @Override public void onLost(Network n) { if (n.equals(bound)) bind(null); }
            });
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private synchronized void bind(Network n) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23) cm.bindProcessToNetwork(n);
            else ConnectivityManager.setProcessDefaultNetwork(n);
            bound = n;
        } catch (Exception ignored) {}
    }
}
