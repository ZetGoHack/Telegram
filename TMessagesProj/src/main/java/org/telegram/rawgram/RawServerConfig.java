package org.telegram.rawgram;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Fetches help.getConfig and help.getAppConfig fresh from the server and shows both in the raw sheet. */
public class RawServerConfig {

    public static void show(Context context, int account, Theme.ResourcesProvider rp) {
        ConnectionsManager cm = ConnectionsManager.getInstance(account);
        cm.sendRequest(new TLRPC.TL_help_getConfig(), (config, configError) -> {
            TLRPC.TL_help_getAppConfig appReq = new TLRPC.TL_help_getAppConfig();
            appReq.hash = 0;
            cm.sendRequest(appReq, (app, appError) -> AndroidUtilities.runOnUIThread(() -> {
                Object configObject = config != null ? config : configError;
                Object appObject = app instanceof TLRPC.TL_help_appConfig ? toPlain(((TLRPC.TL_help_appConfig) app).config) : app != null ? app : appError;
                RawObjectSheet sheet = new RawObjectSheet(context, account, "Конфиг сервера", configObject, rp);
                sheet.addObjectTab("help.getConfig", () -> sheet.setObject("лимиты, DC, таймауты", configObject));
                sheet.addObjectTab("help.getAppConfig", () -> sheet.setObject("app config: лимиты клиента и флаги", appObject));
                Object limits = limits(config instanceof TLRPC.TL_config ? (TLRPC.TL_config) config : null, appObject instanceof Map ? (Map<?, ?>) appObject : null);
                sheet.addObjectTab("Лимиты", () -> sheet.setObject("обычный · премиум", limits));
                sheet.setSubtitle("лимиты, DC, таймауты");
                sheet.show();
            }));
        });
    }

    /**
     * Readable limits: app config keys with _default/_premium pairs merged into one line,
     * other limit-like app config keys, and the numeric limits of help.getConfig.
     */
    static Object limits(TLRPC.TL_config config, Map<?, ?> app) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (app != null) {
            TreeMap<String, Object[]> pairs = new TreeMap<>(); // base -> {default, premium}
            TreeMap<String, Object> other = new TreeMap<>();
            for (Map.Entry<?, ?> e : app.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object value = e.getValue();
                if (value instanceof Map || value instanceof java.util.List) {
                    continue;
                }
                if (key.endsWith("_default") || key.endsWith("_premium")) {
                    String base = key.substring(0, key.length() - 8);
                    Object[] pair = pairs.get(base);
                    if (pair == null) {
                        pairs.put(base, pair = new Object[2]);
                    }
                    pair[key.endsWith("_default") ? 0 : 1] = value;
                } else if (isLimitKey(key)) {
                    other.put(key, value);
                }
            }
            LinkedHashMap<String, Object> premium = new LinkedHashMap<>();
            for (Map.Entry<String, Object[]> e : pairs.entrySet()) {
                Object def = e.getValue()[0];
                Object prem = e.getValue()[1];
                if (def != null && prem != null) {
                    premium.put(e.getKey(), "обычный " + def + " · премиум " + prem);
                } else {
                    // lone half of a pair: keep the full key so it stays searchable
                    other.put(e.getKey() + (def != null ? "_default" : "_premium"), def != null ? def : prem);
                }
            }
            out.put("обычный_и_премиум", premium);
            out.put("app_config", new LinkedHashMap<>(other));
        }
        if (config != null) {
            LinkedHashMap<String, Object> c = new LinkedHashMap<>();
            c.put("message_length_max", config.message_length_max);
            c.put("caption_length_max", config.caption_length_max);
            c.put("chat_size_max", config.chat_size_max);
            c.put("megagroup_size_max", config.megagroup_size_max);
            c.put("forwarded_count_max", config.forwarded_count_max);
            c.put("stickers_recent_limit", config.stickers_recent_limit);
            c.put("edit_time_limit", config.edit_time_limit + " с (" + formatSeconds(config.edit_time_limit) + ")");
            c.put("revoke_time_limit", config.revoke_time_limit + " с (" + formatSeconds(config.revoke_time_limit) + ")");
            c.put("revoke_pm_time_limit", config.revoke_pm_time_limit + " с (" + formatSeconds(config.revoke_pm_time_limit) + ")");
            c.put("push_chat_limit", config.push_chat_limit);
            c.put("channels_read_media_period", config.channels_read_media_period + " с (" + formatSeconds(config.channels_read_media_period) + ")");
            c.put("online_update_period_ms", config.online_update_period_ms);
            c.put("call_ring_timeout_ms", config.call_ring_timeout_ms);
            out.put("help.getConfig", c);
        }
        return out;
    }

    private static boolean isLimitKey(String key) {
        return key.contains("limit") || key.contains("max") || key.contains("_count") || key.contains("_length")
                || key.contains("_size") || key.contains("_period") || key.contains("_timeout") || key.contains("_cooldown");
    }

    private static String formatSeconds(int seconds) {
        if (seconds <= 0 || seconds == Integer.MAX_VALUE) {
            return seconds <= 0 ? "0" : "∞";
        }
        if (seconds % 86400 == 0) {
            return seconds / 86400 + " д";
        } else if (seconds % 3600 == 0) {
            return seconds / 3600 + " ч";
        } else if (seconds % 60 == 0) {
            return seconds / 60 + " мин";
        }
        return seconds + " с";
    }

    /** JSONValue (TL) as plain maps/lists, so the dump reads like the JSON the server meant. */
    static Object toPlain(TLRPC.JSONValue value) {
        if (value instanceof TLRPC.TL_jsonObject) {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            for (TLRPC.TL_jsonObjectValue entry : ((TLRPC.TL_jsonObject) value).value) {
                map.put(entry.key, toPlain(entry.value));
            }
            return map;
        } else if (value instanceof TLRPC.TL_jsonArray) {
            ArrayList<Object> list = new ArrayList<>();
            for (TLRPC.JSONValue item : ((TLRPC.TL_jsonArray) value).value) {
                list.add(toPlain(item));
            }
            return list;
        } else if (value instanceof TLRPC.TL_jsonString) {
            return ((TLRPC.TL_jsonString) value).value;
        } else if (value instanceof TLRPC.TL_jsonNumber) {
            double d = ((TLRPC.TL_jsonNumber) value).value;
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d;
        } else if (value instanceof TLRPC.TL_jsonBool) {
            return ((TLRPC.TL_jsonBool) value).value;
        }
        return null;
    }
}
