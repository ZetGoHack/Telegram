package org.telegram.rawgram;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.ApplicationLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Crashes the Java handler never sees: native crashes (SIGSEGV etc.), ANRs and system kills, imported from
 * ActivityManager.getHistoricalProcessExitReasons (Android 11+) into regular rawGram reports.
 * Native traces are binary tombstone protos ({@link RawTombstone}), ANR traces are filtered text.
 */
public class RawExitReports {

    private static final String KEY_IMPORTED = "exits_imported";
    // first run: don't import ancient history
    private static final long FIRST_RUN_WINDOW = 3L * 24 * 60 * 60 * 1000;
    private static final int TRACE_READ_CAP = 8 * 1024 * 1024;
    private static final int STACK_BYTES = 16 * 1024;
    private static final int THREADS_BYTES = 20 * 1024;
    private static final int LOG_BYTES = 16 * 1024;
    private static final int TOP_FRAMES = 6;

    private static final Pattern TID = Pattern.compile("\\btid=(\\d+)");
    private static final Pattern HELD_BY = Pattern.compile("held by (?:thread|tid=)\\s*(\\d+)");

    /** Decoded system trace of one exit. */
    static class Decoded {
        String exception, at, thread;
        final StringBuilder stack = new StringBuilder();
        final StringBuilder threads = new StringBuilder();
        final StringBuilder device = new StringBuilder();
        String logcat;
        final ArrayList<String> topFrames = new ArrayList<>();
    }

    // region import

    /** Imports new crash-like exits as report files. Blocking, call off the UI thread. Returns the number written. */
    static synchronized int importNow() {
        if (Build.VERSION.SDK_INT < 30 || ApplicationLoader.applicationContext == null) {
            return 0;
        }
        try {
            return importExits();
        } catch (Throwable e) {
            return 0;
        }
    }

