package org.telegram.rawgram;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.SystemFonts;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/**
 * «Системный шрифт»: the device's own sans-serif family for Telegram's bundled Roboto assets, with the weights picked by
 * what the font really has — a medium weight and an italic are used only when they render differently from the regular
 * one, else bold / the regular italic stand in; on Pixels Google Sans is used when the system sans is plain Roboto.
 * Ported from exteraGram's FontUtils (exteraSquad, GPL).
 */
public final class RawSystemFonts {

    private static final int WEIGHT_REGULAR = 400;
    private static final int WEIGHT_MEDIUM = 500;
    private static final int WEIGHT_BOLD = 700;
    private static final int WEIGHT_EXTRA_BOLD = 800;

    private static final int CANVAS_SIZE = AndroidUtilities.dp(20);
    private static final Paint PAINT = new Paint();
    private static final String TEST_TEXT;

    private static volatile Boolean mediumWeightSupported;
    private static volatile Boolean italicSupported;
    private static volatile Boolean usePixelGoogleSans;
    private static Typeface googleSans;
    private static boolean googleSansLoaded;
    private static Typeface googleSansMedium;
    private static boolean googleSansMediumLoaded;

    static {
        PAINT.setTextSize(CANVAS_SIZE);
        PAINT.setAntiAlias(true);
        PAINT.setSubpixelText(false);
        PAINT.setFakeBoldText(false);
        String language = "en";
        try {
            if (LocaleController.getInstance() != null && LocaleController.getInstance().getCurrentLocale() != null) {
                language = LocaleController.getInstance().getCurrentLocale().getLanguage();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        // compare glyphs of the user's script: a font may have Latin weights but not Cyrillic ones
        if (Arrays.asList("zh", "ja", "ko").contains(language)) {
            TEST_TEXT = "你好";
        } else if (Arrays.asList("ar", "fa").contains(language)) {
            TEST_TEXT = "مرحبا";
        } else if ("iw".equals(language)) {
            TEST_TEXT = "שלום";
        } else if ("th".equals(language)) {
            TEST_TEXT = "สวัสดี";
        } else if ("hi".equals(language)) {
            TEST_TEXT = "नमस्ते";
        } else if (Arrays.asList("ru", "uk", "ky", "be", "sr").contains(language)) {
            TEST_TEXT = "Привет";
        } else {
            TEST_TEXT = "R";
        }
    }

    private RawSystemFonts() {
    }

    /** System typeface for a bundled asset path, or null when the asset has no system counterpart. */
    public static Typeface forAsset(String assetPath) {
        switch (assetPath) {
            case "fonts/ritalic.ttf":
                return resolveSans(WEIGHT_REGULAR, true);
            case AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM_ITALIC:
                return resolveSans(WEIGHT_MEDIUM, true);
            case "fonts/rcondensedbold.ttf":
                return Typeface.create("sans-serif-condensed", Typeface.BOLD);
            case AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM:
                return resolveSans(WEIGHT_MEDIUM, false);
            case AndroidUtilities.TYPEFACE_ROBOTO_MONO:
                return Typeface.MONOSPACE;
            case "fonts/rregular.ttf":
                return resolveSans(WEIGHT_REGULAR, false);
            case AndroidUtilities.TYPEFACE_ROBOTO_EXTRA_BOLD:
                return resolveSans(WEIGHT_EXTRA_BOLD, false);
            default:
                return null;
        }
    }

    public static boolean isMediumWeightSupported() {
        if (mediumWeightSupported == null) {
            synchronized (RawSystemFonts.class) {
                if (mediumWeightSupported == null) {
                    mediumWeightSupported = supportsMediumWeight();
                }
            }
        }
        return mediumWeightSupported;
    }

    public static boolean isItalicSupported() {
        if (italicSupported == null) {
            synchronized (RawSystemFonts.class) {
                if (italicSupported == null) {
                    italicSupported = rendersDifferently(weightedSans(WEIGHT_REGULAR, false), weightedSans(WEIGHT_REGULAR, true));
                }
            }
        }
        return italicSupported;
    }

    /** Draws the test text with both typefaces and compares the pixels. */
    static boolean rendersDifferently(Typeface a, Typeface b) {
        Canvas canvas = new Canvas();
        Bitmap first = Bitmap.createBitmap(CANVAS_SIZE * 2, CANVAS_SIZE, Bitmap.Config.ARGB_8888);
        Bitmap second = Bitmap.createBitmap(CANVAS_SIZE * 2, CANVAS_SIZE, Bitmap.Config.ARGB_8888);
        synchronized (PAINT) {
            canvas.setBitmap(first);
            PAINT.setTypeface(a);
            canvas.drawText(TEST_TEXT, 0, CANVAS_SIZE, PAINT);
            canvas.setBitmap(second);
            PAINT.setTypeface(b);
            canvas.drawText(TEST_TEXT, 0, CANVAS_SIZE, PAINT);
            PAINT.setTypeface(null);
        }
        boolean differs = !first.sameAs(second);
        first.recycle();
        second.recycle();
        return differs;
    }

    private static boolean differsFromRegular(Typeface typeface, boolean italic) {
        return rendersDifferently(weightedSans(WEIGHT_REGULAR, italic), typeface);
    }

    private static boolean supportsMediumWeight() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && differsFromRegular(weightedSans(WEIGHT_MEDIUM, false), false)) {
            return true;
        }
        return differsFromRegular(Typeface.create("sans-serif-medium", Typeface.NORMAL), false);
    }

