package org.telegram.rawgram;

import android.app.ActivityManager;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
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
import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/**
 * rawGram crash log: an uncaught exception handler that synchronously writes a text report
 * (device info + full stack trace + tail of this process' logcat) to filesDir/rawgram_crashes,
 * a "crashed last time" bulletin on the next launch and on-demand logcat snapshots.
 *
 * Report format: header of "key: value" lines, a blank line, then "--- section ---" blocks.
 */
public class RawCrashLog {

    public static final int KEEP = 30;
    static final String DIR = "rawgram_crashes";
    static final String TYPE_CRASH = "crash";
    static final String TYPE_LOGCAT = "logcat";

    private static final String PREFS = "rawgram_crashlog";
    private static final String KEY_SEEN = "seen";
    private static final int CRASH_LOGCAT_LINES = 300;
    private static final int CRASH_LOGCAT_BYTES = 64 * 1024;
    private static final long CRASH_LOGCAT_TIMEOUT = 1000;
    private static final int SNAPSHOT_LOGCAT_LINES = 1000;
    private static final int SNAPSHOT_LOGCAT_BYTES = 512 * 1024;
    private static final long SNAPSHOT_LOGCAT_TIMEOUT = 4000;
    // clipboard goes through binder (~1MB transaction limit, UTF-16)
    static final int CLIPBOARD_LIMIT = 250_000;

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
        StringBuilder sb = new StringBuilder(8192);
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
        appendDeviceInfo(sb);
        sb.append('\n');
        sb.append("--- stack trace ---\n");
        String trace = Log.getStackTraceString(e);
        if (TextUtils.isEmpty(trace)) {
            // getStackTraceString returns "" for UnknownHostException chains
            trace = String.valueOf(e);
        }
        sb.append(trace).append('\n');

