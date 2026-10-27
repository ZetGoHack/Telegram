package org.telegram.rawgram;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Load sampler: once a minute reads the process CPU time, the per-thread CPU time (/proc/self/task), the app's network
 * traffic and the battery. While the app is in the background or the screen is off, every window of at least five
 * minutes is checked: when the process used more than N% of one core (5% by default) a "load" report goes into the
 * crash log (same viewer): threads by CPU, stacks of the busiest ones, traffic, battery, the keep-alive service state
 * and the last samples. Meant for hunting what keeps the phone awake (the HyperOS keep-alive service, push). Off by
 * default; a report can also be taken on demand from the debug menu.
 *
 * A sample is a few small file reads and a handful of objects once a minute; the report is built and written on the
 * sampler thread. Follows exteraGram's LoadMonitor (GPL-2.0-or-later), rewritten as plain Java without health stats.
 */
public final class RawLoadMonitor {

    static final String TYPE_LOAD = "load";

    private static final long SAMPLE_MS = 60_000;
    private static final long WINDOW_MS = 5 * 60_000;
    private static final long COOLDOWN_MS = 30 * 60_000;
    private static final int HISTORY = 30;
    private static final int TOP_THREADS = 15;
    private static final int STACK_THREADS = 6;
    private static final long MANUAL_MS = 10_000;
    private static final String[] STATE_NAMES = {"на экране", "в фоне", "экран выключен"};

    private static final Object startLock = new Object();
    private static volatile Thread sampler;
    private static volatile File lastReport;
    private static volatile String lastLine;
    private static volatile String pendingAlert;
    private static volatile File pendingAlertReport;
    private static volatile int lastPercentX10 = -1;
    private static long lastReportAt;
    private static long clockTicks;

    // sampler thread only
    private static Snapshot window;
    private static Snapshot previous;
    private static final long[] historyTime = new long[HISTORY];
    private static final int[] historyState = new int[HISTORY];
    private static final int[] historyCpuX10 = new int[HISTORY];
    private static final long[] historyRx = new long[HISTORY];
    private static final long[] historyTx = new long[HISTORY];
    private static int historyCount;
    private static int historyHead;

    private RawLoadMonitor() {
    }

    private static final class Snapshot {
        final long realtime = SystemClock.elapsedRealtime();
        final long wall = System.currentTimeMillis();
        final long cpuMs = Process.getElapsedCpuTime();
        final long rx = TrafficStats.getUidRxBytes(Process.myUid());
        final long tx = TrafficStats.getUidTxBytes(Process.myUid());
        final int state = currentState();
        final HashMap<Integer, ThreadStat> threads = readThreads();
    }

    private static final class ThreadStat {
        final String name;
        final long ms;

        ThreadStat(String name, long ms) {
            this.name = name;
            this.ms = ms;
        }
    }

    /** Starts the sampler when the switch is on. Idempotent, never throws. */
    public static void start() {
        try {
            synchronized (startLock) {
                if (!RawgramConfig.isLoadMonitor() || sampler != null) {
                    return;
                }
                Thread thread = new Thread(RawLoadMonitor::loop, "rawLoadMonitor");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                sampler = thread;
                thread.start();
            }
        } catch (Throwable ignore) {
        }
    }

    public static void onSettingChanged() {
        if (RawgramConfig.isLoadMonitor()) {
            start();
        }
        // when off the loop notices it and exits
    }

    public static File lastReport() {
        return lastReport;
    }

    public static String statusLine() {
        if (!RawgramConfig.isLoadMonitor()) {
            return "выкл";
        }
        int x10 = lastPercentX10;
        return x10 < 0 ? "следит" : "CPU " + x10 / 10 + "." + x10 % 10 + "% за минуту";
    }

    private static void loop() {
        try {
            while (RawgramConfig.isLoadMonitor()) {
                Snapshot now = new Snapshot();
                if (previous != null) {
                    long wall = now.realtime - previous.realtime;
                    int x10 = percentX10(now.cpuMs - previous.cpuMs, wall);
                    lastPercentX10 = x10;
                    record(now, previous, x10);
                }
                previous = now;
                step(now);
                showPending();
                Thread.sleep(SAMPLE_MS);
            }
        } catch (InterruptedException ignore) {
        } catch (Throwable ignore) {
        } finally {
            previous = null;
            window = null;
            synchronized (startLock) {
                sampler = null;
            }
            if (RawgramConfig.isLoadMonitor()) {
                start();
            }
        }
    }

