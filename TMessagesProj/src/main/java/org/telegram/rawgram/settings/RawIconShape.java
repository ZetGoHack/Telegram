package org.telegram.rawgram.settings;

import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.graphics.PathParser;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

/**
 * The launcher's icon shape (the system adaptive-icon mask) scaled to a given size, so the app icon in the
 * settings header looks like it does on the home screen. Square-ish system masks become a rounded square;
 * before Android 8 the icon is a circle.
 * Ported from exteraGram's IconShapeHelper (exteraSquad, GPL).
 */
public final class RawIconShape {

    private static final String CIRCLE_PATH = "M50,0A50,50,0,0,1,50,100A50,50,0,0,1,50,0";

    private static boolean initialized;
    private static Path systemPath;
    private static boolean systemPathSquare;

    private RawIconShape() {
    }

    /** The icon shape, {@code widthDp} × {@code heightDp}; {@code cornerRadiusDp} is used when the system mask is square. */
    public static Path get(float widthDp, float heightDp, float cornerRadiusDp) {
        boolean useSystem = Build.VERSION.SDK_INT >= 26;
        if (useSystem && !initialized) {
            initSystemPath();
        }
        float width = AndroidUtilities.dpf2(widthDp);
        float height = AndroidUtilities.dpf2(heightDp);
        float radius = AndroidUtilities.dpf2(cornerRadiusDp);
        if (useSystem && systemPath != null && systemPathSquare && radius > 0) {
            Path path = new Path();
            path.addRoundRect(new RectF(0, 0, width, height), radius, radius, Path.Direction.CW);
            return path;
        }
        Path source = useSystem && systemPath != null ? systemPath : PathParser.createPathFromPathData(CIRCLE_PATH);
        return resize(source, width, height);
    }

    private static Path resize(Path path, float width, float height) {
        Path result = new Path();
        if (path == null || path.isEmpty() || width <= 0 || height <= 0) {
            return result;
        }
        RectF bounds = new RectF();
        path.computeBounds(bounds, true);
        if (bounds.width() > 0 && bounds.height() > 0) {
            Matrix matrix = new Matrix();
            matrix.setRectToRect(bounds, new RectF(0, 0, width, height), Matrix.ScaleToFit.FILL);
            path.transform(matrix, result);
        }
        return result;
    }

    private static void initSystemPath() {
        try {
            Path path = null;
            if (Build.VERSION.SDK_INT >= 26) {
                ColorDrawable empty = new ColorDrawable(0);
                Path mask = new AdaptiveIconDrawable(empty, empty).getIconMask();
                if (mask != null && !mask.isEmpty()) {
                    path = new Path(mask);
                }
            }
            if (path == null) {
                Resources system = Resources.getSystem();
                int id = system.getIdentifier("config_icon_mask", "string", "android");
                if (id != 0) {
                    String data = system.getString(id);
                    if (!TextUtils.isEmpty(data)) {
                        path = PathParser.createPathFromPathData(data);
                    }
                }
            }
            if (path != null && !path.isEmpty()) {
                RectF bounds = new RectF();
                path.computeBounds(bounds, true);
                systemPath = path;
                systemPathSquare = isSquare(path, bounds);
            }
        } catch (Exception e) {
            FileLog.e(e);
            systemPath = null;
            systemPathSquare = false;
        } finally {
            initialized = true;
        }
    }

    /** A plain rectangle, or a shape that fills all four corners: shown as a rounded square instead. */
    private static boolean isSquare(Path path, RectF bounds) {
        if (path.isEmpty() || bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }
        Region clip = new Region((int) bounds.left, (int) bounds.top, (int) bounds.right, (int) bounds.bottom);
        Region region = new Region();
        region.setPath(path, clip);
        if (region.isRect()) {
            return true;
        }
        int corner = Math.max(3, (int) (Math.min(bounds.width(), bounds.height()) * 0.1f));
        int l = (int) bounds.left, t = (int) bounds.top, r = (int) bounds.right, b = (int) bounds.bottom;
        Rect[] corners = {
                new Rect(l, t, l + corner, t + corner),
                new Rect(r - corner, t, r, t + corner),
                new Rect(r - corner, b - corner, r, b),
                new Rect(l, b - corner, l + corner, b)
        };
        Region test = new Region();
        for (Rect rect : corners) {
            test.set(region);
            if (!test.op(rect, Region.Op.INTERSECT)) {
                return false;
            }
        }
        return true;
    }
}