    private static boolean isGooglePixel() {
        String model = Build.MODEL;
        return "google".equalsIgnoreCase(Build.MANUFACTURER) && model != null && model.toLowerCase(Locale.US).startsWith("pixel");
    }

    /** On a Pixel whose sans-serif is still Roboto (same metrics as the bundled one), Google Sans is the "system" look. */
    private static boolean usePixelGoogleSans() {
        if (!isGooglePixel()) {
            return false;
        }
        if (usePixelGoogleSans == null) {
            synchronized (RawSystemFonts.class) {
                if (usePixelGoogleSans == null) {
                    Typeface roboto = fromAssets("fonts/rregular.ttf");
                    usePixelGoogleSans = roboto != null && hasSimilarMetrics(Typeface.create("sans-serif", Typeface.NORMAL), roboto);
                }
            }
        }
        return usePixelGoogleSans;
    }

    private static boolean hasSimilarMetrics(Typeface a, Typeface b) {
        String sample = "Hamburgefontsiv0123456789";
        Paint paint = new Paint();
        paint.setTextSize(100);
        float[] widthsA = new float[sample.length()];
        float[] widthsB = new float[sample.length()];
        paint.setTypeface(a);
        paint.getTextWidths(sample, widthsA);
        paint.setTypeface(b);
        paint.getTextWidths(sample, widthsB);
        for (int i = 0; i < sample.length(); i++) {
            if (widthsB[i] == 0 || Math.abs(widthsA[i] - widthsB[i]) / widthsB[i] > 0.01f) {
                return false;
            }
        }
        return true;
    }

    private static Typeface baseSans() {
        if (!usePixelGoogleSans()) {
            return Typeface.create("sans-serif", Typeface.NORMAL);
        }
        if (!googleSansLoaded) {
            googleSansLoaded = true;
            for (String alias : new String[]{"google-sans-text", "google-sans"}) {
                Typeface typeface = Typeface.create(alias, Typeface.NORMAL);
                if (rendersDifferently(typeface, Typeface.DEFAULT)) {
                    googleSans = typeface;
                    return googleSans;
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                File file = googleSansFile();
                if (file != null) {
                    googleSans = Typeface.createFromFile(file);
                }
            }
        }
        return googleSans != null ? googleSans : Typeface.create("sans-serif", Typeface.NORMAL);
    }

    private static Typeface googleSansMedium() {
        if (!usePixelGoogleSans()) {
            return null;
        }
        if (!googleSansMediumLoaded) {
            googleSansMediumLoaded = true;
            for (String alias : new String[]{"variable-title-medium-emphasized", "variable-title-medium"}) {
                Typeface typeface = Typeface.create(alias, Typeface.NORMAL);
                if (rendersDifferently(typeface, Typeface.DEFAULT)) {
                    googleSansMedium = typeface;
                    break;
                }
            }
        }
        return googleSansMedium;
    }

    private static Typeface weightedSans(int weight, boolean italic) {
        if (weight >= WEIGHT_MEDIUM && weight < WEIGHT_BOLD) {
            Typeface medium = googleSansMedium();
            if (medium != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    return Typeface.create(medium, weight, italic);
                }
                return italic ? Typeface.create(medium, Typeface.ITALIC) : medium;
            }
        }
        Typeface base = baseSans();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(base, weight, italic);
        }
        if (weight >= WEIGHT_BOLD) {
            return Typeface.create(base, italic ? Typeface.BOLD_ITALIC : Typeface.BOLD);
        }
        return italic ? Typeface.create(base, Typeface.ITALIC) : base;
    }

    /** The weight if the font really has it, else the nearest stand-in (sans-serif-medium / black, or bold). */
    private static Typeface resolveSans(int weight, boolean italic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface weighted = weightedSans(weight, italic);
            if (weight == WEIGHT_REGULAR || differsFromRegular(weighted, italic)) {
                return weighted;
            }
        }
        Typeface base = baseSans();
        if (weight >= WEIGHT_EXTRA_BOLD) {
            Typeface black = Typeface.create("sans-serif-black", italic ? Typeface.ITALIC : Typeface.NORMAL);
            return differsFromRegular(black, italic) ? black : Typeface.create(base, italic ? Typeface.BOLD_ITALIC : Typeface.BOLD);
        }
        if (weight >= WEIGHT_MEDIUM) {
            Typeface googleMedium = weight < WEIGHT_BOLD ? googleSansMedium() : null;
            if (googleMedium != null && differsFromRegular(googleMedium, italic)) {
                return googleMedium;
            }
            Typeface medium = Typeface.create("sans-serif-medium", italic ? Typeface.ITALIC : Typeface.NORMAL);
            return differsFromRegular(medium, italic) ? medium : Typeface.create(base, italic ? Typeface.BOLD_ITALIC : Typeface.BOLD);
        }
        return Typeface.create(base, italic ? Typeface.ITALIC : Typeface.NORMAL);
    }

    private static File googleSansFile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        try {
            for (Font font : SystemFonts.getAvailableFonts()) {
                File file = font.getFile();
                if (file == null) {
                    continue;
                }
                String name = file.getName().toLowerCase(Locale.US);
                boolean googleSans = name.contains("googlesanstext") || name.contains("google-sans-text") || name.contains("googlesans") || name.contains("google-sans");
                if (googleSans && !name.contains("medium") && !name.contains("bold") && !name.contains("italic") && !name.contains("condensed")) {
                    return file;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private static Typeface fromAssets(String path) {
        try {
            return Typeface.createFromAsset(ApplicationLoader.applicationContext.getAssets(), path);
        } catch (Throwable e) {
            return null;
        }
    }
}
