package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

/**
 * rawGram's default language: the custom pack t.me/setlanguage/zaeczetgogram, applied once on a fresh install
 * (nobody logged in yet). The login screen then says so at the bottom, with a tap back to the system language.
 */
public final class RawLanguage {

    public static final String PACK = "zaeczetgogram";
    private static final String KEY_APPLIED = "defaultLanguageApplied";

    private RawLanguage() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("rawgram", Context.MODE_PRIVATE);
    }

    /** IntroActivity: the first start without accounts switches to the rawGram language pack (once). */
    public static void applyDefaultOnce(int account) {
        if (prefs().getBoolean(KEY_APPLIED, false)) {
            return;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                prefs().edit().putBoolean(KEY_APPLIED, true).apply(); // an existing install keeps its language
                return;
            }
        }
        LocaleController.LocaleInfo current = LocaleController.getInstance().getCurrentLocaleInfo();
        if (current != null && PACK.equals(current.shortName)) {
            prefs().edit().putBoolean(KEY_APPLIED, true).apply();
            return;
        }
        TLRPC.TL_langpack_getLanguage req = new TLRPC.TL_langpack_getLanguage();
        req.lang_pack = "android";
        req.lang_code = PACK;
        ConnectionsManager.getInstance(account).sendRequest(req, (res, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(res instanceof TLRPC.TL_langPackLanguage)) {
                return; // offline or the pack is gone: try again next start
            }
            prefs().edit().putBoolean(KEY_APPLIED, true).apply();
            apply(localeInfo((TLRPC.TL_langPackLanguage) res), account);
        }), ConnectionsManager.RequestFlagWithoutLogin);
    }

    /** Same LocaleInfo as Telegram builds for a t.me/setlanguage link (AlertsCreator.createLanguageAlert). */
    private static LocaleController.LocaleInfo localeInfo(TLRPC.TL_langPackLanguage language) {
        language.lang_code = language.lang_code.replace('-', '_').toLowerCase();
        language.plural_code = language.plural_code.replace('-', '_').toLowerCase();
        if (language.base_lang_code != null) {
            language.base_lang_code = language.base_lang_code.replace('-', '_').toLowerCase();
        }
        String key = (language.official ? "remote_" : "unofficial_") + language.lang_code;
        LocaleController.LocaleInfo info = LocaleController.getInstance().getLanguageFromDict(key);
        if (info == null) {
            info = new LocaleController.LocaleInfo();
            info.name = language.native_name;
            info.nameEnglish = language.name;
            info.shortName = language.lang_code;
            info.baseLangCode = language.base_lang_code;
            info.pluralLangCode = language.plural_code;
            info.isRtl = language.rtl;
            info.pathToFile = language.official ? "remote" : "unofficial";
        }
        return info;
    }

    private static void apply(LocaleController.LocaleInfo info, int account) {
        if (info == null) {
            return;
        }
        LocaleController.getInstance().applyLanguage(info, true, false, false, true, account, () -> {
            if (LaunchActivity.instance != null) {
                LaunchActivity.instance.rebuildAllFragments(true);
            }
        });
    }

    /**
     * IntroActivity: while a custom (unofficial) language is active, a text button at the bottom offers going back to
     * the system language; Telegram's own «Continue in …» hint is hidden meanwhile.
     */
    public static void addNotice(FrameLayout container, View telegramSwitch, int account) {
        LocaleController.LocaleInfo current = LocaleController.getInstance().getCurrentLocaleInfo();
        if (current == null || !current.isUnofficial()) {
            return;
        }
        LocaleController.LocaleInfo system = systemLocale();
        if (system == null || system == current) {
            return;
        }
        if (telegramSwitch != null) {
            telegramSwitch.setVisibility(View.GONE);
        }
        TextView notice = new TextView(container.getContext());
        notice.setGravity(Gravity.CENTER);
        notice.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        notice.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        notice.setText("Включён неофициальный язык. Нажми, чтобы вернуться на " + system.name);
        notice.setPadding(dp(16), dp(8), dp(16), dp(8));
        notice.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        notice.setOnClickListener(v -> apply(system, account));
        container.addView(notice, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 24, 0, 24, 14));
    }

    /** The bundled language matching the system locale (English when there is none). */
    private static LocaleController.LocaleInfo systemLocale() {
        String lang = LocaleController.getInstance().getSystemDefaultLocale().getLanguage();
        if (lang == null) {
            lang = "en";
        }
        String alias = LocaleController.getLocaleAlias(lang);
        LocaleController.LocaleInfo english = null;
        for (int a = 0; a < LocaleController.getInstance().languages.size(); a++) {
            LocaleController.LocaleInfo info = LocaleController.getInstance().languages.get(a);
            if (info.isUnofficial()) {
                continue;
            }
            if ("en".equals(info.shortName)) {
                english = info;
            }
            if (info.shortName.equals(lang) || info.shortName.equals(alias)) {
                return info;
            }
        }
        return english;
    }
}
