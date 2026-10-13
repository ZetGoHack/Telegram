package org.telegram.rawgram;

import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LruCache;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Watches the Java heap. When it stays almost full (the freeze before an OutOfMemoryError: the
 * collector runs back to back and the UI stalls) it writes a "memory" report into the crash log
 * (same viewer): the heap history of the last minutes, which screens were open, the sizes of the
 * image caches, the busy threads. Optionally a heap dump is saved next to it (one file is kept),
 * to see which objects fill the heap. An OutOfMemoryError crash report gets the same history.
 */
public final class RawMemWatch {

    static final String TYPE_MEMORY = "memory";
    private static final String DIR = "rawgram_diag";

    private static final long SAMPLE_MS = 2000;
    private static final int HISTORY = 90;                  // 3 minutes
    private static final float FULL = 0.92f;                // share of the heap limit
    private static final int FULL_SAMPLES = 5;              // in a row: ~10 s
    private static final long REPORT_COOLDOWN_MS = 15 * 60 * 1000L;
    private static final long HPROF_COOLDOWN_MS = 6 * 60 * 60 * 1000L;
    private static final long HPROF_MIN_FREE = 2L * 1024 * 1024 * 1024;

    private static final Object lock = new Object();
    private static final long[] times = new long[HISTORY];
    private static final long[] used = new long[HISTORY];
    private static final long[] nativeUsed = new long[HISTORY];
    private static final String[] screens = new String[HISTORY];
    private static int count;
    private static int head;
    private static volatile boolean started;
    private static long lastReport;
    private static long lastHprof;
    private static Context context;

    private RawMemWatch() {
    }

    /** Called from ApplicationLoader.onCreate (after RawCrashLog.install). Never throws. */
    public static void start(Context ctx) {
        try {
            if (started || !RawgramConfig.isMemWatch()) {
                return;
            }
            context = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            started = true;
            Thread thread = new Thread(RawMemWatch::loop, "rawMemWatch");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        } catch (Throwable ignore) {
        }
    }

    private static void loop() {
        int full = 0;
        while (true) {
            try {
                Thread.sleep(SAMPLE_MS);
                if (!RawgramConfig.isMemWatch()) {
                    continue;
                }
                Runtime rt = Runtime.getRuntime();
                long max = rt.maxMemory();
                long now = rt.totalMemory() - rt.freeMemory();
                record(now);
                full = now >= max * FULL ? full + 1 : 0;
                if (full >= FULL_SAMPLES && SystemClock.elapsedRealtime() - lastReport > REPORT_COOLDOWN_MS) {
                    lastReport = SystemClock.elapsedRealtime();
                    full = 0;
                    writeReport(now, max);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignore) {
            }
        }
    }

    private static void record(long heapUsed) {
        String screen = null;
        try {
            BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            screen = fragment != null ? fragment.getClass().getSimpleName() : null;
        } catch (Throwable ignore) {
        }
        synchronized (lock) {
            int at = (head + count) % HISTORY;
            if (count == HISTORY) {
                head = (head + 1) % HISTORY;
            } else {
                count++;
            }
            times[at] = System.currentTimeMillis();
            used[at] = heapUsed;
            nativeUsed[at] = Debug.getNativeHeapAllocatedSize();
            screens[at] = screen;
        }
    }

    /** The heap history, for any report. */
    static void appendHistory(StringBuilder sb) {
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);
        long max = Runtime.getRuntime().maxMemory();
        synchronized (lock) {
            sb.append("лимит кучи: ").append(max >> 20).append(" МБ; отсчёты каждые ").append(SAMPLE_MS / 1000).append(" с, новые внизу\n");
            for (int i = 0; i < count; i++) {
                int at = (head + i) % HISTORY;
                sb.append(time.format(new Date(times[at]))).append("  куча ").append(used[at] >> 20).append(" МБ (")
                        .append(used[at] * 100 / Math.max(1, max)).append("%)  native ").append(nativeUsed[at] >> 20).append(" МБ  ")
                        .append(screens[at] == null ? "" : screens[at]).append('\n');
            }
        }
    }

    /** Memory part of an OutOfMemoryError crash report. */
    static void appendForCrash(StringBuilder sb) {
        try {
            RawCrashLog.appendSection(sb, "memory", "Память");
            appendMemory(sb);
            sb.append('\n');
            appendHistory(sb);
            appendCaches(sb);
        } catch (Throwable ignore) {
        }
    }

