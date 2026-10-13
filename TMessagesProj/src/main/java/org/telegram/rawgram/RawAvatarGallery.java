package org.telegram.rawgram;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;

import java.util.List;

/**
 * Profile photo gallery (ProfileGalleryView) for fast browsing, e.g. an account with thousands of photos tapped
 * through like a flip book:
 * <ul>
 * <li>hold — while the next photo is still decoding, the previous one stays on screen instead of the blurred
 *     preview, and a decoded photo replaces it at once (no cross-fade);</li>
 * <li>prefetch — photos beyond the pager's own two neighbours are decoded ahead in the direction of browsing,
 *     as many as the image memory cache can keep next to the visible ones;</li>
 * <li>the image memory cache size (ImageLoader) itself, Telegram's 15–30 MB by default.</li>
 * </ul>
 * One {@link Gallery} per ProfileGalleryView.
 */
public final class RawAvatarGallery {

    private static final int MB = 1024 * 1024;
    /** A 640×640 ARGB photo, until a real one is seen. */
    private static final int DEFAULT_FRAME_BYTES = 640 * 640 * 4;
    /** Pages ViewPager keeps bound on each side (ProfileGalleryView.setOffscreenPageLimit). */
    private static final int PAGER_NEIGHBOURS = 2;
    private static final int MAX_PREFETCH = 12;
    /** How long a page may show the previous photo; after that its own preview shows (e.g. the photo is not downloaded). */
    private static final long HOLD_MS = 400;

    private RawAvatarGallery() {
    }

    // ---- image memory cache ----

    /** ImageLoader's own size, MB: min(15 or 30, memoryClass / 7). */
    public static int telegramCacheMb() {
        int memoryClass = memoryClass();
        return Math.min(memoryClass >= 192 ? 30 : 15, memoryClass / 7);
    }

    /** Largest size offered: a quarter of the Java heap limit. */
    public static int maxCacheMb() {
        return Math.max(telegramCacheMb(), roundDown8(heapMb() / 4));
    }

    /** The largest size that still leaves the heap comfortable room: an eighth of the heap limit. */
    public static int stableCacheMb() {
        return Math.max(telegramCacheMb(), Math.min(maxCacheMb(), roundDown8(heapMb() / 8)));
    }

    /** ImageLoader constructor: the memory cache size in bytes (Telegram's unless set in settings). */
    public static int cacheSize(int telegramBytes) {
        int mb = RawUiConfig.imageCacheMb();
        if (mb <= 0) {
            return telegramBytes;
        }
        return Math.min(mb, maxCacheMb()) * MB;
    }

    /** The size in effect, MB. */
    public static int cacheMb() {
        int mb = RawUiConfig.imageCacheMb();
        return mb <= 0 ? telegramCacheMb() : Math.min(mb, maxCacheMb());
    }

    private static int heapMb() {
        return (int) (Runtime.getRuntime().maxMemory() / MB);
    }

    private static int memoryClass() {
        try {
            ActivityManager am = (ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE);
            return am.getMemoryClass();
        } catch (Throwable e) {
            return 128;
        }
    }

    private static int roundDown8(int mb) {
        return mb / 8 * 8;
    }

    // ---- gallery ----

    public static final class Gallery {

        private Bitmap held;
        private ImageReceiver heldFor;
        private long heldSince;
        private int lastIndex = -1;
        private int frameBytes = DEFAULT_FRAME_BYTES;
        private ImageReceiver[] prefetch;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Rect src = new Rect();
        private final RectF dst = new RectF();
        private final Path clip = new Path();

        /** instantiateItem: a decoded photo replaces the held one at once. */
        public void setup(ImageReceiver receiver) {
            receiver.setCrossfadeDuration(RawUiConfig.galleryHoldFrame() ? 0 : ImageReceiver.DEFAULT_CROSSFADE_DURATION);
        }

