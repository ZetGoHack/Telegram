package org.telegram.rawgram;

import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * UI-thread freeze watchdog. A background thread posts a tiny task to the main looper every 20 ms; when the answer does
 * not come for the threshold (700 ms by default) a "freeze" report is written into the crash log (same viewer): stack
 * samples of the main thread taken while it was stuck, hot frames, the fragment stack, GC and heap figures, other busy
 * threads, NotificationCenter observer counts and the number of live objects of chosen classes. Off by default.
 *
 * The watcher allocates nothing while the UI is healthy (one preallocated task); stacks are sampled only while the
 * main thread is stalled, and every aggregate is bounded. Reports are built and written on the watcher thread.
 *
 * Idea and report layout ported from exteraGram's FreezeMonitor (GPLv3), rewritten as plain Java.
 */
public final class RawFreezeMonitor {

    static final String TYPE_FREEZE = "freeze";

    private static final long TICK_MS = 20;
    private static final long SAMPLE_FROM_MS = 150;       // stack sampling starts this long into a stall
    private static final long SAMPLE_EVERY_MS = 40;
    private static final long GIVE_UP_MS = 60_000;        // a stall this long is the system's doing or an ANR: stop sampling
    private static final long WATCHER_STALL_MS = 1000;    // the watcher itself did not run: the process was suspended
    private static final long COOLDOWN_MS = 20_000;
    private static final int MAX_REPORTS_PER_PROCESS = 20;
    private static final int STACK_DEPTH = 40;
    private static final int MAX_STACKS = 48;
    private static final int MAX_FRAMES = 1500;
    private static final String[] APP_PREFIXES = {"org.telegram.", "com.exteragram.", "com.chaquo.", "de.robv.android.xposed."};
    private static final String[] IDLE_TOP = {"android.os.MessageQueue.nativePollOnce", "java.lang.Object.wait", "java.lang.Thread.sleep",
            "sun.misc.Unsafe.park", "jdk.internal.misc.Unsafe.park", "libcore.io.Linux.", "android.system.Os.", "dalvik.system.VMStack."};
    private static final String[] COUNTED = {"org.telegram.ui.LaunchActivity", "org.telegram.ui.ActionBar.BaseFragment",
            "org.telegram.ui.ChatActivity", "org.telegram.ui.DialogsActivity", "org.telegram.ui.ProfileActivity",
            "org.telegram.ui.Components.ChatActivityEnterView", "org.telegram.ui.Components.EmojiView",
            "org.telegram.messenger.MessageObject", "org.telegram.ui.Cells.ChatMessageCell", "org.telegram.messenger.ImageReceiver",
            "org.telegram.ui.Components.RLottieDrawable", "org.telegram.ui.Components.AnimatedFileDrawable",
            "org.telegram.ui.Components.AnimatedEmojiDrawable", "android.graphics.Bitmap"};
    private static final String[] GC_STATS = {"art.gc.blocking-gc-count", "art.gc.blocking-gc-time", "art.gc.gc-count", "art.gc.gc-time"};

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Thread mainThread = Looper.getMainLooper().getThread();
    private static final Object startLock = new Object();

    private static volatile boolean pending;     // a task is posted and has not run yet
    private static volatile long pendingSince;
    private static volatile long lastPong;
    private static volatile Thread watcher;
    private static volatile long pausedUntil;
    private static volatile int freezes;
    private static volatile String lastLine;
    private static volatile File lastReport;
    private static long lastReportAt;
    private static int reportsWritten;

    private static final Runnable pong = () -> {
        lastPong = SystemClock.uptimeMillis();
        pending = false;
    };

    private RawFreezeMonitor() {
    }

