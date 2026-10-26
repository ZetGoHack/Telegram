package org.telegram.rawgram;

import android.graphics.Typeface;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.util.HashMap;

/**
 * Fonts of the text tool in the photo editor (PaintTypeface). The text is drawn into the picture others get, so its
 * Roboto / Merriweather options stay Telegram's own even when «Внешний вид» → «Шрифт» replaces the app font
 * ({@link RawTypeface} hooks AndroidUtilities.getTypeface, which they used). The font imported there is offered as one
 * more option instead.
 */
public final class RawPaintFonts {

    public static final String CUSTOM_KEY = "rawgram_custom";

    private static final HashMap<String, Typeface> assets = new HashMap<>();

    private RawPaintFonts() {
    }

    /** Telegram's bundled font, bypassing the app font replacement. Mirrors AndroidUtilities.getTypeface. */
    public static Typeface asset(String assetPath) {
        synchronized (assets) {
            Typeface t = assets.get(assetPath);
            if (t == null) {
                try {
                    if (Build.VERSION.SDK_INT >= 26) {
                        Typeface.Builder builder = new Typeface.Builder(ApplicationLoader.applicationContext.getAssets(), assetPath);
                        if (assetPath.contains("medium") || assetPath.contains("rbold")) {
                            builder.setWeight(700);
                        }
                        if (assetPath.contains("italic")) {
                            builder.setItalic(true);
                        }
                        t = builder.build();
                    } else {
                        t = Typeface.createFromAsset(ApplicationLoader.applicationContext.getAssets(), assetPath);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                    return Typeface.DEFAULT_BOLD;
                }
                assets.put(assetPath, t);
            }
            return t;
        }
    }

    /** The imported font file (its bold file when one is picked: the editor's fonts are bold), or null. */
    public static File customFile() {
        File bold = RawCustomFont.boldFile();
        if (bold.exists()) {
            return bold;
        }
        File regular = RawCustomFont.regularFile();
        return regular.exists() ? regular : null;
    }

    /** Name of the imported font in the editor's list. */
    public static String customName(File file) {
        String name = RawCustomFont.fontName(file);
        return name != null ? name : "Свой шрифт";
    }

    /** Typeface of the imported font, or the default bold one when the file can't be read. */
    public static Typeface loadCustom(File file) {
        try {
            return Typeface.createFromFile(file);
        } catch (Exception e) {
            FileLog.e(e);
            return Typeface.DEFAULT_BOLD;
        }
    }
}
