package org.telegram.rawgram;

import android.app.ActivityManager;
import android.app.Application;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * rawGram crash log: an uncaught exception handler that synchronously writes a text report to
 * filesDir/rawgram_crashes, import of native crashes / ANRs from the system exit history
 * ({@link RawExitReports}), a "crashed last time" bulletin on the next launch and on-demand logcat snapshots.
 *
 * Report format: header of "key: value" lines (the summary), a blank line, then sections:
 * "=== section: id | Title ===" followed by lines and optional "--- item: title ---" sub-blocks
 * (threads, exits). Content lines that look like markers are prefixed with a space.
 */
public class RawCrashLog {

    public static final int KEEP = 30;
    static final String DIR = "rawgram_crashes";
    static final String TYPE_CRASH = "crash";
    static final String TYPE_NATIVE = "native";
    static final String TYPE_ANR = "anr";
    static final String TYPE_KILLED = "killed";
    static final String TYPE_LOGCAT = "logcat";

    static final String SEC_SUMMARY = "summary";
    static final String SEC_STACK = "stack";
    static final String SEC_THREADS = "threads";
    static final String SEC_LOGCAT = "logcat";
    static final String SEC_EXITS = "exits";
    static final String SEC_DEVICE = "device";

    static final String SECTION_PREFIX = "=== section: ";
    static final String ITEM_PREFIX = "--- item: ";

    private static final String PREFS = "rawgram_crashlog";
    private static final String KEY_SEEN = "seen";

    // size budgets: crash reports <= ~64 KB, snapshots <= ~128 KB
    static final int CRASH_CAP = 64 * 1024;
    static final int SNAPSHOT_CAP = 128 * 1024;
    private static final int CRASH_STACK_BYTES = 20 * 1024;
    private static final int CRASH_THREADS_BYTES = 10 * 1024;
    private static final int CRASH_LOGCAT_BYTES = 28 * 1024;
    private static final int LOGCAT_TAIL_LINES = 250;
    private static final long CRASH_LOGCAT_TIMEOUT = 1000;
    private static final int SNAPSHOT_LOGCAT_BYTES = 80 * 1024;
    private static final int SNAPSHOT_EXITS_BYTES = 24 * 1024;
    private static final long SNAPSHOT_LOGCAT_TIMEOUT = 4000;
    // clipboard goes through binder (~1MB transaction limit, UTF-16); only small pieces are copied now
    static final int CLIPBOARD_LIMIT = 100_000;

    private static volatile File dir;
    private static volatile Context context;
    private static volatile boolean handling;
    private static boolean noticeScheduled;
    private static final long processStart = SystemClock.elapsedRealtime();

    /** Called from ApplicationLoader.onCreate. Never throws. */
    public static void install(Context ctx) {
        try {
            context = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            dir = new File(context.getFilesDir(), DIR);
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            if (previous instanceof Handler) {
                return;
            }
            Thread.setDefaultUncaughtExceptionHandler(new Handler(previous));
        } catch (Throwable ignore) {
        }
    }

    private static class Handler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler previous;

        Handler(Thread.UncaughtExceptionHandler previous) {
            this.previous = previous;
        }

