package org.telegram.rawgram;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.web.BotWebViewContainer;

import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Per-webview log of the Mini App bridge: the URL (with tgWebAppData) the page was
 * opened with and the events going both ways, kept in a small ring buffer.
 */
public class RawWebAppLog {

    /** Menu id for ActionBarMenuItem based menus; far from R.id.* and small local ids. */
    public static final int MENU_ID = 0x5a770001;
    public static final String MENU_TEXT = "rawGram: данные";

    private static final int MAX_ENTRIES = 400;
    private static final int MAX_DATA = 16_000;

    public static final int DIR_IN = 0;  // page -> client (postEvent)
    public static final int DIR_OUT = 1; // client -> page (receiveEvent)

    private static class Entry {
        long time;
        long lastTime;
        int direction;
        String event;
        String data;
        int count = 1;
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final ArrayList<String> urls = new ArrayList<>();
    private int dropped;

    public synchronized void onUrl(String url) {
        if (url == null) {
            return;
        }
        urls.add(url);
        if (urls.size() > 20) {
            urls.remove(0);
        }
    }

    public void onIn(String event, String data) {
        add(DIR_IN, event, data);
    }

    public void onOut(String event, Object data) {
        add(DIR_OUT, event, data == null ? null : data.toString());
    }

    private synchronized void add(int direction, String event, String data) {
        if (data != null && data.length() > MAX_DATA) {
            data = data.substring(0, MAX_DATA) + "…";
        }
        long now = System.currentTimeMillis();
        Entry last = entries.peekLast();
        // viewport_changed and friends come in bursts: fold identical repeats
        if (last != null && last.direction == direction && TextUtils.equals(last.event, event) && TextUtils.equals(last.data, data)) {
            last.count++;
            last.lastTime = now;
            return;
        }
        Entry e = new Entry();
        e.time = e.lastTime = now;
        e.direction = direction;
        e.event = event;
        e.data = data;
        entries.addLast(e);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
            dropped++;
        }
    }

    public synchronized String getFirstUrl() {
        return urls.isEmpty() ? null : urls.get(0);
    }

    public synchronized String getLastUrl() {
        return urls.isEmpty() ? null : urls.get(urls.size() - 1);
    }

    public synchronized ArrayList<Object> eventsNewestFirst() {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        ArrayList<Object> out = new ArrayList<>(entries.size());
        Iterator<Entry> it = entries.descendingIterator();
        while (it.hasNext()) {
            Entry e = it.next();
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("time", format.format(new Date(e.time)));
            row.put("dir", e.direction == DIR_IN ? "page → client" : "client → page");
            row.put("event", e.event);
            if (e.count > 1) {
                row.put("repeated", e.count);
                row.put("last_time", format.format(new Date(e.lastTime)));
            }
            if (e.data != null) {
                row.put("data", parseJson(e.data));
            }
            out.add(row);
        }
        if (dropped > 0) {
            out.add("… ещё " + dropped + " старых событий вытеснено");
        }
        return out;
    }

    public synchronized int size() {
        return entries.size();
    }

    // ---------------------------------------------------------------- sheet

    public static void show(Context context, int account, BotWebViewContainer container, Theme.ResourcesProvider resourcesProvider) {
        if (context == null || container == null) {
            return;
        }
        RawWebAppLog log = container.rawgramLog;
        String url = log.getLastUrl();
        if (url == null) {
            url = container.getUrlLoaded();
        }
        final String launchUrl = url;
        LinkedHashMap<String, Object> fragment = parseParams(launchUrl, true);
        LinkedHashMap<String, Object> initData = parseInitData(fragment.get("tgWebAppData"));
        if (initData == null) {
            // some links carry it in the query instead of the fragment
            initData = parseInitData(parseParams(launchUrl, false).get("tgWebAppData"));
        }
        final Object initDataObject = initData != null ? initData : "tgWebAppData не найден в URL";

        RawObjectSheet sheet = new RawObjectSheet(context, account, MENU_TEXT, initDataObject, resourcesProvider);
        String initSubtitle = initData != null ? "tgWebAppData · " + initData.size() + " полей · URL-декодировано" : "нет tgWebAppData";
        sheet.setObject(initSubtitle, initDataObject);
        sheet.addObjectTab("initData", () -> sheet.setObject(initSubtitle, initDataObject));
        sheet.addObjectTab("События (" + log.size() + ")", () -> sheet.setObject("новые сверху · повторы свёрнуты", log.eventsNewestFirst()));
        sheet.addObjectTab("URL", () -> {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("launch_url", launchUrl);
            String current = null;
            try {
                current = container.getWebView() != null ? container.getWebView().getUrl() : null;
            } catch (Throwable ignore) {
            }
            if (current != null && !TextUtils.equals(current, launchUrl)) {
                map.put("current_url", current);
            }
            LinkedHashMap<String, Object> query = parseParams(launchUrl, false);
            if (!query.isEmpty()) {
                map.put("query", query);
            }
            if (!fragment.isEmpty()) {
                map.put("fragment", fragment);
            }
            synchronized (log) {
                if (log.urls.size() > 1) {
                    map.put("loaded_urls", new ArrayList<>(log.urls));
                }
            }
            sheet.setObject("URL, с которым открыт Mini App", map);
        });
        sheet.show();
    }

    // ---------------------------------------------------------------- parsing

    /** Params of the URL fragment (fragment = true) or query, decoded; JSON values are expanded. */
    private static LinkedHashMap<String, Object> parseParams(String url, boolean fragment) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (TextUtils.isEmpty(url)) {
            return out;
        }
        String part;
        try {
            Uri uri = Uri.parse(url);
            part = fragment ? uri.getEncodedFragment() : uri.getEncodedQuery();
        } catch (Throwable e) {
            return out;
        }
        if (TextUtils.isEmpty(part)) {
            return out;
        }
        for (String pair : part.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decode(eq >= 0 ? pair.substring(0, eq) : pair);
            String value = eq >= 0 ? decode(pair.substring(eq + 1)) : "";
            // tgWebAppData stays a string here, it is a query string of its own
            out.put(key, "tgWebAppData".equals(key) ? value : parseJson(value));
        }
        return out;
    }

    private static LinkedHashMap<String, Object> parseInitData(Object value) {
        if (!(value instanceof String) || TextUtils.isEmpty((String) value)) {
            return null;
        }
        String raw = (String) value;
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decode(eq >= 0 ? pair.substring(0, eq) : pair);
            String v = eq >= 0 ? decode(pair.substring(eq + 1)) : "";
            Object parsed = parseJson(v);
            if ("auth_date".equals(key)) {
                try {
                    long seconds = Long.parseLong(v);
                    out.put(key, seconds);
                    out.put("auth_date_human", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(seconds * 1000L)));
                    continue;
                } catch (NumberFormatException ignore) {
                }
            }
            out.put(key, parsed);
        }
        out.put("_raw", raw);
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Throwable e) {
            return s;
        }
    }

    /** JSON object/array text -> Map/List so TLDumper renders it as a tree; anything else as is. */
    static Object parseJson(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        try {
            if (t.startsWith("{")) {
                return toJava(new JSONObject(t));
            } else if (t.startsWith("[")) {
                return toJava(new JSONArray(t));
            }
        } catch (Throwable ignore) {
        }
        return text;
    }

    private static Object toJava(Object value) {
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                out.put(key, toJava(obj.opt(key)));
            }
            return out;
        } else if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            ArrayList<Object> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                out.add(toJava(arr.opt(i)));
            }
            return out;
        } else if (value == JSONObject.NULL) {
            return null;
        }
        return value;
    }
}