        /**
         * AvatarImageView.onDraw, before the image: remembers the current page's decoded photo and, while the
         * current page only has its preview, draws that photo instead. Returns true when it drew.
         */
        public boolean draw(ImageReceiver receiver, Canvas canvas, boolean current) {
            if (!RawUiConfig.galleryHoldFrame() || receiver == null) {
                return false;
            }
            // a video avatar handed over as a ready drawable (the first page) counts as loaded
            if (receiver.hasNotThumb() || receiver.getAnimation() != null) {
                if (current && receiver.getAnimation() == null) {
                    Bitmap bitmap = receiver.getBitmap();
                    if (bitmap != null && !bitmap.isRecycled()) {
                        held = bitmap;
                        frameBytes = bitmap.getByteCount();
                    }
                }
                heldFor = null;
                return false;
            }
            Bitmap bitmap = held;
            if (!current || bitmap == null || bitmap.isRecycled()) {
                return false;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (heldFor != receiver) {
                heldFor = receiver;
                heldSince = now;
            }
            long holdLeft = HOLD_MS - (now - heldSince);
            if (holdLeft <= 0) {
                return false; // not loading in time: the page's own preview
            }
            View parent = receiver.getParentView();
            if (parent != null) {
                parent.postInvalidateDelayed(holdLeft + 16);
            }
            float w = receiver.getImageWidth();
            float h = receiver.getImageHeight();
            if (w <= 0 || h <= 0) {
                return false;
            }
            // centre crop, like the photo itself
            float scale = Math.max(w / bitmap.getWidth(), h / bitmap.getHeight());
            int cropW = Math.min(bitmap.getWidth(), Math.round(w / scale));
            int cropH = Math.min(bitmap.getHeight(), Math.round(h / scale));
            int left = (bitmap.getWidth() - cropW) / 2;
            int top = (bitmap.getHeight() - cropH) / 2;
            src.set(left, top, left + cropW, top + cropH);
            dst.set(receiver.getImageX(), receiver.getImageY(), receiver.getImageX() + w, receiver.getImageY() + h);
            int[] radii = receiver.getRoundRadius();
            boolean round = radii != null && (radii[0] != 0 || radii[1] != 0 || radii[2] != 0 || radii[3] != 0);
            canvas.save();
            if (round) {
                clip.reset();
                clip.addRoundRect(dst, new float[]{radii[0], radii[0], radii[1], radii[1], radii[2], radii[2], radii[3], radii[3]}, Path.Direction.CW);
                canvas.clipPath(clip);
            }
            canvas.drawBitmap(bitmap, src, dst, paint);
            canvas.restore();
            return true;
        }

        /**
         * onPageSelected: decodes the photos past the pager's neighbours, in the direction of browsing.
         * {@code realIndex} indexes {@code images}; positions wrap around like the gallery does.
         */
        public void prefetch(View parent, List<ImageLocation> images, List<ImageLocation> videos, List<Integer> sizes,
                             int realIndex, boolean forward, Object parentObject) {
            int count = images == null ? 0 : images.size();
            int n = prefetchCount(count);
            if (!RawUiConfig.galleryPrefetch() || n <= 0 || realIndex < 0) {
                return;
            }
            // the direction by the shorter way round, so going from the last photo to the first counts as forward
            if (lastIndex >= 0 && lastIndex < count && lastIndex != realIndex) {
                int delta = realIndex - lastIndex;
                if (Math.abs(delta) > count / 2) {
                    delta = -delta;
                }
                forward = delta > 0;
            }
            lastIndex = realIndex;
            if (prefetch == null || prefetch.length != n) {
                release();
                prefetch = new ImageReceiver[n];
                for (int i = 0; i < n; i++) {
                    prefetch[i] = new ImageReceiver(parent);
                    prefetch[i].setAllowLoadingOnAttachedOnly(false);
                    prefetch[i].onAttachedToWindow();
                }
            }
            int dir = forward ? 1 : -1;
            for (int k = 0; k < n; k++) {
                int index = ((realIndex + dir * (PAGER_NEIGHBOURS + 1 + k)) % count + count) % count; // floorMod is API 24+
                ImageLocation location = images.get(index);
                if (location == null || videos != null && index < videos.size() && videos.get(index) != null) {
                    continue; // video photos are played, not decoded
                }
                long size = sizes != null && index < sizes.size() && sizes.get(index) != null ? sizes.get(index) : 0;
                // one receiver per position (mod n), so a photo already decoding is not restarted
                ImageReceiver receiver = prefetch[index % n];
                receiver.setImage(null, null, location, null, null, null, null, size, null, parentObject, 1);
            }
        }

        /** As many photos as fit in the memory cache next to the visible page and its neighbours. */
        private int prefetchCount(int count) {
            if (count <= 2 * PAGER_NEIGHBOURS + 1) {
                return 0;
            }
            long cacheBytes = (long) cacheMb() * MB * 4 / 5; // ImageLoader gives 80% to the common cache
            int fit = (int) (cacheBytes / Math.max(1, frameBytes)) - (2 * PAGER_NEIGHBOURS + 1) - 1;
            return Math.max(0, Math.min(Math.min(MAX_PREFETCH, fit), count - (2 * PAGER_NEIGHBOURS + 1)));
        }

        /** The gallery is gone: drop the prefetched photos. */
        public void release() {
            if (prefetch != null) {
                for (ImageReceiver receiver : prefetch) {
                    receiver.onDetachedFromWindow();
                }
                prefetch = null;
            }
            held = null;
            heldFor = null;
        }
    }
}
