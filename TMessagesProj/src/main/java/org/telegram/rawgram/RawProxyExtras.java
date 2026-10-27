package org.telegram.rawgram;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.text.TextUtils;
import android.widget.LinearLayout;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.utils.proxy.ProxySettings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Proxy extras of the proxy list screen: switching the proxy off on VPN / mobile data / Wi-Fi and
 * IP info of the current proxy (on explicit tap only).
 * Ported from exteraGram (ApplicationLoader.checkProxyForNetworkState, proxy/ProxyController,
 * proxy/IpAddressInfoController, ProxyListActivity).
 */
public class RawProxyExtras {

    private static final String PREF_AUTO_DISABLED = "proxy_auto_disabled";
    private static final String INFO_HOST = "ipwho.is";

    /** Called from the default network callback; cheap when no condition is selected. */
    public static void onNetworkChanged() {
        if (ApplicationLoader.applicationContext == null || RawProxyConfig.getDisableOn() == 0 && !wasAutoDisabled()) {
            return;
        }
        try {
            int mask = RawProxyConfig.getDisableOn();
            SharedPreferences prefs = MessagesController.getGlobalMainSettings();
            boolean autoDisabled = prefs.getBoolean(PREF_AUTO_DISABLED, false);
            NetworkCapabilities caps = activeCapabilities();
            boolean match = caps != null && (
                    (mask & RawProxyConfig.VPN) != 0 && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                            || (mask & RawProxyConfig.MOBILE) != 0 && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                            || (mask & RawProxyConfig.WIFI) != 0 && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
            if (match) {
                if (!autoDisabled && prefs.getBoolean("proxy_enabled", false) && !TextUtils.isEmpty(prefs.getString("proxy_ip", ""))) {
                    prefs.edit().putBoolean(PREF_AUTO_DISABLED, true).putBoolean("proxy_enabled", false).apply();
                    ConnectionsManager.setProxySettings(false, null);
                    NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.proxySettingsChanged);
                }
            } else if (autoDisabled) {
                boolean has = !TextUtils.isEmpty(prefs.getString("proxy_ip", ""));
                SharedPreferences.Editor editor = prefs.edit().putBoolean(PREF_AUTO_DISABLED, false);
                if (has) {
                    editor.putBoolean("proxy_enabled", true);
                }
                editor.apply();
                if (has) {
                    ConnectionsManager.setProxySettings(true, ProxySettings.fromSharedPreferences(prefs));
                    NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.proxySettingsChanged);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static boolean wasAutoDisabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_AUTO_DISABLED, false);
    }

    private static NetworkCapabilities activeCapabilities() {
        ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return null;
        }
        Network network = cm.getActiveNetwork();
        return network == null ? null : cm.getNetworkCapabilities(network);
    }

    /** Summary for the row value. */
    public static String summary() {
        int mask = RawProxyConfig.getDisableOn();
        if (mask == 0) {
            return "Нет";
        }
        StringBuilder sb = new StringBuilder();
        if ((mask & RawProxyConfig.VPN) != 0) {
            sb.append("VPN");
        }
        if ((mask & RawProxyConfig.MOBILE) != 0) {
            sb.append(sb.length() > 0 ? ", " : "").append("мобильная сеть");
        }
        if ((mask & RawProxyConfig.WIFI) != 0) {
            sb.append(sb.length() > 0 ? ", " : "").append("Wi-Fi");
        }
        return sb.toString();
    }

