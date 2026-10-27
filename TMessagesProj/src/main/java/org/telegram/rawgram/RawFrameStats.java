package org.telegram.rawgram;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.LaunchActivity;

import java.util.Locale;

/**
 * fps and jank counters for the frame metrics overlay (the overlay itself is Telegram's
 * {@code FrameMetricsOverlayView}, switched from the developer tools). {@link #onFrame} is fed from
 * {@code Window.OnFrameMetricsAvailableListener} (API 24+) on the metrics thread: it does a few additions and no
 * allocation. A frame is janky when it took longer than 1.5 refresh intervals.
 *
 * The counters follow exteraGram's FrameMetricsOverlayView (GPL-2.0-or-later).
 */
public final class RawFrameStats {

    private static final long SECOND_NS = 1_000_000_000L;

    // written by the metrics thread only
    private static long windowStart;
    private static int windowFrames;
    private static long intervalNs;

    private static volatile int fps;
    private static volatile int hz = 60;
    private static volatile long frames;
    private static volatile long janky;
    private static volatile long worstNs;

    private RawFrameStats() {
    }

    /** One frame took {@code totalNs} from the vsync to the swap. */
    public static void onFrame(long totalNs, int dropped) {
        if (totalNs < 0) {
            return;
        }
        long now = SystemClock.elapsedRealtimeNanos();
        if (intervalNs == 0) {
            intervalNs = refreshIntervalNs();
        }
        frames++;
        if (totalNs > intervalNs * 3 / 2) {
            janky++;
        }
        if (totalNs > worstNs) {
            worstNs = totalNs;
        }
        if (windowStart == 0) {
            windowStart = now;
        }
        windowFrames++;
        long elapsed = now - windowStart;
        if (elapsed >= SECOND_NS) {
            fps = (int) Math.round(windowFrames * (double) SECOND_NS / elapsed);
            windowStart = now;
            windowFrames = 0;
        }
    }

    private static long refreshIntervalNs() {
        float rate = 60f;
        try {
            WindowManager wm = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);
            Display display = wm != null ? wm.getDefaultDisplay() : null;
            if (display != null && display.getRefreshRate() > 20f) {
                rate = display.getRefreshRate();
            }
        } catch (Throwable ignore) {
        }
        hz = Math.round(rate);
        return (long) (SECOND_NS / rate);
    }

    public static void reset() {
        frames = 0;
        janky = 0;
        worstNs = 0;
        fps = 0;
    }

    public static String fpsLine() {
        return String.format(Locale.US, "%-16s : %d / %d Hz", "fps", fps, hz);
    }

    public static String jankLine() {
        long total = frames;
        long bad = janky;
        return String.format(Locale.US, "%-16s : %.1f%% (%d/%d) max %d ms", "jank", total == 0 ? 0.0 : bad * 100.0 / total, bad, total, worstNs / 1_000_000L);
    }

    /** Debug menu: one line. */
    public static String statusLine() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return "нужен Android 7+";
        }
        return SharedConfig.frameMetricsEnabled ? fps + " fps, рывки " + (frames == 0 ? 0 : janky * 100 / frames) + "%" : "выкл";
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    public static boolean isOverlayOn() {
        return isSupported() && SharedConfig.frameMetricsEnabled;
    }

    /** Shows or hides the overlay (the same switch as Telegram's own hidden menu). */
    public static void setOverlay(boolean value) {
        if (!isSupported()) {
            return;
        }
        if (SharedConfig.frameMetricsEnabled != value) {
            SharedConfig.toggleFrameMetricsEnabled();
        }
        if (value) {
            reset();
        }
        LaunchActivity activity = LaunchActivity.instance;
        if (activity != null) {
            activity.checkFrameMetrics();
        }
    }

    /** Self-check of the debug menu. */
    static String selfCheck() {
        if (!isSupported()) {
            return "нужен Android 7+, на этом устройстве счётчика нет";
        }
        long before = frames;
        return "оверлей " + (SharedConfig.frameMetricsEnabled ? "включён" : "выключен") + ", кадров с включения: " + before
                + ", обновление экрана " + hz + " Гц";
    }
}