    @RequiresApi(30)
    private static int importExits() {
        SharedPreferences prefs = RawCrashLog.sharedPrefs();
        long last = prefs.getLong(KEY_IMPORTED, 0);
        if (last == 0) {
            last = System.currentTimeMillis() - FIRST_RUN_WINDOW;
        }
        ActivityManager am = (ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE);
        List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 0);
        if (exits == null) {
            return 0;
        }
        long newest = last;
        int imported = 0;
        ArrayList<RawCrashLog.Report> ours = null;
        for (ApplicationExitInfo info : exits) {
            long ts = info.getTimestamp();
            if (ts <= last) {
                continue;
            }
            newest = Math.max(newest, ts);
            String type = typeFor(info.getReason());
            if (type == null) {
                continue;
            }
            if (info.getReason() == ApplicationExitInfo.REASON_CRASH) {
                if (ours == null) {
                    ours = RawCrashLog.list();
                }
                if (handledByUs(ours, info)) {
                    continue;
                }
            }
            if (RawCrashLog.writeReport(type, ts, buildReport(info, type)) != null) {
                imported++;
            }
        }
        if (newest > prefs.getLong(KEY_IMPORTED, 0)) {
            prefs.edit().putLong(KEY_IMPORTED, newest).apply();
        }
        return imported;
    }

    private static String typeFor(int reason) {
        switch (reason) {
            case 4: // REASON_CRASH
                return RawCrashLog.TYPE_CRASH;
            case 5: // REASON_CRASH_NATIVE
                return RawCrashLog.TYPE_NATIVE;
            case 6: // REASON_ANR
                return RawCrashLog.TYPE_ANR;
            case 9: // REASON_EXCESSIVE_RESOURCE_USAGE
                return RawCrashLog.TYPE_KILLED;
            default:
                return null;
        }
    }

    /** Java crash already written by our handler: same pid, close in time. */
    @RequiresApi(30)
    private static boolean handledByUs(ArrayList<RawCrashLog.Report> ours, ApplicationExitInfo info) {
        for (RawCrashLog.Report r : ours) {
            if (!RawCrashLog.TYPE_CRASH.equals(r.type)) {
                continue;
            }
            long dt = Math.abs(r.time - info.getTimestamp());
            if (r.pid == info.getPid() && dt < 120_000 || r.pid == 0 && dt < 10_000) {
                return true;
            }
        }
        return false;
    }

    @RequiresApi(30)
    private static String buildReport(ApplicationExitInfo info, String type) {
        Decoded d = decode(info);
        String reasonName = RawCrashLog.exitReasonName(info.getReason());
        String desc = info.getDescription();
        StringBuilder sb = new StringBuilder(32 * 1024);
        sb.append("rawGram ").append(RawCrashLog.typeLabel(type)).append(" report (из системной истории завершений)\n");
        sb.append("type: ").append(type).append('\n');
        RawCrashLog.appendTime(sb, info.getTimestamp());
        String exception = d != null && d.exception != null ? d.exception : reasonName + (desc != null ? ": " + desc : "");
        sb.append("exception: ").append(RawCrashLog.oneLine(exception, 300)).append('\n');
        if (d != null && d.at != null) {
            sb.append("at: ").append(RawCrashLog.oneLine(d.at, 300)).append('\n');
        }
        if (d != null && d.thread != null) {
            sb.append("thread: ").append(RawCrashLog.oneLine(d.thread, 200)).append('\n');
        }
        sb.append("pid: ").append(info.getPid()).append('\n');
        sb.append("process: ").append(info.getProcessName()).append('\n');
        sb.append("source: ApplicationExitInfo ").append(reasonName).append('\n');
        RawCrashLog.appendShortDevice(sb);

        RawCrashLog.appendSection(sb, RawCrashLog.SEC_STACK, "Стек");
        if (d != null && d.stack.length() > 0) {
            RawCrashLog.appendBody(sb, d.stack.toString(), STACK_BYTES);
        } else {
            sb.append("(система не сохранила трассу для этого завершения)\n");
            if (desc != null) {
                sb.append(desc).append('\n');
            }
        }
        if (d != null && d.threads.length() > 0) {
            RawCrashLog.appendSection(sb, RawCrashLog.SEC_THREADS, "Другие потоки");
            RawCrashLog.appendBody(sb, d.threads.toString(), THREADS_BYTES + 2048, true);
        }

        RawCrashLog.appendSection(sb, RawCrashLog.SEC_DEVICE, "Устройство и диагностика");
        appendExitFields(sb, info);
        if (d != null && d.device.length() > 0) {
            sb.append('\n');
            RawCrashLog.appendBody(sb, d.device.toString(), 4096);
        }
        sb.append("\n(ниже — текущая версия и устройство, на момент импорта)\n");
        RawCrashLog.appendDeviceInfo(sb, false);

        if (d != null && d.logcat != null && !d.logcat.isEmpty()) {
            RawCrashLog.appendSection(sb, RawCrashLog.SEC_LOGCAT, "logcat");
            sb.append("(журнал процесса из tombstone, на момент падения)\n");
            RawCrashLog.appendBody(sb, d.logcat, LOG_BYTES + 1024);
        }
        if (sb.length() > RawCrashLog.CRASH_CAP) {
            sb.setLength(RawCrashLog.CRASH_CAP);
            sb.append("\n… (отчёт обрезан до ").append(RawCrashLog.CRASH_CAP / 1024).append(" КБ)\n");
        }
        return sb.toString();
    }

    @RequiresApi(30)
    private static void appendExitFields(StringBuilder sb, ApplicationExitInfo info) {
        sb.append("exit reason: ").append(RawCrashLog.exitReasonName(info.getReason()))
                .append(", status ").append(info.getStatus())
                .append(", importance ").append(info.getImportance()).append('\n');
        sb.append("pss: ").append(info.getPss() / 1024).append(" MB, rss: ").append(info.getRss() / 1024).append(" MB\n");
        if (info.getDescription() != null) {
            sb.append("description: ").append(RawCrashLog.oneLine(info.getDescription(), 500)).append('\n');
        }
    }

    // endregion

    // region decoding

    @RequiresApi(30)
    static Decoded decode(ApplicationExitInfo info) {
        int reason = info.getReason();
        if (reason != ApplicationExitInfo.REASON_CRASH_NATIVE && reason != ApplicationExitInfo.REASON_ANR) {
            return null;
        }
        byte[] data;
        try (InputStream in = info.getTraceInputStream()) {
            if (in == null) {
                return null;
            }
            data = readAll(in, TRACE_READ_CAP);
        } catch (Throwable e) {
            return null;
        }
        if (data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
                data = readAll(in, TRACE_READ_CAP);
            } catch (Throwable ignore) {
            }
        }
        try {
            if (reason == ApplicationExitInfo.REASON_ANR) {
                return decodeAnr(new String(data, StandardCharsets.UTF_8), info.getDescription());
            }
            return decodeNative(data);
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in, int cap) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(65536);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            int room = cap - out.size();
            if (room <= 0) {
                break;
            }
            out.write(buf, 0, Math.min(n, room));
        }
        return out.toByteArray();
    }

    static Decoded decodeNative(byte[] data) {
        Decoded d = new Decoded();
        if (looksLikeText(data)) {
            // some ROMs hand out the old text tombstone
            String text = new String(data, 0, Math.min(data.length, STACK_BYTES * 2), StandardCharsets.UTF_8);
            d.stack.append(text);
            for (String line : text.split("\n")) {
                if (d.exception == null && line.startsWith("signal ")) {
                    d.exception = RawCrashLog.oneLine(line, 300);
                } else if (line.trim().startsWith("#") && d.topFrames.size() < TOP_FRAMES) {
                    d.topFrames.add(line.trim());
                }
            }
            return d;
        }
        RawTombstone t = RawTombstone.decode(data, data.length);
        d.exception = t.signalSummary();
        d.at = t.firstFrame();
        d.thread = t.threadLabel();
        t.renderStack(d.stack, 64);
        t.renderThreads(d.threads, 40, THREADS_BYTES);
        t.renderDevice(d.device);
        if (!t.logs.isEmpty()) {
            d.logcat = RawCrashLog.compactLog(t.logLines(), 150, LOG_BYTES);
        }
        RawTombstone.TThread crashed = t.crashingThread();
        if (crashed != null) {
            for (int i = 0; i < crashed.frames.size() && i < TOP_FRAMES; i++) {
                d.topFrames.add(t.frameLine(i, crashed.frames.get(i)).trim());
            }
        }
        return d;
    }

    private static boolean looksLikeText(byte[] data) {
        int n = Math.min(data.length, 512), printable = 0;
        if (n == 0) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            int c = data[i] & 0xff;
            if (c == '\n' || c == '\t' || c >= 0x20 && c < 0x7f) {
                printable++;
            }
        }
        return printable > n * 0.95 && new String(data, 0, n, StandardCharsets.US_ASCII).contains("***");
    }

    /**
     * ANR trace ("DALVIK THREADS" dump): keeps the "main" thread, threads with org.telegram frames and the
     * threads holding locks main waits for. Everything else is counted and dropped.
     */
    static Decoded decodeAnr(String text, String description) {
        Decoded d = new Decoded();
        String[] lines = text.split("\n");
        ArrayList<ArrayList<String>> blocks = new ArrayList<>();
        ArrayList<String> cur = null;
        boolean inThreads = false;
        String subject = null;
        for (String line : lines) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (!inThreads) {
                if (line.startsWith("DALVIK THREADS")) {
                    inThreads = true;
                } else if (line.startsWith("----- pid") || line.startsWith("Cmd line:") || line.startsWith("Build fingerprint:")
                        || line.startsWith("ABI:") || line.startsWith("Build type:")) {
                    d.device.append(line).append('\n');
                } else if (line.startsWith("Subject:")) {
                    subject = line.substring(8).trim();
                    d.device.append(line).append('\n');
                }
                continue;
            }
            if (line.startsWith("----- end") || line.startsWith("----- Waiting Channels")) {
                break;
            }
            if (line.startsWith("\"")) {
                cur = new ArrayList<>();
                blocks.add(cur);
                cur.add(line);
            } else if (line.trim().isEmpty()) {
                cur = null;
            } else if (cur != null) {
                cur.add(line);
            }
        }
        ArrayList<String> main = null;
        for (ArrayList<String> b : blocks) {
            if (b.get(0).startsWith("\"main\"")) {
                main = b;
                break;
            }
        }
        HashSet<String> wanted = new HashSet<>();
        if (main != null) {
            collectHeldBy(main, wanted);
        }
        String why = subject != null ? subject : description;
        d.exception = "ANR" + (why != null ? ": " + RawCrashLog.oneLine(why, 260) : "");
        d.thread = "main";
        if (main != null) {
            int n = 0;
            for (String l : main) {
                if (n++ >= 120) {
                    d.stack.append("  … ещё ").append(main.size() - 120).append(" строк\n");
                    break;
                }
                d.stack.append(l).append('\n');
                String t = l.trim();
                if (t.startsWith("at ")) {
                    if (d.topFrames.size() < TOP_FRAMES) {
                        d.topFrames.add(t);
                    }
                    if (d.at == null && t.startsWith("at org.telegram.")) {
                        d.at = t.substring(3);
                    }
                }
            }
            if (d.at == null && !d.topFrames.isEmpty()) {
                d.at = d.topFrames.get(0).substring(3);
            }
        } else {
            d.stack.append("(поток main не найден в трассе)\n");
        }
        int shown = 0, skipped = 0;
        for (ArrayList<String> b : blocks) {
            if (b == main) {
                continue;
            }
            boolean keep = false;
            Matcher m = TID.matcher(b.get(0));
            if (m.find() && wanted.contains(m.group(1))) {
                keep = true;
            } else {
                for (String l : b) {
                    if (l.contains("org.telegram.")) {
                        keep = true;
                        break;
                    }
                }
            }
            if (!keep || d.threads.length() > THREADS_BYTES) {
                skipped++;
                continue;
            }
            shown++;
            RawCrashLog.appendItem(d.threads, b.get(0) + " (" + (b.size() - 1) + " строк)");
            for (int i = 1; i < b.size() && i <= 60; i++) {
                d.threads.append(b.get(i)).append('\n');
            }
            if (b.size() > 61) {
                d.threads.append("  … ещё ").append(b.size() - 61).append(" строк\n");
            }
        }
        if (shown == 0) {
            d.threads.append("(других потоков с кадрами org.telegram нет)\n");
        }
        if (skipped > 0) {
            d.threads.append("(пропущено потоков без кадров org.telegram: ").append(skipped).append(")\n");
        }
        return d;
    }

    private static void collectHeldBy(ArrayList<String> block, HashSet<String> out) {
        for (String l : block) {
            Matcher m = HELD_BY.matcher(l);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
    }

    // endregion

    // region history (snapshots)

    /** One item per exit with a short decoded summary for native crashes / ANRs. */
    static void renderHistory(StringBuilder sb, int maxBytes) {
        if (Build.VERSION.SDK_INT < 30) {
            sb.append("недоступно: нужен Android 11+\n");
            return;
        }
        try {
            renderHistory30(sb, maxBytes);
        } catch (Throwable e) {
            sb.append("ошибка: ").append(e).append('\n');
        }
    }

    @RequiresApi(30)
    private static void renderHistory30(StringBuilder sb, int maxBytes) {
        ActivityManager am = (ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE);
        List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 20);
        if (exits == null || exits.isEmpty()) {
            sb.append("записей нет\n");
            return;
        }
        String pkg = ApplicationLoader.applicationContext.getPackageName();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        File reports = RawCrashLog.reportsDir();
        int start = sb.length(), decoded = 0, i = 0;
        for (ApplicationExitInfo info : exits) {
            if (sb.length() - start > maxBytes) {
                sb.append("(ещё записей: ").append(exits.size() - i).append(")\n");
                break;
            }
            i++;
            String process = info.getProcessName();
            RawCrashLog.appendItem(sb, fmt.format(new Date(info.getTimestamp())) + "  " + RawCrashLog.exitReasonName(info.getReason())
                    + "  pid " + info.getPid() + (process != null && !process.equals(pkg) ? "  " + process : ""));
            appendExitFields(sb, info);
            int reason = info.getReason();
            if ((reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_ANR) && decoded < 5) {
                Decoded d = decode(info);
                if (d != null) {
                    decoded++;
                    if (d.exception != null) {
                        sb.append("exception: ").append(RawCrashLog.oneLine(d.exception, 300)).append('\n');
                    }
                    if (d.thread != null) {
                        sb.append("thread: ").append(d.thread).append('\n');
                    }
                    if (d.at != null) {
                        sb.append("at: ").append(RawCrashLog.oneLine(d.at, 300)).append('\n');
                    }
                    for (String f : d.topFrames) {
                        sb.append("  ").append(RawCrashLog.oneLine(f, 300)).append('\n');
                    }
                } else {
                    sb.append("(трасса недоступна)\n");
                }
            }
            String type = typeFor(reason);
            if (type != null && reports != null) {
                File f = new File(reports, type + "_" + info.getTimestamp() + ".txt");
                if (f.exists()) {
                    sb.append("отчёт: ").append(f.getName()).append('\n');
                }
            }
        }
    }

    // endregion
}