    /** Window logic: only while not in the foreground; a window is judged once it is long enough, then restarted. */
    private static void step(Snapshot now) {
        if (now.state == 0) {
            window = null;
            return;
        }
        if (window == null) {
            window = now;
            return;
        }
        long wall = now.realtime - window.realtime;
        if (wall < WINDOW_MS) {
            return;
        }
        Snapshot start = window;
        window = now;
        int x10 = percentX10(now.cpuMs - start.cpuMs, wall);
        if (x10 < RawgramConfig.getLoadCpuPercent() * 10 || SystemClock.elapsedRealtime() - lastReportAt < COOLDOWN_MS && lastReportAt != 0) {
            return;
        }
        lastReportAt = SystemClock.elapsedRealtime();
        File report = write(start, now, "в фоне: CPU " + x10 / 10 + "." + x10 % 10 + "% за " + wall / 60_000 + " мин");
        if (report != null) {
            pendingAlert = "Приложение грузит процессор в фоне: " + x10 / 10 + "%";
            pendingAlertReport = report;
        }
    }

    private static void showPending() {
        String text = pendingAlert;
        File report = pendingAlertReport;
        ForegroundDetector detector = ForegroundDetector.getInstance();
        if (text == null || detector == null || !detector.isForeground()) {
            return;
        }
        pendingAlert = null;
        pendingAlertReport = null;
        AndroidUtilities.runOnUIThread(() -> RawNotify.warn(text + ". Нажми, чтобы открыть отчёт", () -> {
            BaseFragment last = LaunchActivity.getSafeLastFragment();
            if (last != null && report != null) {
                last.presentFragment(new RawCrashLogActivity(report));
            }
        }));
    }

    /** Debug menu: measures about ten seconds and writes a report whatever the load. Blocks, call off the UI thread. */
    public static File captureNow() {
        try {
            Snapshot start = new Snapshot();
            Thread.sleep(MANUAL_MS);
            Snapshot end = new Snapshot();
            long wall = end.realtime - start.realtime;
            int x10 = percentX10(end.cpuMs - start.cpuMs, wall);
            return write(start, end, "снято по запросу: CPU " + x10 / 10 + "." + x10 % 10 + "% за " + wall / 1000 + " с");
        } catch (Throwable e) {
            return null;
        }
    }

    // region readings

    private static int percentX10(long cpuMs, long wallMs) {
        return wallMs <= 0 ? 0 : (int) Math.max(0, cpuMs * 1000 / wallMs);
    }