    private static void appendMemory(StringBuilder sb) {
        Runtime rt = Runtime.getRuntime();
        sb.append("куча Java: ").append((rt.totalMemory() - rt.freeMemory()) >> 20).append(" МБ из ").append(rt.maxMemory() >> 20)
                .append(" МБ (выделено ").append(rt.totalMemory() >> 20).append(" МБ)\n");
        sb.append("native: ").append(Debug.getNativeHeapAllocatedSize() >> 20).append(" МБ из ").append(Debug.getNativeHeapSize() >> 20).append(" МБ\n");
        try {
            Debug.MemoryInfo info = new Debug.MemoryInfo();
            Debug.getMemoryInfo(info);
            sb.append("PSS: ").append(info.getTotalPss() >> 10).append(" МБ");
            String graphics = info.getMemoryStat("summary.graphics");
            String code = info.getMemoryStat("summary.code");
            if (graphics != null) {
                sb.append(", графика ").append(Integer.parseInt(graphics) >> 10).append(" МБ");
            }
            if (code != null) {
                sb.append(", код ").append(Integer.parseInt(code) >> 10).append(" МБ");
            }
            sb.append('\n');
        } catch (Throwable ignore) {
        }
    }

    /** Sizes of the image caches and what the open chats hold. Reads UI objects: best effort. */
    private static void appendCaches(StringBuilder sb) {
        RawCrashLog.appendSection(sb, "memory_caches", "Кеши и экраны");
        for (String name : new String[]{"memCache", "smallImagesMemCache", "wallpaperMemCache", "lottieMemCache"}) {
            try {
                Field field = ImageLoader.class.getDeclaredField(name);
                field.setAccessible(true);
                LruCache<?> cache = (LruCache<?>) field.get(ImageLoader.getInstance());
                if (cache != null) {
                    sb.append(name).append(": ").append(cache.size() >> 20).append(" МБ из ").append(cache.maxSize() >> 20).append(" МБ\n");
                }
            } catch (Throwable ignore) {
            }
        }
        final StringBuilder screensOut = new StringBuilder();
        Runnable collect = () -> {
            try {
                LaunchActivity activity = LaunchActivity.instance;
                List<BaseFragment> stack = activity != null && activity.actionBarLayout != null ? activity.actionBarLayout.getFragmentStack() : null;
                if (stack == null) {
                    screensOut.append("(стек экранов недоступен)\n");
                    return;
                }
                for (int i = 0; i < stack.size(); i++) {
                    BaseFragment fragment = stack.get(i);
                    screensOut.append(i + 1).append(". ").append(fragment.getClass().getSimpleName());
                    if (fragment instanceof ChatActivity) {
                        ChatActivity chat = (ChatActivity) fragment;
                        screensOut.append("  диалог ").append(chat.getDialogId()).append(", сообщений в списке ").append(chat.messages.size());
                    }
                    screensOut.append('\n');
                }
            } catch (Throwable e) {
                screensOut.append("(не удалось прочитать экраны: ").append(e).append(")\n");
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            collect.run();
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    collect.run();
                } finally {
                    latch.countDown();
                }
            });
            try {
                // a stalled UI thread is exactly the case: do not wait for it long
                if (!latch.await(4, TimeUnit.SECONDS)) {
                    screensOut.append("(UI-поток не ответил за 4 с)\n");
                }
            } catch (InterruptedException ignore) {
            }
        }
        sb.append(screensOut);
    }

    private static void writeReport(long heapUsed, long max) {
        try {
            long now = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder(32 * 1024);
            sb.append("rawGram memory report\n");
            sb.append("type: ").append(TYPE_MEMORY).append('\n');
            RawCrashLog.appendTime(sb, now);
            sb.append("exception: куча Java почти заполнена: ").append(heapUsed >> 20).append(" из ").append(max >> 20)
                    .append(" МБ (").append(heapUsed * 100 / Math.max(1, max)).append("%)\n");
            sb.append("pid: ").append(android.os.Process.myPid()).append('\n');
            RawCrashLog.appendShortDevice(sb);

            RawCrashLog.appendSection(sb, "memory", "Память");
            appendMemory(sb);
            sb.append('\n');
            appendHistory(sb);
            appendCaches(sb);

            RawCrashLog.appendSection(sb, RawCrashLog.SEC_THREADS, "Потоки");
            RawCrashLog.appendJavaThreads(sb, null, 24 * 1024);

            File report = RawCrashLog.writeReport(TYPE_MEMORY, now, sb);
            showWarning(heapUsed, max, report);
            if (report != null && RawgramConfig.isMemHprof()) {
                try {
                    Thread.sleep(2500); // let the bulletin render: the dump freezes the app for 10-20 s
                } catch (InterruptedException ignore) {
                }
                dumpHeap(report, now);
            }
        } catch (Throwable ignore) {
        }
    }

    /** rawGram warning (red accent) at the bottom: the heap is almost full, a leak is likely. Tap opens the report. */
    static void showWarning(long heapUsed, long max, File report) {
        AndroidUtilities.runOnUIThread(() -> RawNotify.warn("Возможна утечка памяти: куча " + (heapUsed >> 20) + " из " + (max >> 20)
                + " МБ" + (report != null ? ". Нажми, чтобы открыть отчёт" : ""), report == null ? null : () -> {
            BaseFragment last = LaunchActivity.getSafeLastFragment();
            if (last != null) {
                last.presentFragment(new RawCrashLogActivity(report));
            }
        }));
    }

    /** Debug menu: the current heap in one line. */
    public static String heapLine() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory() >> 20) + " из " + (rt.maxMemory() >> 20) + " МБ";
    }

    /** Debug menu: native allocations in one line. */
    public static String nativeLine() {
        return (Debug.getNativeHeapAllocatedSize() >> 20) + " МБ";
    }

    /** Debug menu: a heap dump on demand (replaces the previous one, no cooldown). Blocks while it is written; call off the UI thread. */
    public static File dumpNow(Context ctx) throws java.io.IOException {
        File dir = new File(ctx.getFilesDir(), DIR);
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                if (f.getName().endsWith(".hprof")) {
                    f.delete();
                }
            }
        }
        if (dir.getUsableSpace() < HPROF_MIN_FREE) {
            throw new java.io.IOException("мало свободного места");
        }
        File dump = new File(dir, "heap_" + System.currentTimeMillis() + ".hprof");
        Debug.dumpHprofData(dump.getAbsolutePath());
        return dump;
    }

    /** One heap dump is kept; at most one per few hours and only with enough free space. */
    private static void dumpHeap(File report, long now) {
        try {
            if (lastHprof != 0 && SystemClock.elapsedRealtime() - lastHprof < HPROF_COOLDOWN_MS) {
                return;
            }
            File dir = new File(context.getFilesDir(), DIR);
            dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) {
                for (File f : old) {
                    if (f.getName().endsWith(".hprof")) {
                        f.delete();
                    }
                }
            }
            if (dir.getUsableSpace() < HPROF_MIN_FREE) {
                appendToReport(report, "\nдамп кучи не сделан: мало свободного места\n");
                return;
            }
            lastHprof = SystemClock.elapsedRealtime();
            File dump = new File(dir, "heap_" + now + ".hprof");
            long start = SystemClock.elapsedRealtime();
            Debug.dumpHprofData(dump.getAbsolutePath());
            appendToReport(report, "\nдамп кучи: " + dump.getAbsolutePath() + " (" + (dump.length() >> 20) + " МБ, "
                    + (SystemClock.elapsedRealtime() - start) / 1000 + " с)\n"
                    + "забрать: adb exec-out run-as " + context.getPackageName() + " cat files/" + DIR + "/" + dump.getName() + " > heap.hprof\n");
        } catch (Throwable e) {
            appendToReport(report, "\nдамп кучи не удался: " + e + "\n");
        }
    }

    private static void appendToReport(File report, String text) {
        try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(report, true), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write(text);
        } catch (Throwable ignore) {
        }
    }

    /** For the settings row: the newest heap dump, if any. */
    public static File lastDump() {
        try {
            File[] files = new File(context != null ? context.getFilesDir() : org.telegram.messenger.ApplicationLoader.applicationContext.getFilesDir(), DIR).listFiles();
            File best = null;
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".hprof") && (best == null || f.lastModified() > best.lastModified())) {
                        best = f;
                    }
                }
            }
            return best;
        } catch (Throwable e) {
            return null;
        }
    }
}
