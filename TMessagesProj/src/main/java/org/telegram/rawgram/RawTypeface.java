package org.telegram.rawgram;

/*
 * System fonts instead of the bundled Roboto assets.
 * Ported from Nagram (tw.nekomimi.nekogram.helpers.TypefaceHelper), GPLv3:
 * Copyright (C) Nekogram / NekoX / Nagram contributors.
 */

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;

public class RawTypeface {

    private static final int CANVAS_SIZE = 40;
    private static Boolean mediumWeightSupported;

    /**
     * System typeface for a bundled asset path, or null to load the asset as usual
     * (option off, or a font with no system counterpart).
     */
    public static Typeface createOrNull(String assetPath) {
        if (!RawUiConfig.systemFont() || assetPath == null) {
            return null;
        }
        try {
            switch (assetPath) {
                case AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM:
                    return isMediumWeightSupported() ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.create("sans-serif", Typeface.BOLD);
                case AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM_ITALIC:
                    return isMediumWeightSupported() ? Typeface.create("sans-serif-medium", Typeface.ITALIC) : Typeface.create("sans-serif", Typeface.BOLD_ITALIC);
                case "fonts/rcondensedbold.ttf":
                    return Typeface.create("sans-serif-condensed", Typeface.BOLD);
                case AndroidUtilities.TYPEFACE_ROBOTO_EXTRA_BOLD:
                    return create(800, false);
                case "fonts/ritalic.ttf":
                    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? Typeface.create(Typeface.SANS_SERIF, 400, true) : Typeface.create("sans-serif", Typeface.ITALIC);
                case AndroidUtilities.TYPEFACE_ROBOTO_MONO:
                    return Typeface.MONOSPACE;
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Typeface create(int weight, boolean italic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(null, weight, italic);
        }
        if (weight >= 700) {
            return Typeface.create(weight == 800 ? "sans-serif-black" : "sans-serif", italic ? Typeface.BOLD_ITALIC : Typeface.BOLD);
        }
        return Typeface.create(weight == 500 ? "sans-serif-medium" : "sans-serif", italic ? Typeface.ITALIC : Typeface.NORMAL);
    }

    /** Some vendor fonts have no medium weight: then sans-serif-medium renders exactly like regular. */
    private static boolean isMediumWeightSupported() {
        if (mediumWeightSupported == null) {
            mediumWeightSupported = differsFromDefault(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return mediumWeightSupported;
    }

    private static boolean differsFromDefault(Typeface typeface) {
        Paint paint = new Paint();
        paint.setTextSize(20);
        paint.setAntiAlias(false);
        paint.setSubpixelText(false);
        paint.setFakeBoldText(false);
        String text = "RПр";
        Canvas canvas = new Canvas();
        Bitmap b1 = Bitmap.createBitmap(CANVAS_SIZE * 3, CANVAS_SIZE, Bitmap.Config.ARGB_8888);
        canvas.setBitmap(b1);
        paint.setTypeface(null);
        canvas.drawText(text, 0, CANVAS_SIZE, paint);
        Bitmap b2 = Bitmap.createBitmap(CANVAS_SIZE * 3, CANVAS_SIZE, Bitmap.Config.ARGB_8888);
        canvas.setBitmap(b2);
        paint.setTypeface(typeface);
        canvas.drawText(text, 0, CANVAS_SIZE, paint);
        boolean differs = !b1.sameAs(b2);
        b1.recycle();
        b2.recycle();
        return differs;
    }
}