    /** Dialog: conditions to switch the proxy off, plus an IP info button. */
    public static void showDialog(Activity activity, Runnable onChanged) {
        if (activity == null) {
            return;
        }
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int mask = RawProxyConfig.getDisableOn();
        int[] flags = {RawProxyConfig.VPN, RawProxyConfig.MOBILE, RawProxyConfig.WIFI};
        String[] names = {"При включённом VPN", "В мобильной сети", "В сети Wi-Fi"};
        CheckBoxCell[] cells = new CheckBoxCell[3];
        for (int i = 0; i < 3; i++) {
            CheckBoxCell cell = new CheckBoxCell(activity, 1);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            cell.setText(names[i], null, (mask & flags[i]) != 0, false);
            cell.setOnClickListener(v -> ((CheckBoxCell) v).setChecked(!((CheckBoxCell) v).isChecked(), true));
            cells[i] = cell;
            box.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Отключать прокси");
        builder.setMessage("Прокси выключается в выбранных сетях и включается обратно, когда сеть сменится.");
        builder.setView(box);
        builder.setPositiveButton("Готово", (d, w) -> {
            int result = 0;
            for (int i = 0; i < 3; i++) {
                if (cells[i].isChecked()) {
                    result |= flags[i];
                }
            }
            RawProxyConfig.setDisableOn(result);
            onChanged.run();
            onNetworkChanged();
        });
        builder.setNegativeButton("Отмена", null);
        builder.setNeutralButton("Сведения об IP", (d, w) -> confirmInfo(activity));
        builder.show();
    }

    private static void confirmInfo(Activity activity) {
        SharedConfig.ProxyInfo proxy = SharedConfig.currentProxy;
        if (proxy == null || proxy.settings.getType() == ProxySettings.Type.WEB) {
            RawNotify.warn("Нет выбранного прокси");
            return;
        }
        String host = proxy.settings.getAddress();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Сведения об IP");
        builder.setMessage("IP-адрес прокси будет отправлен на сервис " + INFO_HOST + ". Продолжить?");
        builder.setPositiveButton("Узнать", (d, w) -> request(activity, host));
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    private static void request(Activity activity, String host) {
        Utilities.globalQueue.postRunnable(() -> {
            String text = null;
            try {
                String ip = resolve(host);
                if (ip != null) {
                    HttpURLConnection c = (HttpURLConnection) new URL("https://" + INFO_HOST + "/" + ip).openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(4000);
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        char[] buf = new char[2048];
                        int n;
                        while ((n = r.read(buf)) > 0 && sb.length() < 64 * 1024) {
                            sb.append(buf, 0, n);
                        }
                        text = format(ip, new JSONObject(sb.toString()));
                    } finally {
                        c.disconnect();
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            String result = text;
            AndroidUtilities.runOnUIThread(() -> {
                if (result == null) {
                    RawNotify.warn("Не удалось получить сведения об IP");
                } else if (!activity.isFinishing()) {
                    new AlertDialog.Builder(activity).setTitle("Сведения об IP").setMessage(result).setPositiveButton("OK", null).show();
                }
            });
        });
    }

    private static String resolve(String host) throws Exception {
        String h = host.trim();
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);
        }
        InetAddress a = InetAddress.getByName(IDN.toASCII(h));
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()) {
            return null;
        }
        return a.getHostAddress();
    }

    private static String format(String ip, JSONObject o) {
        if (!o.optBoolean("success", false)) {
            return null;
        }
        JSONObject conn = o.optJSONObject("connection");
        JSONObject tz = o.optJSONObject("timezone");
        StringBuilder sb = new StringBuilder();
        line(sb, "IP", o.optString("ip", ip));
        String country = o.optString("country_code", "");
        if (!country.isEmpty()) {
            String name = new Locale("", country.toUpperCase(Locale.US)).getDisplayCountry(Locale.getDefault());
            line(sb, "Страна", TextUtils.isEmpty(name) ? country : name);
        }
        line(sb, "Регион", o.optString("region", ""));
        line(sb, "Город", o.optString("city", ""));
        if (conn != null) {
            line(sb, "Провайдер", conn.optString("isp", ""));
            line(sb, "Организация", conn.optString("org", ""));
            line(sb, "Домен", conn.optString("domain", ""));
        }
        if (tz != null) {
            line(sb, "Часовой пояс", tz.optString("id", ""));
        }
        return sb.toString().trim();
    }

    private static void line(StringBuilder sb, String key, String value) {
        if (!TextUtils.isEmpty(value)) {
            sb.append(key).append(": ").append(value).append('\n');
        }
    }
}