    /** Starts the watcher when the switch is on. Idempotent, never throws. */
    public static void start() {
        try {
            synchronized (startLock) {
                if (!RawgramConfig.isFreezeMonitor() || watcher != null) {
                    return;
                }
                Thread thread = new Thread(RawFreezeMonitor::loop, "rawFreezeWatch");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY);
                watcher = thread;
                thread.start();
            }
        } catch (Throwable ignore) {
        }
    }

    /** Called when the switch is turned on or off. */
    public static void onSettingChanged() {
        if (RawgramConfig.isFreezeMonitor()) {
            start();
        }
        // when off the loop notices it and exits
    }

    /** Ignore stalls for a while (the app freezes on purpose while a heap dump is written). */
    static void pause(long ms) {
        pausedUntil = Math.max(pausedUntil, SystemClock.uptimeMillis() + ms);
    }

    public static boolean isRunning() {
        Thread thread = watcher;
        return thread != null && thread.isAlive();
    }

    /** Debug menu: milliseconds since the main thread last answered the watcher (or 0 while it answers on time). */
    public static long answerAgeMs() {
        return SystemClock.uptimeMillis() - lastPong;
    }

    /** Debug menu: freezes recorded in this process. */
    public static int freezeCount() {
        return freezes;
    }

    public static String statusLine() {
        if (!RawgramConfig.isFreezeMonitor()) {
            return "выкл";
        }
        return freezes == 0 ? "следит, зависаний нет" : "зависаний: " + freezes;
    }

    public static File lastReport() {
        return lastReport;
    }

    /** Debug menu: blocks the UI thread for a moment so the watcher has something to report. Call on the UI thread. */
    public static void simulate() {
        try {
            Thread.sleep(Math.max(1200, RawgramConfig.getFreezeThreshold() + 600L));
        } catch (InterruptedException ignore) {
        }
    }

    private static boolean foreground() {
        ForegroundDetector detector = ForegroundDetector.getInstance();
        return detector != null && detector.isForeground();
    }

    private static void loop() {
        long lastLoop = SystemClock.uptimeMillis();
        try {
            while (RawgramConfig.isFreezeMonitor()) {
                Thread.sleep(TICK_MS);
                long now = SystemClock.uptimeMillis();
                long gap = now - lastLoop;
                lastLoop = now;
                if (gap > WATCHER_STALL_MS) {
                    // the whole process was suspended (Doze, freezer) or starved: whatever is pending is not a freeze
                    pending = false;
                    continue;
                }
                if (now < pausedUntil) {
                    pending = false;
                    continue;
                }
                if (!pending) {
                    if (!foreground()) {
                        Thread.sleep(1000);
                        lastLoop = SystemClock.uptimeMillis();
                        continue;
                    }
                    pendingSince = now;
                    pending = true;
                    if (!mainHandler.post(pong)) {
                        pending = false;
                    }
                    continue;
                }
                if (now - pendingSince >= SAMPLE_FROM_MS) {
                    handleStall();
                    lastLoop = SystemClock.uptimeMillis();
                }
            }
        } catch (InterruptedException ignore) {
        } catch (Throwable ignore) {
        } finally {
            pending = false;
            synchronized (startLock) {
                watcher = null;
            }
            // switched back on while we were exiting
            if (RawgramConfig.isFreezeMonitor()) {
                start();
            }
        }
    }

    // region sampling

    private static final class Count {
        int count;
        int depth;   // deepest position from the top of a stack
    }

    private static final class Stall {
        final long posted;
        final int threshold;
        final long[] gcBefore = gcStats();
        final String heapBefore = heapLine();
        final HashMap<String, Count> states = new HashMap<>();
        final HashMap<String, Count> tops = new HashMap<>();
        final HashMap<String, Count> hot = new HashMap<>();
        final HashMap<String, Count> stacks = new HashMap<>();
        final HashSet<String> seen = new HashSet<>();
        int samples;
        int stackOverflow;
        long maxGap;
        String screen;
        String fragments;
        String otherThreads;
        String observers;
        boolean captured;

        Stall(long posted, int threshold) {
            this.posted = posted;
            this.threshold = threshold;
        }
    }

    private static void handleStall() throws InterruptedException {
        Stall stall = new Stall(pendingSince, RawgramConfig.getFreezeThreshold());
        long lastSample = SystemClock.uptimeMillis();
        while (pending && RawgramConfig.isFreezeMonitor()) {
            long now = SystemClock.uptimeMillis();
            stall.maxGap = Math.max(stall.maxGap, now - lastSample);
            lastSample = now;
            long age = now - stall.posted;
            if (now < pausedUntil) {
                return;
            }
            if (age > GIVE_UP_MS) {
                while (pending && RawgramConfig.isFreezeMonitor()) {
                    Thread.sleep(1000);
                }
                return;
            }
            sample(stall);
            if (!stall.captured && age + SAMPLE_EVERY_MS >= stall.threshold) {
                stall.captured = true;
                stall.screen = screenName();
                stall.fragments = fragmentStack();
                stall.otherThreads = otherThreads();
                stall.observers = observers();
            }
            Thread.sleep(SAMPLE_EVERY_MS);
        }
        long duration = lastPong - stall.posted;
        if (duration < stall.threshold || stall.samples == 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (reportsWritten >= MAX_REPORTS_PER_PROCESS || now - lastReportAt < COOLDOWN_MS) {
            freezes++;
            return;
        }
        lastReportAt = now;
        reportsWritten++;
        freezes++;
        write(stall, duration);
    }

    private static void sample(Stall stall) {
        StackTraceElement[] trace = mainThread.getStackTrace();
        stall.samples++;
        bump(stall.states, mainThread.getState().name(), 0);
        if (trace.length == 0) {
            return;
        }
        bump(stall.tops, trace[0].toString(), 0);
        stall.seen.clear();
        for (int i = 0; i < trace.length; i++) {
            StackTraceElement el = trace[i];
            if (!isApp(el.getClassName())) {
                continue;
            }
            String frame = el.toString();
            if (stall.seen.add(frame) && (stall.hot.size() < MAX_FRAMES || stall.hot.containsKey(frame))) {
                bump(stall.hot, frame, i);
            }
        }
        StringBuilder key = new StringBuilder(STACK_DEPTH * 60);
        for (int i = 0; i < trace.length && i < STACK_DEPTH; i++) {
            key.append(trace[i]).append('\n');
        }
        String stackKey = key.toString();
        if (stall.stacks.size() < MAX_STACKS || stall.stacks.containsKey(stackKey)) {
            bump(stall.stacks, stackKey, 0);
        } else {
            stall.stackOverflow++;
        }
    }

    private static void bump(HashMap<String, Count> map, String key, int depth) {
        Count c = map.get(key);
        if (c == null) {
            map.put(key, c = new Count());
        }
        c.count++;
        if (depth > c.depth) {
            c.depth = depth;
        }
    }

    private static boolean isApp(String className) {
        for (String prefix : APP_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // endregion

    // region figures

    private static long[] gcStats() {
        long[] result = new long[GC_STATS.length];
        for (int i = 0; i < result.length; i++) {
            try {
                String value = Debug.getRuntimeStat(GC_STATS[i]);
                result[i] = value == null ? 0 : Long.parseLong(value);
            } catch (Throwable ignore) {
            }
        }
        return result;
    }

    private static String heapLine() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        return "java " + (used >> 20) + " МБ из " + (rt.maxMemory() >> 20) + " МБ (" + used * 100 / Math.max(1, rt.maxMemory())
                + "%), native " + (Debug.getNativeHeapAllocatedSize() >> 20) + " МБ";
    }

    private static String screenName() {
        try {
            BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            return fragment != null ? fragment.getClass().getName() : "нет";
        } catch (Throwable e) {
            return "неизвестно";
        }
    }

    private static String fragmentStack() {
        try {
            LaunchActivity activity = LaunchActivity.instance;
            List<BaseFragment> stack = activity != null && activity.actionBarLayout != null ? activity.actionBarLayout.getFragmentStack() : null;
            if (stack == null) {
                return "недоступен";
            }
            ArrayList<BaseFragment> copy = new ArrayList<>(stack);
            StringBuilder sb = new StringBuilder("(").append(copy.size()).append(") ");
            for (int i = 0; i < copy.size(); i++) {
                sb.append(i == 0 ? "" : " > ").append(copy.get(i).getClass().getSimpleName());
            }
            return sb.toString();
        } catch (Throwable e) {
            return "недоступен";
        }
    }

    private static boolean idle(StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) {
            return true;
        }
        String top = stack[0].toString();
        for (String prefix : IDLE_TOP) {
            if (top.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String otherThreads() {
        StringBuilder sb = new StringBuilder();
        try {
            int shown = 0;
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread t = entry.getKey();
                if (t == mainThread || t == Thread.currentThread()) {
                    continue;
                }
                Thread.State state = t.getState();
                if ((state != Thread.State.RUNNABLE && state != Thread.State.BLOCKED) || idle(entry.getValue())) {
                    continue;
                }
                if (shown++ >= 40 || sb.length() > 24 * 1024) {
                    break;
                }
                RawCrashLog.appendItem(sb, "\"" + t.getName() + "\" " + state);
                StackTraceElement[] stack = entry.getValue();
                for (int i = 0; i < stack.length && i < 20; i++) {
                    sb.append("  at ").append(stack[i]).append('\n');
                }
            }
        } catch (Throwable e) {
            sb.append("не удалось снять потоки: ").append(e).append('\n');
        }
        return sb.length() > 0 ? sb.toString() : "(нет)\n";
    }

    private static String delegateName(Object delegate) {
        if (delegate == null) {
            return "null";
        }
        Class<?> cls = delegate.getClass();
        if (cls.getName().endsWith("WeakObserversGroupImpl")) {
            try {
                Field field = cls.getDeclaredField("reference");
                field.setAccessible(true);
                Object target = ((WeakReference<?>) field.get(delegate)).get();
                return (target != null ? target.getClass().getName() : "очищено") + " (weak)";
            } catch (Throwable ignore) {
            }
        }
        return cls.getName();
    }

    /** Observer counts of the current account and of the global NotificationCenter: where subscriptions pile up. */
    private static String observers() {
        StringBuilder sb = new StringBuilder();
        String[] names = {"аккаунт", "глобальный"};
        NotificationCenter[] centers = {NotificationCenter.getInstance(UserConfig.selectedAccount), NotificationCenter.getGlobalInstance()};
        for (int c = 0; c < centers.length; c++) {
            try {
                Field field = NotificationCenter.class.getDeclaredField("observers");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                SparseArray<ArrayList<?>> all = (SparseArray<ArrayList<?>>) field.get(centers[c]);
                HashMap<String, Count> byClass = new HashMap<>();
                ArrayList<int[]> ids = new ArrayList<>();
                int total = 0;
                for (int i = 0; i < all.size(); i++) {
                    ArrayList<?> list = all.valueAt(i);
                    int size = list.size();
                    total += size;
                    ids.add(new int[]{size, all.keyAt(i)});
                    for (int j = 0; j < size && j < 400; j++) {
                        String name = delegateName(list.get(j));
                        bump(byClass, name.substring(name.lastIndexOf('.') + 1), 0);
                    }
                }
                Collections.sort(ids, (a, b) -> Integer.compare(b[0], a[0]));
                sb.append(names[c]).append(": ").append(total).append(" подписок на ").append(ids.size()).append(" событий\n");
                sb.append("  больше всего у: ");
                for (int i = 0; i < ids.size() && i < 8; i++) {
                    sb.append(i == 0 ? "" : ", ").append(notificationName(ids.get(i)[1])).append(' ').append(ids.get(i)[0]);
                }
                sb.append("\n  по классам: ");
                ArrayList<Map.Entry<String, Count>> entries = new ArrayList<>(byClass.entrySet());
                Collections.sort(entries, (a, b) -> Integer.compare(b.getValue().count, a.getValue().count));
                for (int i = 0; i < entries.size() && i < 12; i++) {
                    sb.append(i == 0 ? "" : ", ").append(entries.get(i).getKey()).append(' ').append(entries.get(i).getValue().count);
                }
                sb.append('\n');
            } catch (Throwable e) {
                sb.append(names[c]).append(": не удалось прочитать (").append(e).append(")\n");
            }
        }
        return sb.toString();
    }

    private static volatile SparseArray<String> notificationNames;

    private static String notificationName(int id) {
        SparseArray<String> names = notificationNames;
        if (names == null) {
            names = new SparseArray<>();
            try {
                for (Field field : NotificationCenter.class.getFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                        names.put(field.getInt(null), field.getName());
                    }
                }
            } catch (Throwable ignore) {
            }
            notificationNames = names;
        }
        String name = names.get(id);
        return name != null ? name : String.valueOf(id);
    }

    /** Counts of live instances through the runtime's debug class; the restricted API may be unavailable. */
    private static String liveObjects() {
        try {
            Class<?>[] classes = new Class<?>[COUNTED.length];
            for (int i = 0; i < classes.length; i++) {
                classes[i] = Class.forName(COUNTED[i]);
            }
            Class<?> vmDebug = Class.forName("dalvik.system.VMDebug");
            long[] counts = new long[classes.length];
            try {
                Method many = vmDebug.getDeclaredMethod("countInstancesOfClasses", Class[].class, boolean.class);
                counts = (long[]) many.invoke(null, classes, Boolean.TRUE);
            } catch (ReflectiveOperationException e) {
                Method one = vmDebug.getDeclaredMethod("countInstancesOfClass", Class.class, boolean.class);
                for (int i = 0; i < classes.length; i++) {
                    counts[i] = (Long) one.invoke(null, classes[i], Boolean.TRUE);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < classes.length; i++) {
                sb.append(String.format(Locale.US, "%7d  %s\n", counts[i], classes[i].getName()));
            }
            return sb.toString();
        } catch (Throwable e) {
            return "недоступно на этой версии Android: " + e + "\n";
        }
    }

    // endregion

    // region report

    private static String percent(int count, int total) {
        return String.format(Locale.US, "%3d%%", count * 100 / Math.max(1, total));
    }

    private static ArrayList<Map.Entry<String, Count>> sorted(HashMap<String, Count> map) {
        ArrayList<Map.Entry<String, Count>> list = new ArrayList<>(map.entrySet());
        Collections.sort(list, (a, b) -> Integer.compare(b.getValue().count, a.getValue().count));
        return list;
    }

    private static void write(Stall stall, long duration) {
        try {
            long now = System.currentTimeMillis();
            long[] gc = gcStats();
            ArrayList<Map.Entry<String, Count>> stacks = sorted(stall.stacks);
            String at = null;
            if (!stacks.isEmpty()) {
                for (String line : stacks.get(0).getKey().split("\n")) {
                    if (isApp(line)) {
                        at = line;
                        break;
                    }
                }
            }
            StringBuilder sb = new StringBuilder(48 * 1024);
            sb.append("rawGram freeze report\n");
            sb.append("type: ").append(TYPE_FREEZE).append('\n');
            RawCrashLog.appendTime(sb, now);
            sb.append("exception: UI-поток завис на ").append(String.format(Locale.US, "%.1f", duration / 1000.0)).append(" с\n");
            if (at != null) {
                sb.append("at: ").append(at).append('\n');
            }
            sb.append("pid: ").append(android.os.Process.myPid()).append('\n');
            RawCrashLog.appendShortDevice(sb);

            RawCrashLog.appendSection(sb, "freeze", "Зависание");
            sb.append("порог ").append(stall.threshold).append(" мс, снимков стека: ").append(stall.samples)
                    .append(" (с 150 мс зависания, раз в ").append(SAMPLE_EVERY_MS).append(" мс)\n");
            if (stall.maxGap > 400) {
                sb.append("ВНИМАНИЕ: сам сторожевой поток простоял ").append(stall.maxGap)
                        .append(" мс, возможно система приостановила процесс; отчёт может быть ложным\n");
            }
            sb.append("экран: ").append(stall.screen).append('\n');
            sb.append("стек экранов: ").append(stall.fragments).append('\n');
            sb.append("GC за время зависания: блокирующих ").append(gc[0] - stall.gcBefore[0]).append(" (").append(gc[1] - stall.gcBefore[1])
                    .append(" мс), всего ").append(gc[2] - stall.gcBefore[2]).append(" (").append(gc[3] - stall.gcBefore[3]).append(" мс)\n");
            sb.append("куча до:    ").append(stall.heapBefore).append('\n');
            sb.append("куча после: ").append(heapLine()).append('\n');
            sb.append("состояния UI-потока: ");
            boolean first = true;
            for (Map.Entry<String, Count> e : sorted(stall.states)) {
                sb.append(first ? "" : ", ").append(e.getKey()).append(' ').append(percent(e.getValue().count, stall.samples).trim());
                first = false;
            }
            sb.append('\n');

            RawCrashLog.appendSection(sb, "freeze_hot", "Горячие кадры");
            sb.append("доля снимков, где кадр на стеке; сверху самые частые\n");
            ArrayList<Map.Entry<String, Count>> hot = sorted(stall.hot);
            Collections.sort(hot, (a, b) -> a.getValue().count != b.getValue().count
                    ? Integer.compare(b.getValue().count, a.getValue().count) : Integer.compare(a.getValue().depth, b.getValue().depth));
            for (int i = 0; i < hot.size() && i < 25; i++) {
                sb.append(percent(hot.get(i).getValue().count, stall.samples)).append("  ").append(hot.get(i).getKey()).append('\n');
            }

            RawCrashLog.appendSection(sb, "freeze_top", "Вершина стека");
            ArrayList<Map.Entry<String, Count>> tops = sorted(stall.tops);
            for (int i = 0; i < tops.size() && i < 12; i++) {
                sb.append(percent(tops.get(i).getValue().count, stall.samples)).append("  ").append(tops.get(i).getKey()).append('\n');
            }

            RawCrashLog.appendSection(sb, "freeze_stacks", "Частые стеки");
            for (int i = 0; i < stacks.size() && i < 4; i++) {
                int n = stacks.get(i).getValue().count;
                RawCrashLog.appendItem(sb, n + " снимков, " + n * 100 / Math.max(1, stall.samples) + "%");
                for (String line : stacks.get(i).getKey().split("\n")) {
                    sb.append("  at ").append(line).append('\n');
                }
            }
            if (stall.stackOverflow > 0) {
                sb.append("(ещё ").append(stall.stackOverflow).append(" снимков с редкими стеками не сохранены)\n");
            }

            RawCrashLog.appendSection(sb, RawCrashLog.SEC_THREADS, "Другие занятые потоки");
            sb.append(stall.otherThreads == null ? "(не снято: зависание закончилось раньше порога)\n" : stall.otherThreads);

            RawCrashLog.appendSection(sb, "freeze_observers", "Подписки NotificationCenter");
            sb.append(stall.observers == null ? "(не снято)\n" : stall.observers);

            RawCrashLog.appendSection(sb, "freeze_env", "Окружение");
            ForegroundDetector detector = ForegroundDetector.getInstance();
            sb.append("аптайм процесса ").append((SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 1000)
                    .append(" с, на экране: ").append(detector != null ? detector.isForeground() : "?").append('\n');

            RawCrashLog.appendSection(sb, "freeze_live", "Живые объекты");
            Runtime.getRuntime().gc();
            sb.append("после сборки мусора\n").append(liveObjects());

            File report = RawCrashLog.writeReport(TYPE_FREEZE, now, sb);
            lastReport = report;
            lastLine = "UI-поток завис на " + String.format(Locale.US, "%.1f", duration / 1000.0) + " с";
            showWarning(duration, report);
        } catch (Throwable ignore) {
        }
    }

    static void showWarning(long durationMs, File report) {
        AndroidUtilities.runOnUIThread(() -> RawNotify.warn("Интерфейс завис на " + String.format(Locale.US, "%.1f", durationMs / 1000.0) + " с"
                + (report != null ? ". Нажми, чтобы открыть отчёт" : ""), report == null ? null : () -> {
            BaseFragment last = LaunchActivity.getSafeLastFragment();
            if (last != null) {
                last.presentFragment(new RawCrashLogActivity(report));
            }
        }));
    }

    // endregion

    /** Self-check of the debug menu. */
    static String selfCheck() throws Exception {
        if (!RawgramConfig.isFreezeMonitor()) {
            return "выключен в настройках, проверять нечего";
        }
        if (!isRunning()) {
            throw new IllegalStateException("поток сторожа не запущен");
        }
        Thread.sleep(150);
        if (answerAgeMs() > 3000 && foreground()) {
            throw new IllegalStateException("UI-поток не отвечал сторожу " + answerAgeMs() + " мс");
        }
        return "поток работает, порог " + RawgramConfig.getFreezeThreshold() + " мс, зависаний за запуск: " + freezes;
    }
}
