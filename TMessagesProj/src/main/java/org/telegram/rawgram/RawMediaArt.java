package org.telegram.rawgram;

import android.graphics.Bitmap;

/**
 * Cover art for the media notification. The cover bitmaps belong to the player and the image caches, which may recycle
 * them while the notification still refers to them; the remote control client keeps its artwork across edits, so it gets
 * a copy of its own.
 */
public final class RawMediaArt {

    private RawMediaArt() {
    }

    /** {@code null} instead of a recycled bitmap. */
    public static Bitmap alive(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled() ? bitmap : null;
    }

    /** A private copy of the bitmap, or {@code null} if it is gone or cannot be copied. */
    public static Bitmap own(Bitmap bitmap) {
        if (alive(bitmap) == null) {
            return null;
        }
        try {
            Bitmap.Config config = bitmap.getConfig();
            if (config == null || android.os.Build.VERSION.SDK_INT >= 26 && config == Bitmap.Config.HARDWARE) {
                config = Bitmap.Config.ARGB_8888;
            }
            return bitmap.copy(config, false);
        } catch (Throwable e) {
            return null;
        }
    }
}
