package org.telegram.rawgram;

import android.graphics.Typeface;

/**
 * Hook for AndroidUtilities.getTypeface (Telegram's bundled Roboto assets): the font chosen in «Внешний вид» →
 * «Шрифт» — the user's own file (RawCustomFont), the system font (RawSystemFonts, ported from exteraGram's
 * FontUtils), or null to load the bundled asset as usual.
 */
public class RawTypeface {

    public static Typeface createOrNull(String assetPath) {
        if (assetPath == null) {
            return null;
        }
        try {
            switch (RawUiConfig.fontMode()) {
                case RawUiConfig.FONT_CUSTOM:
                    return RawCustomFont.forAsset(assetPath);
                case RawUiConfig.FONT_SYSTEM:
                    return RawSystemFonts.forAsset(assetPath);
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