    private static int currentState() {
        try {
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                return 2;
            }
        } catch (Throwable ignore) {
        }
        ForegroundDetector detector = ForegroundDetector.getInstance();
        return detector != null && detector.isForeground() ? 0 : 1;
    }

    private static long ticks() {
        if (clockTicks == 0) {
            long value = 0;
            try {
                value = Os.sysconf(OsConstants._SC_CLK_TCK);
            } catch (Throwable ignore) {
            }
            clockTicks = value > 0 ? value : 100;
        }
        return clockTicks;
    }

    private static HashMap<Integer, ThreadStat> readThreads() {
        HashMap<Integer, ThreadStat> result = new HashMap<>();
        String[] ids = new File("/proc/self/task").list();
        if (ids == null) {
            return result;
        }
        byte[] buffer = new byte[1024];
        long tick = ticks();
        for (String id : ids) {
            try (FileInputStream in = new FileInputStream("/proc/self/task/" + id + "/stat")) {
                int n = in.read(buffer);
                if (n <= 0) {
                    continue;
                }
                String stat = new String(buffer, 0, n, StandardCharsets.UTF_8);
                int open = stat.indexOf('(');
                int close = stat.lastIndexOf(')');
                if (open < 0 || close <= open || close + 2 >= stat.length()) {
                    continue;
                }
                String[] fields = stat.substring(close + 2).split(" ");
                if (fields.length < 13) {
                    continue;
                }
                long ticksUsed = Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
                result.put(Integer.parseInt(id), new ThreadStat(stat.substring(open + 1, close), ticksUsed * 1000 / tick));
            } catch (Throwable ignore) {
            }
        }
        return result;
    }

    private static void record(Snapshot now, Snapshot before, int x10) {
        int at = (historyHead + historyCount) % HISTORY;
        if (historyCount == HISTORY) {
            historyHead = (historyHead + 1) % HISTORY;
        } else {
            historyCount++;
        }
        historyTime[at] = now.wall;
        historyState[at] = now.state;
        historyCpuX10[at] = x10;
        historyRx[at] = now.rx < 0 || before.rx < 0 ? -1 : now.rx - before.rx;
        historyTx[at] = now.tx < 0 || before.tx < 0 ? -1 : now.tx - before.tx;
    }

    private static String bytes(long value) {
        if (value < 0) {
            return "?";
        }
        return value < 1024 * 1024 ? String.format(Locale.US, "%.1f КБ", value / 1024.0) : String.format(Locale.US, "%.1f МБ", value / 1048576.0);
    }

    private static String normalize(String name) {
        return name.replaceAll("\\d+", "#");
    }

    // endregion

    // region report

    private static File write(Snapshot start, Snapshot end, String description) {
        try {
            long wall = end.realtime - start.realtime;
            long cpu = end.cpuMs - start.cpuMs;
            StringBuilder sb = new StringBuilder(32 * 1024);
            sb.append("rawGram load report\n");
            sb.append("type: ").append(TYPE_LOAD).append('\n');
            RawCrashLog.appendTime(sb, end.wall);
            sb.append("exception: ").append(description).append('\n');
            sb.append("pid: ").append(Process.myPid()).append('\n');
            RawCrashLog.appendShortDevice(sb);

            RawCrashLog.appendSection(sb, "load", "Нагрузка");
            sb.append("окно: ").append(wall / 1000).append(" с, процессор ").append(cpu).append(" мс (")
                    .append(String.format(Locale.US, "%.1f", cpu * 100.0 / Math.max(1, wall))).append("% одного ядра)\n");
            sb.append("состояние: в начале ").append(STATE_NAMES[start.state]).append(", в конце ").append(STATE_NAMES[end.state]).append('\n');
            sb.append("сеть приложения: принято ").append(bytes(end.rx < 0 || start.rx < 0 ? -1 : end.rx - start.rx))
                    .append(", отправлено ").append(bytes(end.tx < 0 || start.tx < 0 ? -1 : end.tx - start.tx)).append('\n');
            appendBattery(sb);
            appendKeepAlive(sb);
            sb.append("потоков в процессе: ").append(end.threads.size()).append('\n');

            RawCrashLog.appendSection(sb, "load_threads", "Потоки по процессору");
            ArrayList<Map.Entry<String, long[]>> top = topThreads(start.threads, end.threads);
            for (int i = 0; i < top.size() && i < TOP_THREADS; i++) {
                long[] v = top.get(i).getValue();
                sb.append(String.format(Locale.US, "%7d мс  %5.1f%%  %s", v[0], v[0] * 100.0 / Math.max(1, wall), top.get(i).getKey()));
                if (v[1] > 1) {
                    sb.append("  (потоков: ").append(v[1]).append(')');
                }
                sb.append('\n');
            }
            if (top.isEmpty()) {
                sb.append("(потоки не прочитаны)\n");
            }

            RawCrashLog.appendSection(sb, RawCrashLog.SEC_THREADS, "Стеки самых занятых потоков");
            appendStacks(sb, top);

            RawCrashLog.appendSection(sb, "load_history", "Последние замеры");
            java.text.SimpleDateFormat time = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US);
            for (int i = 0; i < historyCount; i++) {
                int at = (historyHead + i) % HISTORY;
                sb.append(time.format(new java.util.Date(historyTime[at]))).append("  ").append(String.format(Locale.US, "%-15s", STATE_NAMES[historyState[at]]))
                        .append(String.format(Locale.US, " CPU %4.1f%%", historyCpuX10[at] / 10.0))
                        .append("  принято ").append(bytes(historyRx[at])).append(", отправлено ").append(bytes(historyTx[at])).append('\n');
            }
            if (historyCount == 0) {
                sb.append("(замеров ещё нет)\n");
            }

            File report = RawCrashLog.writeReport(TYPE_LOAD, end.wall, sb);
            lastReport = report;
            lastLine = description;
            return report;
        } catch (Throwable e) {
            return null;
        }
    }

    private static ArrayList<Map.Entry<String, long[]>> topThreads(HashMap<Integer, ThreadStat> from, HashMap<Integer, ThreadStat> to) {
        HashMap<String, long[]> byName = new HashMap<>();
        for (Map.Entry<Integer, ThreadStat> e : to.entrySet()) {
            ThreadStat before = from.get(e.getKey());
            long delta = e.getValue().ms - (before != null && before.name.equals(e.getValue().name) ? before.ms : 0);
            if (delta <= 0) {
                continue;
            }
            String name = normalize(e.getValue().name);
            long[] sum = byName.get(name);
            if (sum == null) {
                byName.put(name, sum = new long[2]);
            }
            sum[0] += delta;
            sum[1]++;
        }
        ArrayList<Map.Entry<String, long[]>> list = new ArrayList<>(byName.entrySet());
        Collections.sort(list, (a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        return list;
    }

    /** Java threads whose normalized name is among the busiest: the current stack, one sample. Native threads have none. */
    private static void appendStacks(StringBuilder sb, ArrayList<Map.Entry<String, long[]>> top) {
        int shown = 0;
        try {
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            for (int i = 0; i < top.size() && i < STACK_THREADS; i++) {
                String name = top.get(i).getKey();
                for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                    if (!normalize(e.getKey().getName()).equals(name)) {
                        continue;
                    }
                    StackTraceElement[] stack = e.getValue();
                    RawCrashLog.appendItem(sb, "\"" + e.getKey().getName() + "\" " + e.getKey().getState());
                    for (int j = 0; j < stack.length && j < 25; j++) {
                        sb.append("  at ").append(stack[j]).append('\n');
                    }
                    shown++;
                    break;
                }
            }
        } catch (Throwable e) {
            sb.append("не удалось снять стеки: ").append(e).append('\n');
        }
        if (shown == 0) {
            sb.append("(у самых занятых потоков нет Java-стека: это системные или нативные потоки)\n");
        }
    }

    private static void appendBattery(StringBuilder sb) {
        try {
            Intent battery = ApplicationLoader.applicationContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                sb.append("батарея: ").append(level * 100 / Math.max(1, scale)).append("%, ").append(plugged != 0 ? "на зарядке" : "от батареи").append('\n');
            }
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                sb.append("экономия энергии: ").append(pm.isPowerSaveMode() ? "включена" : "выключена");
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    sb.append(", приложение вне ограничений батареи: ").append(pm.isIgnoringBatteryOptimizations(ApplicationLoader.applicationContext.getPackageName()));
                }
                sb.append('\n');
            }
        } catch (Throwable ignore) {
        }
    }

    private static void appendKeepAlive(StringBuilder sb) {
        sb.append("служба поддержания связи: ").append(RawPushDiag.serviceRunning ? "запущена" : "не запущена")
                .append(RawPushDiag.serviceForeground ? ", в режиме переднего плана" : "");
        if (RawPushDiag.serviceError != null) {
            sb.append(", ошибка: ").append(RawPushDiag.serviceError);
        }
        sb.append('\n');
    }

    // endregion

    /** Self-check of the debug menu: the thread table is readable and parses. */
    static String selfCheck() {
        HashMap<Integer, ThreadStat> threads = readThreads();
        if (threads.isEmpty()) {
            throw new IllegalStateException("/proc/self/task не читается");
        }
        long total = 0;
        for (ThreadStat t : threads.values()) {
            total += t.ms;
        }
        return "потоков " + threads.size() + ", CPU процесса " + Process.getElapsedCpuTime() + " мс (по потокам " + total + " мс)"
                + (RawgramConfig.isLoadMonitor() ? ", сэмплер " + (sampler != null ? "работает" : "не запущен") : ", выключен в настройках");
    }
}
