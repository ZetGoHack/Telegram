package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

public class LauncherIconController {
    public static void tryFixLauncherIconIfNeeded() {
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (isEnabled(icon)) {
                return;
            }
        }

        setIcon(LauncherIcon.DEFAULT);
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        int i = ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx));
        return i == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || i == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == LauncherIcon.DEFAULT;
    }

    public static void setIcon(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        for (LauncherIcon i : LauncherIcon.values()) {
            pm.setComponentEnabledSetting(i.getComponentName(ctx), i == icon ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }

    public enum LauncherIcon {
        // rawGram: the six launcher aliases carry rawGram's own vector icons (none premium-locked)
        DEFAULT("DefaultIcon", R.drawable.rawgram_icon_bg_slate, R.drawable.rawgram_icon_fg_white, "rawGram"),
        VINTAGE("VintageIcon", R.drawable.rawgram_icon_bg_blue, R.drawable.rawgram_icon_fg_white, "Telegram"),
        AQUA("AquaIcon", R.drawable.rawgram_icon_bg_terminal, R.drawable.rawgram_icon_fg_green, "Terminal"),
        PREMIUM("PremiumIcon", R.drawable.rawgram_icon_bg_light, R.drawable.rawgram_icon_fg_slate, "Light"),
        TURBO("TurboIcon", R.drawable.rawgram_icon_bg_neon, R.drawable.rawgram_icon_fg_pink, "Neon"),
        NOX("NoxIcon", R.drawable.rawgram_icon_bg_json, R.drawable.rawgram_icon_fg_braces, "JSON");

        public final String key;
        public final int background;
        public final int foreground;
        public final String label;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, String label) {
            this(key, background, foreground, label, false);
        }

        LauncherIcon(String key, int background, int foreground, String label, boolean premium) {
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.label = label;
            this.premium = premium;
        }
    }
}
