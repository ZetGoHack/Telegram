package org.telegram.rawgram;

import android.text.TextUtils;

import org.telegram.messenger.BuildConfig;

/**
 * rawGram's own build identity: the build number from {@code rawgram-builds/build.ps1}, the plugins flavour and the
 * git commit the APK was built from (with {@code -dirty} for uncommitted sources). Telegram's version stays the base.
 */
public final class RawVersion {

    private RawVersion() {
    }

    /** Build number from build.ps1, or 0 for a build made without it. */
    public static int build() {
        return BuildConfig.RAWGRAM_BUILD;
    }

    public static String commit() {
        return BuildConfig.RAWGRAM_COMMIT;
    }

    public static boolean plugins() {
        return BuildConfig.RAWGRAM_BRANCH.contains("plugins");
    }

    /** "build 123 · plugins · 17b5a183d": the parts that are known, separated by dots. */
    public static String line() {
        StringBuilder text = new StringBuilder(build() > 0 ? "build " + build() : "локальная сборка");
        if (plugins()) {
            text.append(" · plugins");
        }
        if (!TextUtils.isEmpty(commit())) {
            text.append(" · ").append(commit());
        }
        return text.toString();
    }
}
