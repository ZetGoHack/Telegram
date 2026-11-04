package org.telegram.rawgram.nowplaying;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.TextUtils;

import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the «Сейчас играет» card shows: the track, its cover and two colours taken from the cover. Ported from
 * exteraGram's {@code NowPlayingCardData} (exteraGram, GPLv3).
 */
public final class NowPlayingCardData {

    /** Cover size asked from the saved-music document thumbs. */
    private static final int THUMB_SIZE = 1000;
    /** Give up on the cover after this long and show the card without colours. */
    private static final long COVER_TIMEOUT_MS = 15_000L;
    public static final long NO_USER_EMOJI = -1L;

    public final NowPlayingTrack track;
    /** Background colour from the cover, or null (theme colour). */
    public final Integer backgroundColor;
    /** Accent (play button, pattern) from the cover, or null. */
    public final Integer accentColor;
    public final Bitmap coverBitmap;
    public final ImageLocation imageLocation;
    /** Custom emoji for the pattern when the track is the saved music (profile colour emoji), else {@link #NO_USER_EMOJI}. */
    public long userEmoji;

    public interface Callback {
        void onDataLoaded(NowPlayingCardData data);
    }

    NowPlayingCardData(NowPlayingTrack track, Integer backgroundColor, Integer accentColor, Bitmap coverBitmap, ImageLocation imageLocation) {
        this.track = track;
        this.backgroundColor = backgroundColor;
        this.accentColor = accentColor;
        this.coverBitmap = coverBitmap;
        this.imageLocation = imageLocation;
        this.userEmoji = NO_USER_EMOJI;
    }

    /**
     * Loads the cover (saved-music thumb, else the track's cover URL) and its colours, then calls {@code callback} on
     * the UI thread. Without a cover the card gets theme colours.
     */
    public static void create(NowPlayingTrack track, TLRPC.Document savedMusic, Callback callback) {
        ImageLocation location = null;
        if (track.isTelegram() && savedMusic != null) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(savedMusic.thumbs, THUMB_SIZE);
            if (size != null) {
                location = ImageLocation.getForDocument(size, savedMusic);
            }
        }
        if (location == null && !TextUtils.isEmpty(track.coverUrl)) {
            location = ImageLocation.getForPath(track.coverUrl);
        }
        final ImageLocation imageLocation = location;
        if (imageLocation == null) {
            AndroidUtilities.runOnUIThread(() -> callback.onDataLoaded(new NowPlayingCardData(track, null, null, null, null)));
            return;
        }
        final ImageReceiver imageReceiver = new ImageReceiver(null);
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Runnable[] timeout = new Runnable[1];
        timeout[0] = () -> finish(finished, timeout[0], imageReceiver, callback,
                new NowPlayingCardData(track, null, null, null, imageLocation));
        AndroidUtilities.runOnUIThread(() -> {
            imageReceiver.onAttachedToWindow();
            imageReceiver.setDelegate((receiver, set, thumb, memCache) -> {
                if (!set || thumb) {
                    return;
                }
                final Bitmap bitmap = receiver.getBitmap();
                Utilities.themeQueue.postRunnable(() -> {
                    final int[] colors = extractColors(bitmap);
                    AndroidUtilities.runOnUIThread(() -> finish(finished, timeout[0], imageReceiver, callback,
                            new NowPlayingCardData(track, colors == null ? null : colors[0], colors == null ? null : colors[1], bitmap, imageLocation)));
                });
            });
            AndroidUtilities.runOnUIThread(timeout[0], COVER_TIMEOUT_MS);
            imageReceiver.setImage(imageLocation, null, (android.graphics.drawable.Drawable) null, null, null, 0);
        });
    }

    private static void finish(AtomicBoolean finished, Runnable timeout, ImageReceiver imageReceiver, Callback callback, NowPlayingCardData data) {
        if (finished.compareAndSet(false, true)) {
            AndroidUtilities.cancelRunOnUIThread(timeout);
            imageReceiver.setDelegate(null);
            imageReceiver.onDetachedFromWindow();
            callback.onDataLoaded(data);
        }
    }

    /** {background, accent} from the cover, adjusted for contrast with white text; null without a bitmap. */
    private static int[] extractColors(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }
        Palette palette = Palette.from(bitmap).generate();
        int color;
        Palette.Swatch swatch = palette.getDarkVibrantSwatch();
        if (swatch == null) {
            swatch = palette.getMutedSwatch();
        }
        if (swatch == null) {
            swatch = palette.getDarkMutedSwatch();
        }
        if (swatch == null) {
            swatch = palette.getDominantSwatch();
        }
        color = swatch != null ? swatch.getRgb() : AndroidUtilities.getDominantColor(bitmap);

        double contrast = ColorUtils.calculateContrast(Color.WHITE, color);
        if (contrast > 15.0) {
            color = adjustHsl(color, 2.0f, -1f);
        } else if (contrast < 10.0) {
            color = adjustHsl(color, 0.5f, -1f);
        }
        if (ColorUtils.calculateContrast(Color.WHITE, color) < 3.0) {
            color = ColorUtils.blendARGB(color, Color.BLACK, 0.3f);
        }
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        float lightness = hsl[2];
        float factor;
        if (lightness >= 0f && lightness <= 0.25f) {
            factor = 2.0f;
        } else if (lightness >= 0.25f && lightness <= 0.5f) {
            factor = 1.5f;
        } else if (lightness >= 0.5f && lightness <= 0.75f) {
            factor = 1.0f;
        } else {
            factor = 0.5f;
        }
        return new int[]{color, adjustHsl(color, factor, -1f)};
    }

    /** exteraGram's {@code UIUtil.adjustHsl}: scales lightness (and saturation when {@code saturation > 0}). */
    static int adjustHsl(int color, float luminance, float saturation) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        if (saturation > 0f) {
            hsl[1] = Math.min(hsl[1] * saturation, 1f);
        }
        hsl[2] = Math.min(hsl[2] * luminance, 1f);
        return ColorUtils.HSLToColor(hsl);
    }
}
