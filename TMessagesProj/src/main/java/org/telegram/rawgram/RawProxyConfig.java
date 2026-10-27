package org.telegram.rawgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/** Proxy extras: networks on which the proxy is switched off automatically. Default: never. */
public class RawProxyConfig {

    public static final int VPN = 1;
    public static final int MOBILE = 2;
    public static final int WIFI = 4;

    private static int disableOn = -1;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("rawgram_proxy", Context.MODE_PRIVATE);
    }

    /** Bit mask of VPN / MOBILE / WIFI. */
    public static int getDisableOn() {
        if (disableOn < 0) {
            if (ApplicationLoader.applicationContext == null) {
                return 0;
            }
            disableOn = prefs().getInt("disableOn", 0) & (VPN | MOBILE | WIFI);
        }
        return disableOn;
    }

    public static void setDisableOn(int mask) {
        disableOn = mask & (VPN | MOBILE | WIFI);
        prefs().edit().putInt("disableOn", disableOn).apply();
    }
}
