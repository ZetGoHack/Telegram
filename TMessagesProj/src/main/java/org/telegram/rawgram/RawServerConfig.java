package org.telegram.rawgram;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.LinkedHashMap;

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
                sheet.setSubtitle("лимиты, DC, таймауты");
                sheet.show();
            }));
        });
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