        @Override
        public void uncaughtException(@NonNull Thread thread, @NonNull Throwable e) {
            // a second crash while we are writing: don't recurse, just let the system kill us
            if (!handling) {
                handling = true;
                try {
                    writeCrash(thread, e);
                } catch (Throwable ignore) {
                }
            }
            try {
                if (previous != null) {
                    previous.uncaughtException(thread, e);
                    return;
                }
            } catch (Throwable ignore) {
            }
            try {
                android.os.Process.killProcess(android.os.Process.myPid());
            } catch (Throwable ignore) {
            }
            System.exit(10);
        }
    }

    // region format helpers

    static void appendSection(StringBuilder sb, String id, String title) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        sb.append('\n').append(SECTION_PREFIX).append(id).append(" | ").append(title).append(" ===\n");
    }

    static void appendItem(StringBuilder sb, String title) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        sb.append(ITEM_PREFIX).append(oneLine(title, 300)).append(" ---\n");
    }

    /** Appends multi-line text, escaping lines that would be read as markers, cut to {@code max} chars. */
    static void appendBody(StringBuilder sb, String text, int max) {
        appendBody(sb, text, max, false);
    }

    /** @param keepItems text was built with {@link #appendItem}: keep its item markers */
    static void appendBody(StringBuilder sb, String text, int max, boolean keepItems) {
        if (text == null) {
            return;
        }
        boolean cut = false;
        if (text.length() > max) {
            text = text.substring(0, max);
            cut = true;
        }
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? text.length() : nl;
            if (text.startsWith(SECTION_PREFIX, start) || !keepItems && text.startsWith(ITEM_PREFIX, start)) {
                sb.append(' ');
            }
            sb.append(text, start, end).append('\n');
            start = end + 1;
        }
        if (cut) {
            sb.append("… (обрезано до ").append(max / 1024).append(" КБ)\n");
        }
    }

    // endregion

    // region writing

    private static void writeCrash(Thread thread, Throwable e) throws Exception {
        File d = dir;
        if (d == null) {
            return;
        }
        d.mkdirs();
        long now = System.currentTimeMillis();
        File file = new File(d, TYPE_CRASH + "_" + now + ".txt");

        Throwable root = rootCause(e);
        StringBuilder sb = new StringBuilder(16384);
        sb.append("rawGram crash report\n");
        sb.append("type: ").append(TYPE_CRASH).append('\n');
        appendTime(sb, now);
        sb.append("exception: ").append(oneLine(String.valueOf(root), 300)).append('\n');
        if (root != e) {
            sb.append("thrown: ").append(oneLine(String.valueOf(e), 300)).append('\n');
        }
        String frame = firstAppFrame(e);
        if (frame != null) {
            sb.append("at: ").append(frame).append('\n');
        }
        sb.append("thread: ").append(thread.getName()).append(" (id ").append(thread.getId()).append(")\n");
        appendProcess(sb);
        appendShortDevice(sb);

        appendSection(sb, SEC_STACK, "Стек");
        String trace = Log.getStackTraceString(e);
        if (TextUtils.isEmpty(trace)) {
            // getStackTraceString returns "" for UnknownHostException chains
            trace = String.valueOf(e);
        }
        appendBody(sb, trace, CRASH_STACK_BYTES);

        appendSection(sb, SEC_DEVICE, "Устройство и диагностика");
        appendDeviceInfo(sb, true);

        // stack trace goes to disk first: logcat below may hang or the process may die meanwhile
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
            w.flush();
        }
        try {
            StringBuilder more = new StringBuilder(CRASH_THREADS_BYTES + CRASH_LOGCAT_BYTES + 1024);
            appendSection(more, SEC_THREADS, "Другие потоки");
            appendJavaThreads(more, thread, CRASH_THREADS_BYTES);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                w.write(more.toString());
                w.flush();
            }
        } catch (Throwable ignore) {
        }
        try {
            StringBuilder more = new StringBuilder(CRASH_LOGCAT_BYTES + 1024);
            appendSection(more, SEC_LOGCAT, "logcat");
            appendBody(more, captureLogcat(LOGCAT_TAIL_LINES, CRASH_LOGCAT_BYTES, CRASH_LOGCAT_TIMEOUT), CRASH_LOGCAT_BYTES + 512);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                w.write(more.toString());
                w.flush();
            }
        } catch (Throwable ignore) {
        }
        trim();
    }

    /** "main" + threads that have org.telegram frames, one item each. */
    private static void appendJavaThreads(StringBuilder sb, Thread crashed, int maxBytes) {
        int start = sb.length();
        int skipped = 0, shown = 0;
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        for (Map.Entry<Thread, StackTraceElement[]> entry : all.entrySet()) {
            Thread t = entry.getKey();
            StackTraceElement[] stack = entry.getValue();
            if (t == crashed || stack == null) {
                continue;
            }
            boolean app = "main".equals(t.getName());
            for (StackTraceElement el : stack) {
                if (app) {
                    break;
                }
                app = el.getClassName() != null && el.getClassName().startsWith("org.telegram.");
            }
            if (!app || sb.length() - start > maxBytes) {
                skipped++;
                continue;
            }
            shown++;
            appendItem(sb, "\"" + t.getName() + "\" id=" + t.getId() + " " + t.getState() + " (" + stack.length + " кадров)");
            for (int i = 0; i < stack.length && i < 40; i++) {
                sb.append("  at ").append(stack[i]).append('\n');
            }
            if (stack.length > 40) {
                sb.append("  … ещё ").append(stack.length - 40).append('\n');
            }
        }
        if (shown == 0) {
            sb.append("(других потоков с кадрами org.telegram нет)\n");
        }
        if (skipped > 0) {
            sb.append("(пропущено потоков без кадров org.telegram: ").append(skipped).append(")\n");
        }
    }

    /** Writes a report with the current process logcat (no crash). Blocking, call off the UI thread. */
    static File captureSnapshot() {
        try {
            File d = dir;
            if (d == null) {
                return null;
            }
            d.mkdirs();
            RawExitReports.importNow();
            long now = System.currentTimeMillis();
            File file = new File(d, TYPE_LOGCAT + "_" + now + ".txt");
            StringBuilder sb = new StringBuilder(SNAPSHOT_CAP);
            sb.append("rawGram logcat snapshot\n");
            sb.append("type: ").append(TYPE_LOGCAT).append('\n');
            appendTime(sb, now);
            sb.append("exception: logcat snapshot\n");
            appendProcess(sb);
            appendShortDevice(sb);

            appendSection(sb, SEC_DEVICE, "Устройство и диагностика");
            appendDeviceInfo(sb, true);
            appendDiagnostics(sb);

            appendSection(sb, SEC_EXITS, "История завершений");
            RawExitReports.renderHistory(sb, SNAPSHOT_EXITS_BYTES);

            appendSection(sb, SEC_LOGCAT, "logcat");
            int room = Math.max(16 * 1024, Math.min(SNAPSHOT_LOGCAT_BYTES, SNAPSHOT_CAP - sb.length() - 1024));
            appendBody(sb, captureLogcat(LOGCAT_TAIL_LINES, room, SNAPSHOT_LOGCAT_TIMEOUT), room + 512);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            }
            trim();
            return file;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Writes a finished report (imported exits). */
    static File writeReport(String type, long time, CharSequence text) {
        try {
            File d = reportsDir();
            if (d == null) {
                return null;
            }
            d.mkdirs();
            File file = new File(d, type + "_" + time + ".txt");
            while (file.exists()) {
                time++;
                file = new File(d, type + "_" + time + ".txt");
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                w.write(text.toString());
            }
            trim();
            return file;
        } catch (Throwable e) {
            return null;
        }
    }

    static String exitReasonName(int reason) {
        switch (reason) {
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH (java)";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            default: return "UNKNOWN(" + reason + ")";
        }
    }

    static void appendTime(StringBuilder sb, long now) {
        sb.append("time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date(now))).append('\n');
        sb.append("timestamp: ").append(now).append('\n');
    }

    private static void appendProcess(StringBuilder sb) {
        sb.append("pid: ").append(android.os.Process.myPid()).append('\n');
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                sb.append("process: ").append(Application.getProcessName()).append('\n');
            }
        } catch (Throwable ignore) {
        }
    }

    static String appVersion() {
        Context ctx = context != null ? context : ApplicationLoader.applicationContext;
        try {
            String pkg = ctx.getPackageName();
            PackageInfo info = ctx.getPackageManager().getPackageInfo(pkg, 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return pkg + " " + info.versionName + " (versionCode " + code + ")";
        } catch (Throwable t) {
            return BuildConfig.BUILD_VERSION_STRING;
        }
    }

    /** "app" and one-line "device" for the summary. */
    static void appendShortDevice(StringBuilder sb) {
        sb.append("app: ").append(appVersion()).append('\n');
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
    }

    /** @param live include uptime/heap of the current process (not for imported reports) */
    static void appendDeviceInfo(StringBuilder sb, boolean live) {
        sb.append("app: ").append(appVersion()).append('\n');
        sb.append("build: ").append(BuildConfig.BUILD_TYPE)
                .append(BuildConfig.DEBUG_VERSION ? ", debug_version" : "")
                .append(BuildConfig.DEBUG_PRIVATE_VERSION ? ", debug_private" : "").append('\n');
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")");
        if (Build.VERSION.SDK_INT >= 23) {
            sb.append(", patch ").append(Build.VERSION.SECURITY_PATCH);
        }
        sb.append('\n');
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(", ").append(Build.PRODUCT).append(")\n");
        sb.append("abi: ").append(TextUtils.join(", ", Build.SUPPORTED_ABIS)).append('\n');
        sb.append("fingerprint: ").append(Build.FINGERPRINT).append('\n');
        if (!live) {
            return;
        }
        sb.append("uptime: ").append((SystemClock.elapsedRealtime() - processStart) / 1000).append(" s since process start\n");
        try {
            Runtime rt = Runtime.getRuntime();
            sb.append("memory: ").append((rt.totalMemory() - rt.freeMemory()) / 1024 / 1024).append(" / ")
                    .append(rt.maxMemory() / 1024 / 1024).append(" MB heap\n");
        } catch (Throwable ignore) {
        }
    }

    /** Things that matter for "notifications arrive late": power management and push state. */
    private static void appendDiagnostics(StringBuilder sb) {
        Context ctx = context;
        try {
            sb.append("notifications_enabled: ").append(NotificationManagerCompat.from(ctx).areNotificationsEnabled()).append('\n');
        } catch (Throwable ignore) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                sb.append("ignoring_battery_optimizations: ").append(pm.isIgnoringBatteryOptimizations(ctx.getPackageName())).append('\n');
                sb.append("device_idle: ").append(pm.isDeviceIdleMode()).append(", power_save: ").append(pm.isPowerSaveMode()).append('\n');
            }
        } catch (Throwable ignore) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                sb.append("background_restricted: ").append(am.isBackgroundRestricted()).append('\n');
                UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
                sb.append("standby_bucket: ").append(bucketName(usm.getAppStandbyBucket())).append('\n');
            }
        } catch (Throwable ignore) {
        }
        try {
            sb.append("push_provider: ").append(ApplicationLoader.getPushProvider().getLogTitle())
                    .append(", has_services: ").append(ApplicationLoader.getPushProvider().hasServices()).append('\n');
            // the token itself is not written: reports get shared
            sb.append("push_type: ").append(SharedConfig.pushType)
                    .append(", token: ").append(TextUtils.isEmpty(SharedConfig.pushString) ? "none" : "set (" + SharedConfig.pushString.length() + " chars)")
                    .append(", status: ").append(TextUtils.isEmpty(SharedConfig.pushStringStatus) ? "-" : SharedConfig.pushStringStatus).append('\n');
        } catch (Throwable ignore) {
        }
    }

    private static String bucketName(int bucket) {
        switch (bucket) {
            case UsageStatsManager.STANDBY_BUCKET_ACTIVE:
                return "active (" + bucket + ")";
            case UsageStatsManager.STANDBY_BUCKET_WORKING_SET:
                return "working_set (" + bucket + ")";
            case UsageStatsManager.STANDBY_BUCKET_FREQUENT:
                return "frequent (" + bucket + ")";
            case UsageStatsManager.STANDBY_BUCKET_RARE:
                return "rare (" + bucket + ")";
            case 45: // STANDBY_BUCKET_RESTRICTED, API 30
                return "restricted (" + bucket + ")";
            default:
                return String.valueOf(bucket);
        }
    }

    // endregion

    // region logcat

    /** threadtime: "10-01 18:41:03.123  1234  1250 E Tag: msg" */
    static final Pattern LOG_LINE = Pattern.compile("^\\S+\\s+\\S+\\s+\\d+\\s+\\d+\\s+([VDIWEFA])\\s");

    static char logLevel(String line) {
        Matcher m = LOG_LINE.matcher(line);
        return m.find() ? m.group(1).charAt(0) : 0;
    }

    /**
     * Reads this process' logcat within {@code timeoutMs} and compacts it: the last {@code tailLines} lines at all
     * levels plus earlier E/F lines from the same capture (deduplicated), unreadable lines dropped. Never throws.
     */
    static String captureLogcat(int tailLines, int maxBytes, long timeoutMs) {
        java.lang.Process process = null;
        try {
            ArrayList<String> cmd = new ArrayList<>(Arrays.asList("logcat", "-d", "-v", "threadtime", "-t", String.valueOf(tailLines * 6)));
            if (Build.VERSION.SDK_INT >= 24) {
                cmd.add("--pid=" + android.os.Process.myPid());
            }
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final InputStream in = process.getInputStream();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(65536);
            final int hardCap = 3 * 1024 * 1024;
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[8192];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        synchronized (out) {
                            if (out.size() + n > hardCap) {
                                break;
                            }
                            out.write(buf, 0, n);
                        }
                    }
                } catch (Throwable ignore) {
                }
            }, "rawgram-logcat");
            reader.setDaemon(true);
            reader.start();
            reader.join(timeoutMs);
            boolean timedOut = reader.isAlive();
            byte[] bytes;
            synchronized (out) {
                bytes = out.toByteArray();
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            String result = compactLog(Arrays.asList(text.split("\n")), tailLines, maxBytes);
            if (timedOut) {
                result += "(logcat не уложился в " + timeoutMs + " мс)\n";
            }
            return result.isEmpty() ? "(logcat пуст)\n" : result;
        } catch (Throwable t) {
            return "(logcat недоступен: " + t + ")\n";
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    static String compactLog(List<String> raw, int tailLines, int maxBytes) {
        ArrayList<String> lines = new ArrayList<>(raw.size());
        int garbage = 0;
        for (String l : raw) {
            if (l.startsWith("--------- beginning of")) {
                continue;
            }
            String c = cleanLine(l);
            if (c == null) {
                garbage++;
            } else if (!c.trim().isEmpty()) {
                lines.add(c);
            }
        }
        int tailStart = Math.max(0, lines.size() - tailLines);

        // earlier errors, deduplicated by "tag: message"
        LinkedHashMap<String, String> firstByKey = new LinkedHashMap<>();
        HashMap<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < tailStart; i++) {
            String l = lines.get(i);
            Matcher m = LOG_LINE.matcher(l);
            if (!m.find()) {
                continue;
            }
            char lv = m.group(1).charAt(0);
            if (lv != 'E' && lv != 'F' && lv != 'A') {
                continue;
            }
            String key = l.substring(m.end());
            Integer c = counts.get(key);
            if (c == null) {
                firstByKey.put(key, l);
                counts.put(key, 1);
            } else {
                counts.put(key, c + 1);
            }
        }
        ArrayList<String> errors = new ArrayList<>(firstByKey.size());
        for (Map.Entry<String, String> e : firstByKey.entrySet()) {
            int c = counts.get(e.getKey());
            errors.add(c > 1 ? e.getValue() + "  (×" + c + ")" : e.getValue());
        }
        StringBuilder sb = new StringBuilder(Math.min(maxBytes, 256 * 1024) + 256);
        if (!errors.isEmpty()) {
            int budget = maxBytes / 3, used = 0, start = errors.size();
            while (start > 0 && used + errors.get(start - 1).length() + 1 <= budget) {
                used += errors.get(start - 1).length() + 1;
                start--;
            }
            sb.append("··· ранее: ошибки E/F без повторов (").append(errors.size() - start).append(" из ").append(errors.size()).append(") ···\n");
            for (int i = start; i < errors.size(); i++) {
                sb.append(errors.get(i)).append('\n');
            }
            sb.append("··· последние ").append(lines.size() - tailStart).append(" строк ···\n");
        }
        int budget = maxBytes - sb.length(), used = 0, from = lines.size();
        while (from > tailStart && used + lines.get(from - 1).length() + 1 <= budget) {
            used += lines.get(from - 1).length() + 1;
            from--;
        }
        if (from > tailStart) {
            sb.append("(не влезло старых строк: ").append(from - tailStart).append(")\n");
        }
        for (int i = from; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        if (garbage > 0) {
            sb.append("(пропущено нечитаемых строк: ").append(garbage).append(")\n");
        }
        return sb.toString();
    }

    /** Strips control characters; null when the line is mostly binary garbage. */
    static String cleanLine(String l) {
        int bad = 0, len = l.length();
        for (int i = 0; i < len; i++) {
            char c = l.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\r' || c == 0xfffd || c >= 0x7f && c < 0xa0) {
                bad++;
            }
        }
        if (bad > Math.max(2, len / 10)) {
            return null;
        }
        StringBuilder sb = null;
        if (bad > 0 || len > 0 && l.charAt(len - 1) == '\r') {
            sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                char c = l.charAt(i);
                if (c < 0x20 && c != '\t' || c == 0xfffd || c >= 0x7f && c < 0xa0) {
                    continue;
                }
                sb.append(c);
            }
        }
        String s = sb != null ? sb.toString() : l;
        return s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }

    // endregion

    // region misc

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        int guard = 0;
        while (t.getCause() != null && t.getCause() != t && guard++ < 20) {
            t = t.getCause();
        }
        return t;
    }

    /** First org.telegram frame, preferring the root cause (wrappers like "Unable to start activity" point into the framework). */
    private static String firstAppFrame(Throwable e) {
        ArrayList<Throwable> chain = new ArrayList<>();
        Throwable t = e;
        while (t != null && chain.size() < 20 && !chain.contains(t)) {
            chain.add(t);
            t = t.getCause();
        }
        Collections.reverse(chain);
        for (Throwable c : chain) {
            StackTraceElement[] stack = c.getStackTrace();
            if (stack == null) {
                continue;
            }
            for (StackTraceElement el : stack) {
                if (el != null && el.getClassName() != null && el.getClassName().startsWith("org.telegram.")) {
                    return el.toString();
                }
            }
        }
        return null;
    }

    static String oneLine(String s, int max) {
        if (s == null) {
            return "null";
        }
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static void trim() {
        ArrayList<File> files = listFiles();
        for (int i = KEEP; i < files.size(); i++) {
            try {
                files.get(i).delete();
            } catch (Throwable ignore) {
            }
        }
    }

    static File reportsDir() {
        File d = dir;
        if (d == null && ApplicationLoader.applicationContext != null) {
            d = new File(ApplicationLoader.applicationContext.getFilesDir(), DIR);
        }
        return d;
    }

    // endregion

    // region reading

    static class Report {
        final File file;
        final long time;
        String type = TYPE_CRASH;
        String exception;
        String at;
        int pid;

        Report(File file, long time) {
            this.file = file;
            this.time = time;
        }

        /** Anything that ended the process abnormally (shown in red, triggers the next-launch notice). */
        boolean isCrash() {
            return isCrashType(type);
        }
    }

    static boolean isCrashType(String type) {
        return TYPE_CRASH.equals(type) || TYPE_NATIVE.equals(type) || TYPE_ANR.equals(type) || TYPE_KILLED.equals(type);
    }

    static boolean isCrashFile(File f) {
        String n = f.getName();
        return n.startsWith(TYPE_CRASH + "_") || n.startsWith(TYPE_NATIVE + "_") || n.startsWith(TYPE_ANR + "_") || n.startsWith(TYPE_KILLED + "_");
    }

    static String typeLabel(String type) {
        if (TYPE_NATIVE.equals(type)) {
            return "native";
        } else if (TYPE_ANR.equals(type)) {
            return "ANR";
        } else if (TYPE_KILLED.equals(type)) {
            return "убит системой";
        } else if (TYPE_LOGCAT.equals(type)) {
            return "logcat";
        }
        return "java";
    }

    static long parseTime(File f) {
        String name = f.getName();
        int us = name.lastIndexOf('_');
        int dot = name.lastIndexOf('.');
        if (us < 0 || dot <= us) {
            return 0;
        }
        try {
            return Long.parseLong(name.substring(us + 1, dot));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Report files, newest first. */
    static ArrayList<File> listFiles() {
        ArrayList<File> result = new ArrayList<>();
        File d = reportsDir();
        File[] files = d != null ? d.listFiles() : null;
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".txt") && parseTime(f) > 0) {
                    result.add(f);
                }
            }
        }
        Collections.sort(result, (a, b) -> Long.compare(parseTime(b), parseTime(a)));
        return result;
    }

    /** Parses only the header (up to the first blank line). */
    static Report readHeader(File f) {
        Report r = new Report(f, parseTime(f));
        String name = f.getName();
        r.type = name.substring(0, Math.max(0, name.lastIndexOf('_')));
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            while ((line = br.readLine()) != null && !line.isEmpty() && count++ < 40) {
                if (line.startsWith("type: ")) {
                    r.type = line.substring(6);
                } else if (line.startsWith("exception: ")) {
                    r.exception = line.substring(11);
                } else if (line.startsWith("at: ")) {
                    r.at = line.substring(4);
                } else if (line.startsWith("pid: ")) {
                    try {
                        r.pid = Integer.parseInt(line.substring(5).trim());
                    } catch (Exception ignore) {
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return r;
    }

    static ArrayList<Report> list() {
        ArrayList<Report> reports = new ArrayList<>();
        for (File f : listFiles()) {
            reports.add(readHeader(f));
        }
        return reports;
    }

    static String read(File f, int limit) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(f.length(), limit) + 16);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                int room = limit - out.size();
                if (room <= 0) {
                    break;
                }
                out.write(buf, 0, Math.min(n, room));
            }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (f.length() > limit) {
                text += "\n… (обрезано, полный файл: " + AndroidUtilities.formatFileSize(f.length()) + ")\n";
            }
            return text;
        } catch (Throwable e) {
            return "(не удалось прочитать " + f.getName() + ": " + e + ")";
        }
    }

    static String forClipboard(String text) {
        if (text != null && text.length() > CLIPBOARD_LIMIT) {
            return text.substring(0, CLIPBOARD_LIMIT) + "\n… (обрезано для буфера обмена, используй «Поделиться»)\n";
        }
        return text;
    }

    /** "NullPointerException: msg" from "java.lang.NullPointerException: msg". */
    static String shortException(String exception) {
        if (TextUtils.isEmpty(exception)) {
            return "?";
        }
        int colon = exception.indexOf(':');
        String cls = colon >= 0 ? exception.substring(0, colon) : exception;
        if (cls.indexOf(' ') < 0) {
            int dot = cls.lastIndexOf('.');
            if (dot >= 0) {
                return exception.substring(dot + 1);
            }
        }
        return exception;
    }

    /** One line: time, kind, exception, first app frame. */
    static String summaryLine(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(r.time)));
        sb.append(" · rawGram ").append(typeLabel(r.type));
        if (!TYPE_LOGCAT.equals(r.type)) {
            sb.append(" · ").append(oneLine(shortException(r.exception), 300));
        }
        if (!TextUtils.isEmpty(r.at)) {
            sb.append(" · at ").append(oneLine(r.at, 200));
        }
        return sb.toString();
    }

    /** Structured view of a report file for the viewer. */
    static class Parsed {
        final ArrayList<String> header = new ArrayList<>();
        final ArrayList<Section> sections = new ArrayList<>();
    }

    static class Section {
        final String id;
        final String title;
        final ArrayList<String> lines = new ArrayList<>();
        final ArrayList<Item> items = new ArrayList<>();

        Section(String id, String title) {
            this.id = id;
            this.title = title;
        }

        String toText() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== ").append(title).append(" ===\n");
            for (String l : lines) {
                sb.append(l).append('\n');
            }
            for (Item item : items) {
                sb.append("--- ").append(item.title).append(" ---\n");
                for (String l : item.lines) {
                    sb.append(l).append('\n');
                }
            }
            return sb.toString();
        }
    }

    static class Item {
        final String title;
        final ArrayList<String> lines = new ArrayList<>();

        Item(String title) {
            this.title = title;
        }
    }

    static Parsed parse(File f, int limit) {
        Parsed p = new Parsed();
        String text = read(f, limit);
        String[] lines = text.split("\n", -1);
        boolean structured = text.contains("\n" + SECTION_PREFIX);
        boolean inHeader = true;
        Section section = null;
        Item item = null;
        for (String line : lines) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            String sectionId = null, sectionTitle = null;
            if (structured && line.startsWith(SECTION_PREFIX) && line.endsWith(" ===")) {
                String inner = line.substring(SECTION_PREFIX.length(), line.length() - 4);
                int bar = inner.indexOf(" | ");
                sectionId = bar >= 0 ? inner.substring(0, bar) : inner;
                sectionTitle = bar >= 0 ? inner.substring(bar + 3) : inner;
            } else if (!structured && line.startsWith("--- ") && line.endsWith(" ---") && line.length() > 8) {
                // reports written before sections existed
                sectionTitle = line.substring(4, line.length() - 4);
                sectionId = sectionTitle.startsWith("stack") ? SEC_STACK : sectionTitle.startsWith("logcat") ? SEC_LOGCAT
                        : sectionTitle.contains("exit history") ? SEC_EXITS : "legacy";
            }
            if (sectionId != null) {
                inHeader = false;
                section = new Section(sectionId, sectionTitle);
                p.sections.add(section);
                item = null;
                continue;
            }
            if (inHeader) {
                if (line.isEmpty()) {
                    inHeader = false;
                } else {
                    p.header.add(line);
                }
                continue;
            }
            if (section == null) {
                if (!line.isEmpty()) {
                    p.header.add(line);
                }
                continue;
            }
            if (line.startsWith(ITEM_PREFIX) && line.endsWith(" ---")) {
                item = new Item(line.substring(ITEM_PREFIX.length(), line.length() - 4));
                section.items.add(item);
                continue;
            }
            if (line.startsWith(" " + SECTION_PREFIX) || line.startsWith(" " + ITEM_PREFIX)) {
                line = line.substring(1);
            }
            (item != null ? item.lines : section.lines).add(line);
        }
        for (Section s : p.sections) {
            stripTrailingBlank(s.lines);
            for (Item i : s.items) {
                stripTrailingBlank(i.lines);
            }
        }
        return p;
    }

    private static void stripTrailingBlank(ArrayList<String> lines) {
        while (!lines.isEmpty() && lines.get(lines.size() - 1).trim().isEmpty()) {
            lines.remove(lines.size() - 1);
        }
    }

    // endregion

    // region UI entry points

    public static void openViewer(BaseFragment fragment) {
        if (fragment != null) {
            fragment.presentFragment(new RawCrashLogActivity());
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static SharedPreferences sharedPrefs() {
        return prefs();
    }

    static void markSeen() {
        try {
            long newest = 0;
            for (File f : listFiles()) {
                if (isCrashFile(f)) {
                    newest = Math.max(newest, parseTime(f));
                }
            }
            if (newest > prefs().getLong(KEY_SEEN, 0)) {
                prefs().edit().putLong(KEY_SEEN, newest).apply();
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * LaunchActivity.onResume: once per process, import native crashes / ANRs from the system exit history,
     * then show a bulletin if a crash happened since the last one shown.
     */
    public static void checkLastCrash() {
        if (noticeScheduled) {
            return;
        }
        noticeScheduled = true;
        final long started = SystemClock.elapsedRealtime();
        new Thread(() -> {
            try {
                RawExitReports.importNow();
            } catch (Throwable ignore) {
            }
            // let the fragment stack / passcode settle first
            long delay = Math.max(0, 1500 - (SystemClock.elapsedRealtime() - started));
            AndroidUtilities.runOnUIThread(RawCrashLog::showNoticeIfNeeded, delay);
        }, "rawgram-exit-import").start();
    }

    private static void showNoticeIfNeeded() {
        try {
            long seen = prefs().getLong(KEY_SEEN, 0);
            ArrayList<File> unseen = new ArrayList<>();
            for (File f : listFiles()) {
                if (isCrashFile(f) && parseTime(f) > seen) {
                    unseen.add(f);
                }
            }
            if (unseen.isEmpty()) {
                return;
            }
            BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            if (fragment == null || fragment.getParentActivity() == null || SharedConfig.isWaitingForPasscodeEnter) {
                noticeScheduled = false; // retry on the next resume
                return;
            }
            final File newest = unseen.get(0);
            final Report report = readHeader(newest);
            markSeen();

            Context ctx = fragment.getParentActivity();
            Theme.ResourcesProvider rp = fragment.getResourceProvider();
            Bulletin.TwoLineLayout layout = new Bulletin.TwoLineLayout(ctx, rp);
            layout.hideImage();
            String what = TYPE_ANR.equals(report.type) ? "rawGram завис (ANR)" : TYPE_KILLED.equals(report.type) ? "rawGram убит системой" : "rawGram упал в прошлый раз";
            layout.titleTextView.setText(unseen.size() > 1 ? "rawGram падал " + unseen.size() + " раз(а)" : what);
            layout.titleTextView.setEllipsize(TextUtils.TruncateAt.END);
            layout.subtitleTextView.setText((TYPE_CRASH.equals(report.type) ? "" : typeLabel(report.type) + ": ") + shortException(report.exception));
            layout.subtitleTextView.setSingleLine(true);
            layout.subtitleTextView.setEllipsize(TextUtils.TruncateAt.END);
            ActionsButton button = new ActionsButton(ctx, rp);
            button.add("Переслать", () -> {
                BaseFragment last = LaunchActivity.getSafeLastFragment();
                if (last != null) {
                    RawReportExport.forwardReport(last, newest);
                }
            });
            button.add("Открыть", () -> {
                BaseFragment last = LaunchActivity.getSafeLastFragment();
                if (last != null) {
                    last.presentFragment(new RawCrashLogActivity(newest));
                }
            });
            layout.setButton(button);
            Bulletin.make(fragment, layout, 8000).show();
        } catch (Throwable ignore) {
        }
    }

    /** Bulletin button with several text actions side by side. */
    private static class ActionsButton extends Bulletin.Button {
        private final LinearLayout row;
        private final Theme.ResourcesProvider resourcesProvider;
        private Bulletin bulletin;

        ActionsButton(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            addView(row, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        }

        void add(CharSequence text, Runnable action) {
            int color = Theme.getColor(Theme.key_undo_cancelColor, resourcesProvider);
            TextView tv = new TextView(getContext());
            tv.setBackground(Theme.createSelectorDrawable((color & 0x00ffffff) | 0x19000000, Theme.RIPPLE_MASK_ROUNDRECT_6DP));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            tv.setTypeface(AndroidUtilities.bold());
            tv.setTextColor(color);
            tv.setText(text);
            tv.setSingleLine(true);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
            tv.setOnClickListener(v -> {
                if (bulletin != null) {
                    bulletin.hide();
                }
                action.run();
            });
            row.addView(tv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        }

        @Override
        public void onAttach(@NonNull Bulletin.Layout layout, @NonNull Bulletin bulletin) {
            this.bulletin = bulletin;
        }

        @Override
        public void onDetach(@NonNull Bulletin.Layout layout) {
            this.bulletin = null;
        }
    }

    // endregion
}