        // stack trace goes to disk first: logcat below may hang or the process may die meanwhile
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
            w.flush();
        }
        try {
            String logcat = captureLogcat(CRASH_LOGCAT_LINES, CRASH_LOGCAT_BYTES, CRASH_LOGCAT_TIMEOUT);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                w.write("\n--- logcat (last " + CRASH_LOGCAT_LINES + " lines, pid " + android.os.Process.myPid() + ") ---\n");
                w.write(logcat);
                w.flush();
            }
        } catch (Throwable ignore) {
        }
        trim();
    }

    /** Writes a report with the current process logcat (no crash). Blocking, call off the UI thread. */
    static File captureSnapshot() {
        try {
            File d = dir;
            if (d == null) {
                return null;
            }
            d.mkdirs();
            long now = System.currentTimeMillis();
            File file = new File(d, TYPE_LOGCAT + "_" + now + ".txt");
            StringBuilder sb = new StringBuilder(16384);
            sb.append("rawGram logcat snapshot\n");
            sb.append("type: ").append(TYPE_LOGCAT).append('\n');
            appendTime(sb, now);
            sb.append("exception: logcat snapshot\n");
            appendDeviceInfo(sb);
            appendDiagnostics(sb);
            sb.append('\n');
            sb.append("--- logcat (last ").append(SNAPSHOT_LOGCAT_LINES).append(" lines, pid ").append(android.os.Process.myPid()).append(") ---\n");
            sb.append(captureLogcat(SNAPSHOT_LOGCAT_LINES, SNAPSHOT_LOGCAT_BYTES, SNAPSHOT_LOGCAT_TIMEOUT));
            appendExitHistory(sb);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            }
            trim();
            return file;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * The system's own record of how this app's processes ended (Android 11+): crashes, ANRs, native crashes,
     * low-memory and background kills — including ones from before rawGram's handler existed. ANRs and native
     * crashes (Android 12+) come with the system trace.
     */
    private static void appendExitHistory(StringBuilder sb) {
        sb.append("\n--- system exit history (ApplicationExitInfo) ---\n");
        if (Build.VERSION.SDK_INT < 30) {
            sb.append("недоступно: нужен Android 11+\n");
            return;
        }
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<android.app.ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 16);
            if (exits == null || exits.isEmpty()) {
                sb.append("записей нет\n");
                return;
            }
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            int traces = 0;
            for (android.app.ApplicationExitInfo info : exits) {
                sb.append('\n').append(fmt.format(new Date(info.getTimestamp())))
                        .append("  ").append(exitReasonName(info.getReason()))
                        .append("  process=").append(info.getProcessName())
                        .append("  pid=").append(info.getPid())
                        .append("  importance=").append(info.getImportance())
                        .append("  status=").append(info.getStatus())
                        .append("  pss=").append(info.getPss() / 1024).append("MB")
                        .append("  rss=").append(info.getRss() / 1024).append("MB\n");
                if (info.getDescription() != null) {
                    sb.append("  ").append(info.getDescription()).append('\n');
                }
                int reason = info.getReason();
                if (traces < 3 && (reason == android.app.ApplicationExitInfo.REASON_ANR || reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE)) {
                    try (java.io.InputStream in = info.getTraceInputStream()) {
                        if (in != null) {
                            traces++;
                            byte[] buf = new byte[64 * 1024];
                            int total = 0, n;
                            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) {
                                total += n;
                            }
                            sb.append("  --- trace (first ").append(total / 1024).append(" KB) ---\n")
                                    .append(new String(buf, 0, total, StandardCharsets.UTF_8)).append('\n');
                        }
                    } catch (Throwable ignore) {
                    }
                }
            }
        } catch (Throwable e) {
            sb.append("ошибка: ").append(e).append('\n');
        }
    }

    private static String exitReasonName(int reason) {
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

    private static void appendTime(StringBuilder sb, long now) {
        sb.append("time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date(now))).append('\n');
        sb.append("timestamp: ").append(now).append('\n');
    }

    private static void appendDeviceInfo(StringBuilder sb) {
        Context ctx = context;
        try {
            String pkg = ctx.getPackageName();
            PackageInfo info = ctx.getPackageManager().getPackageInfo(pkg, 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            sb.append("app: ").append(pkg).append(' ').append(info.versionName).append(" (versionCode ").append(code).append(")\n");
        } catch (Throwable t) {
            sb.append("app: ").append(BuildConfig.BUILD_VERSION_STRING).append('\n');
        }
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

    /** Reads this process' logcat within {@code timeoutMs}; keeps the newest {@code maxBytes}. Never throws. */
    static String captureLogcat(int lines, int maxBytes, long timeoutMs) {
        Process process = null;
        try {
            ArrayList<String> cmd = new ArrayList<>(Arrays.asList("logcat", "-d", "-v", "threadtime", "-t", String.valueOf(lines)));
            if (Build.VERSION.SDK_INT >= 24) {
                cmd.add("--pid=" + android.os.Process.myPid());
            }
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final InputStream in = process.getInputStream();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
            final int hardCap = maxBytes * 4;
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
            if (text.length() > maxBytes) {
                // -t gives the newest lines last: keep the tail, cut at a line start
                text = text.substring(text.length() - maxBytes);
                int nl = text.indexOf('\n');
                if (nl >= 0 && nl < text.length() - 1) {
                    text = text.substring(nl + 1);
                }
                text = "(обрезано до последних " + maxBytes / 1024 + " КБ)\n" + text;
            }
            if (timedOut) {
                text += "\n(logcat не уложился в " + timeoutMs + " мс)\n";
            }
            return text.isEmpty() ? "(logcat пуст)\n" : text;
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

    private static String oneLine(String s, int max) {
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

    // endregion

    // region reading

    static class Report {
        final File file;
        final long time;
        String type = TYPE_CRASH;
        String exception;
        String at;

        Report(File file, long time) {
            this.file = file;
            this.time = time;
        }

        boolean isCrash() {
            return TYPE_CRASH.equals(type);
        }
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
        File d = dir;
        if (d == null && ApplicationLoader.applicationContext != null) {
            d = new File(ApplicationLoader.applicationContext.getFilesDir(), DIR);
        }
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
        if (f.getName().startsWith(TYPE_LOGCAT + "_")) {
            r.type = TYPE_LOGCAT;
        }
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

    // endregion

    // region UI entry points

    public static void openViewer(BaseFragment fragment) {
        if (fragment != null) {
            fragment.presentFragment(new RawCrashLogActivity());
        }
    }

    /** Shares the report as a file (FileProvider via external files dir), falling back to plain text. */
    static void share(BaseFragment fragment, File f) {
        if (fragment == null || fragment.getParentActivity() == null || f == null) {
            return;
        }
        Context ctx = fragment.getParentActivity();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "rawGram " + f.getName());
        boolean attached = false;
        try {
            // provider_paths has no entry for filesDir/rawgram_crashes; external files dir is covered by external-path "."
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) {
                File shareDir = new File(ext, DIR);
                shareDir.mkdirs();
                File copy = new File(shareDir, f.getName());
                try (FileInputStream in = new FileInputStream(f); FileOutputStream out = new FileOutputStream(copy)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                Uri uri = FileProvider.getUriForFile(ctx, ApplicationLoader.getApplicationId() + ".provider", copy);
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                attached = true;
            }
        } catch (Throwable ignore) {
        }
        if (!attached) {
            intent.putExtra(Intent.EXTRA_TEXT, forClipboard(read(f, CLIPBOARD_LIMIT)));
        }
        try {
            ctx.startActivity(Intent.createChooser(intent, "Поделиться отчётом"));
        } catch (Throwable ignore) {
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void markSeen() {
        try {
            long newest = 0;
            for (File f : listFiles()) {
                if (f.getName().startsWith(TYPE_CRASH + "_")) {
                    newest = Math.max(newest, parseTime(f));
                }
            }
            if (newest > prefs().getLong(KEY_SEEN, 0)) {
                prefs().edit().putLong(KEY_SEEN, newest).apply();
            }
        } catch (Throwable ignore) {
        }
    }

    /** LaunchActivity.onResume: once per process, show a bulletin if a crash happened since the last one shown. */
    public static void checkLastCrash() {
        if (noticeScheduled) {
            return;
        }
        noticeScheduled = true;
        // let the fragment stack / passcode settle first
        AndroidUtilities.runOnUIThread(RawCrashLog::showNoticeIfNeeded, 1500);
    }

    private static void showNoticeIfNeeded() {
        try {
            long seen = prefs().getLong(KEY_SEEN, 0);
            ArrayList<File> unseen = new ArrayList<>();
            for (File f : listFiles()) {
                if (f.getName().startsWith(TYPE_CRASH + "_") && parseTime(f) > seen) {
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
            layout.titleTextView.setText(unseen.size() > 1 ? "rawGram падал " + unseen.size() + " раз(а)" : "rawGram упал в прошлый раз");
            layout.titleTextView.setEllipsize(TextUtils.TruncateAt.END);
            layout.subtitleTextView.setText(shortException(report.exception));
            layout.subtitleTextView.setSingleLine(true);
            layout.subtitleTextView.setEllipsize(TextUtils.TruncateAt.END);
            ActionsButton button = new ActionsButton(ctx, rp);
            button.add("Копировать", () -> {
                AndroidUtilities.addToClipboard(forClipboard(read(newest, CLIPBOARD_LIMIT)));
                RawNotify.show(R.drawable.msg_copy, "Отчёт о падении скопирован");
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
