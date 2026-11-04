package org.telegram.rawgram.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/**
 * Decodes the cover embedded in an audio file at a size fit for the big player cover: subsampled while the long side
 * stays at least {@link #TARGET_SIZE}, then scaled down to it when the result is still above {@link #MAX_SIZE}.
 * The stock decoders capped covers at 800 px, which looked soft on the full-width cover of the new player.
 *
 * <p>Ported from exteraGram (exteraSquad, GPL-2.0-or-later): CoverDecoder.
 */
public final class CoverDecoder {

    private static final int TARGET_SIZE = 1280;
    private static final int MAX_SIZE = 1600;

    private CoverDecoder() {
    }

    /**
     * Hook for the tag readers: the full-size decode when the new player is on, the stock decode with the caller's
     * options otherwise (so stock memory use is unchanged).
     */
    public static Bitmap decodeOr(byte[] data, BitmapFactory.Options stockOptions) {
        if (RawPlayerConfig.getNewPlayerStyle()) {
            return decode(data);
        }
        return data == null ? null : BitmapFactory.decodeByteArray(data, 0, data.length, stockOptions);
    }

    public static Bitmap decode(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        int size = Math.max(options.outWidth, options.outHeight);
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while ((size /= 2) >= TARGET_SIZE) {
            options.inSampleSize *= 2;
        }
        Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (decoded == null) {
            return null;
        }
        int decodedSize = Math.max(decoded.getWidth(), decoded.getHeight());
        if (decodedSize <= MAX_SIZE) {
            return decoded;
        }
        float scale = (float) TARGET_SIZE / decodedSize;
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, Math.max(1, Math.round(decoded.getWidth() * scale)), Math.max(1, Math.round(decoded.getHeight() * scale)), true);
        if (scaled != decoded) {
            decoded.recycle();
        }
        return scaled;
    }
}
