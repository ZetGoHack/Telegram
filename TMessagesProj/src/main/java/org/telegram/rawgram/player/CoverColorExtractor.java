package org.telegram.rawgram.player;

import android.graphics.Bitmap;
import android.os.Build;
import android.util.LruCache;
import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

/**
 * Ported from exteraGram (exteraSquad, GPL-2.0-or-later): CoverColorExtractor.
 */
public abstract class CoverColorExtractor {
    private static final LruCache<String, Integer> cache = new LruCache<>(96);
    private static final LruCache<String, int[]> glowCache = new LruCache<>(96);
    private static final LruCache<String, Integer> sourceSizes = new LruCache<>(96);
    private static final HashMap<String, ArrayList<Utilities.Callback<Integer>>> pending = new HashMap<>();
    private static final HashMap<String, Integer> pendingSizes = new HashMap<>();

    public static String getKey(MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }
        TLRPC.Document document = messageObject.getDocument();
        if (document != null && document.id != 0) {
            return "d" + document.id;
        }
        return "m" + messageObject.getDialogId() + "_" + messageObject.getId();
    }

    public static Integer getCachedSeed(MessageObject messageObject) {
        String key = getKey(messageObject);
        if (key == null) {
            return null;
        }
        return cache.get(key);
    }

    public static int[] getCachedGlow(MessageObject messageObject) {
        String key = getKey(messageObject);
        if (key == null) {
            return null;
        }
        return glowCache.get(key);
    }

    public static void extract(MessageObject messageObject, ImageReceiver imageReceiver, Utilities.Callback<Integer> callback) {
        final String key = getKey(messageObject);
        final ImageReceiver.BitmapHolder bitmapSafe = key != null ? imageReceiver.getBitmapSafe() : null;
        if (bitmapSafe == null) {
            return;
        }
        if (bitmapSafe.isRecycled()) {
            bitmapSafe.release();
            return;
        }
        final int iMax = Math.max(bitmapSafe.getWidth(), bitmapSafe.getHeight());
        Integer num = cache.get(key);
        Integer num2 = sourceSizes.get(key);
        HashMap<String, ArrayList<Utilities.Callback<Integer>>> map = pending;
        ArrayList<Utilities.Callback<Integer>> arrayList = map.get(key);
        HashMap<String, Integer> map2 = pendingSizes;
        Integer num3 = map2.get(key);
        if (arrayList == null && num != null && (num2 == null || iMax <= num2.intValue() * 1.5f)) {
            bitmapSafe.release();
            if (callback != null) {
                callback.run(num);
                return;
            }
            return;
        }
        if (arrayList != null && num3 != null && iMax <= num3.intValue() * 1.5f) {
            bitmapSafe.release();
            if (callback != null) {
                arrayList.add(callback);
                return;
            }
            return;
        }
        if (arrayList == null) {
            arrayList = new ArrayList<>();
            map.put(key, arrayList);
        }
        if (callback != null) {
            arrayList.add(callback);
        }
        map2.put(key, Integer.valueOf(iMax));
        Utilities.globalQueue.postRunnable(() -> extractOnQueue(bitmapSafe, key, iMax));
    }

    private static void extractOnQueue(final ImageReceiver.BitmapHolder holder, final String key, final int sourceSize) {
        int seed = 0;
        int[] glow = null;
        Bitmap sample = null;
        try {
            sample = createSample(holder.bitmap);
            List<Palette.Swatch> swatches = new Palette.Builder(sample).maximumColorCount(24).clearFilters().generate().getSwatches();
            seed = computeSeed(swatches);
            if (seed != 0) {
                glow = computeGlow(swatches, seed);
            }
        } catch (Throwable th) {
            FileLog.e(th);
        } finally {
            if (sample != null) {
                sample.recycle();
            }
        }
        final int resultSeed = seed;
        final int[] resultGlow = glow;
        AndroidUtilities.runOnUIThread(() -> applyResult(holder, key, sourceSize, resultSeed, resultGlow));
    }

    private static void applyResult(ImageReceiver.BitmapHolder bitmapHolder, String str, int i, int i2, int[] iArr) {
        bitmapHolder.release();
        LruCache<String, Integer> lruCache = sourceSizes;
        Integer num = lruCache.get(str);
        if (num == null || i >= num.intValue()) {
            cache.put(str, Integer.valueOf(i2));
            lruCache.put(str, Integer.valueOf(i));
            if (iArr != null) {
                glowCache.put(str, iArr);
            } else {
                glowCache.remove(str);
            }
        }
        HashMap<String, Integer> map = pendingSizes;
        Integer num2 = map.get(str);
        if (num2 == null || num2.intValue() != i) {
            return;
        }
        map.remove(str);
        ArrayList<Utilities.Callback<Integer>> arrayListRemove = pending.remove(str);
        Integer num3 = cache.get(str);
        if (arrayListRemove == null || num3 == null) {
            return;
        }
        int size = arrayListRemove.size();
        int i3 = 0;
        while (i3 < size) {
            Utilities.Callback<Integer> callback = arrayListRemove.get(i3);
            i3++;
            callback.run(num3);
        }
    }

    private static Bitmap createSample(Bitmap bitmap) {
        Bitmap bitmapCopy = (Build.VERSION.SDK_INT < 26 || bitmap.getConfig() != Bitmap.Config.HARDWARE) ? bitmap : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        int width = bitmapCopy.getWidth();
        int height = bitmapCopy.getHeight();
        float fMin = Math.min(1.0f, 112.0f / Math.max(width, height));
        Bitmap bitmapCreateScaledBitmap = Bitmap.createScaledBitmap(bitmapCopy, Math.max(1, Math.round(width * fMin)), Math.max(1, Math.round(height * fMin)), true);
        if (bitmapCreateScaledBitmap == bitmap) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
        if (bitmapCopy != bitmap && bitmapCopy != bitmapCreateScaledBitmap) {
            bitmapCopy.recycle();
        }
        return bitmapCreateScaledBitmap;
    }

    private static int computeSeed(List<Palette.Swatch> list) {
        int i;
        int i2;
        int i3;
        int i4;
        if (list.isEmpty()) {
            return 0;
        }
        int size = list.size();
        double[] dArr = new double[size];
        double[] dArr2 = new double[size];
        float[] fArr = new float[size];
        double[] dArr3 = new double[3];
        Iterator<Palette.Swatch> it = list.iterator();
        long population = 0;
        while (it.hasNext()) {
            population += (long) it.next().getPopulation();
        }
        if (population <= 0) {
            return 0;
        }
        int i5 = 0;
        while (i5 < size) {
            Palette.Swatch swatch = list.get(i5);
            ColorUtils.colorToLAB(swatch.getRgb(), dArr3);
            double[] dArr4 = dArr;
            dArr2[i5] = Math.hypot(dArr3[1], dArr3[2]);
            dArr4[i5] = Math.toDegrees(Math.atan2(dArr3[2], dArr3[1]));
            fArr[i5] = swatch.getPopulation() / population;
            i5++;
            dArr = dArr4;
        }
        double[] dArr5 = dArr;
        int i6 = -1;
        double d = -1.7976931348623157E308d;
        int i7 = 0;
        int i8 = 0;
        while (i7 < size) {
            float f = fArr[i7];
            if (f > fArr[i8]) {
                i8 = i7;
            }
            double d2 = 8.0d;
            if (dArr2[i7] < 8.0d || f < 0.01f) {
                i = size;
                i2 = i6;
            } else {
                float f2 = 0.0f;
                int i9 = 0;
                while (i9 < size) {
                    if (dArr2[i9] >= d2) {
                        i3 = size;
                        i4 = i6;
                        if (hueDistance(dArr5[i7], dArr5[i9]) <= 15.0d) {
                            f2 += fArr[i9];
                        }
                    } else {
                        i3 = size;
                        i4 = i6;
                    }
                    i9++;
                    size = i3;
                    i6 = i4;
                    d2 = 8.0d;
                }
                i = size;
                i2 = i6;
                double d3 = dArr2[i7];
                double d4 = (((double) f2) * 70.0d) + ((d3 - 48.0d) * (d3 < 48.0d ? 0.1d : 0.3d));
                if (d4 > d) {
                    d = d4;
                    i6 = i7;
                }
                i7++;
                size = i;
            }
            i6 = i2;
            i7++;
            size = i;
        }
        int i10 = i6;
        return list.get(i10 >= 0 ? i10 : i8).getRgb() | (-16777216);
    }

    /** Four glow colours: the base one, then up to three distinct swatches by weight, then hue-shifted variants. */
    private static int[] computeGlow(List<Palette.Swatch> swatches, int base) {
        long population = 0;
        for (Palette.Swatch swatch : swatches) {
            population += swatch.getPopulation();
        }
        int size = swatches.size();
        Integer[] order = new Integer[size];
        final double[] weights = new double[size];
        double[] lab = new double[3];
        for (int i = 0; i < size; i++) {
            order[i] = i;
            ColorUtils.colorToLAB(swatches.get(i).getRgb(), lab);
            double share = population > 0 ? ((double) swatches.get(i).getPopulation()) / population : 0.0;
            weights[i] = share * (((Math.hypot(lab[1], lab[2]) / 100.0) * 1.6) + 0.2);
        }
        Arrays.sort(order, (a, b) -> Double.compare(weights[b], weights[a]));
        final int count = 4;
        double[][] labs = new double[count][];
        int[] colors = new int[count];
        labs[0] = toLab(base);
        colors[0] = base | 0xFF000000;
        int filled = 1;
        for (int k = 0; k < size && filled < count; k++) {
            Palette.Swatch swatch = swatches.get(order[k]);
            if (population > 0 && ((double) swatch.getPopulation()) / population < 0.01) {
                continue;
            }
            double[] candidate = toLab(swatch.getRgb());
            boolean distinct = true;
            for (int j = 0; j < filled && distinct; j++) {
                distinct = isDistinct(candidate, labs[j]);
            }
            if (distinct) {
                labs[filled] = candidate;
                colors[filled] = swatch.getRgb() | 0xFF000000;
                filled++;
            }
        }
        for (int k = filled; k < count; k++) {
            double[] source = labs[k % filled];
            double chroma = Math.hypot(source[1], source[2]);
            double hue = Math.atan2(source[2], source[1]) + Math.toRadians(k * 18.0);
            double tone = Math.max(0.0, Math.min(100.0, source[0] + (k % 2 == 1 ? 12.0 : -12.0)));
            colors[k] = ColorUtils.LABToColor(tone, chroma * Math.cos(hue), chroma * Math.sin(hue)) | 0xFF000000;
        }
        return colors;
    }

    private static double[] toLab(int i) {
        double[] dArr = new double[3];
        ColorUtils.colorToLAB(i, dArr);
        return dArr;
    }

    private static boolean isDistinct(double[] dArr, double[] dArr2) {
        return ((Math.hypot(dArr[1], dArr[2]) > 12.0d ? 1 : (Math.hypot(dArr[1], dArr[2]) == 12.0d ? 0 : -1)) < 0) != ((Math.hypot(dArr2[1], dArr2[2]) > 12.0d ? 1 : (Math.hypot(dArr2[1], dArr2[2]) == 12.0d ? 0 : -1)) < 0) || Math.abs(dArr[0] - dArr2[0]) > 22.0d || hueDistance(Math.toDegrees(Math.atan2(dArr[2], dArr[1])), Math.toDegrees(Math.atan2(dArr2[2], dArr2[1]))) > 22.0d;
    }

    private static double hueDistance(double d, double d2) {
        double dAbs = Math.abs(d - d2) % 360.0d;
        return dAbs > 180.0d ? 360.0d - dAbs : dAbs;
    }
}