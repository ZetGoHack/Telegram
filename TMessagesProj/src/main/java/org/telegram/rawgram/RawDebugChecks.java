package org.telegram.rawgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Self-checks of the debug menu. A check returns a short detail text and throws when it fails; other branches add their own
 * with {@link #register}. {@link #runAll} runs them off the UI thread.
 */
public class RawDebugChecks {

    public interface Check {
        String run() throws Throwable;
    }

    public static final class Result {
        public final String name;
        public final boolean ok;
        public final long millis;
        public final String detail;

        Result(String name, boolean ok, long millis, String detail) {
            this.name = name;
            this.ok = ok;
            this.millis = millis;
            this.detail = detail;
        }
    }

    private static final class Entry {
        final String name;
        final Check check;

        Entry(String name, Check check) {
            this.name = name;
            this.check = check;
        }
    }

    private static final List<Entry> entries = new CopyOnWriteArrayList<>();

    static {
        register("TLDumper: объекты TL", RawDebugChecks::tlDumper);
        register("Конфиги читаются", RawDebugChecks::configs);
        register("Настройки: запись и чтение", RawDebugChecks::prefs);
        register("UI-поток отвечает", RawDebugChecks::uiThread);
    }

    private RawDebugChecks() {
    }

    public static void register(String name, Check check) {
        entries.add(new Entry(name, check));
    }

    /** Runs every check one after another; call off the UI thread. */
    public static List<Result> runAll() {
        ArrayList<Result> results = new ArrayList<>();
        for (Entry entry : entries) {
            long start = SystemClock.elapsedRealtime();
            try {
                String detail = entry.check.run();
                results.add(new Result(entry.name, true, SystemClock.elapsedRealtime() - start, detail));
            } catch (Throwable e) {
                results.add(new Result(entry.name, false, SystemClock.elapsedRealtime() - start, e.toString()));
            }
        }
        return results;
    }

    /** A made-up user, for the raw viewer and the dumper checks. */
    public static TLRPC.User sampleUser() {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = 777000;
        user.first_name = "Debug";
        user.last_name = "Sample";
        user.username = "rawgram_debug";
        user.phone = "42";
        user.bot = false;
        return user;
    }

    private static String tlDumper() {
        Object[] samples = {sampleUser(), sampleMessage()};
        StringBuilder detail = new StringBuilder();
        for (Object sample : samples) {
            String json = TLDumper.toJson(sample);
            String fields = TLDumper.toFields(sample);
            if (json.isEmpty() || fields.isEmpty()) {
                throw new IllegalStateException(TLDumper.typeName(sample) + ": пустой дамп");
            }
            if (detail.length() > 0) {
                detail.append(", ");
            }
            detail.append(TLDumper.typeName(sample)).append(' ').append(json.length()).append(" симв.");
        }
        return detail.toString();
    }

    private static TLRPC.Message sampleMessage() {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = 1;
        message.message = "debug";
        message.date = (int) (System.currentTimeMillis() / 1000);
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = 777000;
        return message;
    }

    /** Reads every public no-argument is*()/get*() getter of the config classes: none may throw. */
    private static String configs() throws Exception {
        Class<?>[] classes = {RawgramConfig.class, RawUiConfig.class, RawChatUiConfig.class, RawClassicUi.class, RawMotion.class};
        int read = 0;
        for (Class<?> cls : classes) {
            for (Method method : cls.getDeclaredMethods()) {
                String name = method.getName();
                if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0
                        || !(name.startsWith("is") || name.startsWith("get")) || method.getReturnType() == void.class) {
                    continue;
                }
                try {
                    method.invoke(null);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw new IllegalStateException(cls.getSimpleName() + "." + name + ": " + e.getCause());
                }
                read++;
            }
        }
        if (read == 0) {
            throw new IllegalStateException("геттеры не найдены");
        }
        return read + " значений";
    }

    private static String prefs() {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("rawgram_debug", Context.MODE_PRIVATE);
        long value = System.nanoTime();
        prefs.edit().putLong("check", value).commit();
        long back = prefs.getLong("check", -1);
        prefs.edit().remove("check").commit();
        if (back != value) {
            throw new IllegalStateException("записано " + value + ", прочитано " + back);
        }
        return "совпало";
    }

    private static String uiThread() throws InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("проверка запущена на UI-потоке");
        }
        long start = SystemClock.elapsedRealtime();
        boolean[] onMain = new boolean[1];
        CountDownLatch latch = new CountDownLatch(1);
        AndroidUtilities.runOnUIThread(() -> {
            onMain[0] = Looper.myLooper() == Looper.getMainLooper();
            latch.countDown();
        });
        if (!latch.await(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("UI-поток не ответил за 2 с");
        }
        if (!onMain[0]) {
            throw new IllegalStateException("задача выполнена не на UI-потоке");
        }
        return "ответ за " + (SystemClock.elapsedRealtime() - start) + " мс, "
                + (LaunchActivity.getSafeLastFragment() != null ? "экран есть" : "экрана нет");
    }
}
