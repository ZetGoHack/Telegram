package org.telegram.rawgram;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsService;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * rawGram push diagnostics + the foreground keep-alive service.
 * Telegram only sends FCM pushes with the Firebase credentials it has for an api_id; a custom api_id with
 * its own Firebase project gets none, so delivery relies on the MTProto push connection (token_type 7)
 * kept alive by NotificationsService running in the foreground.
 */
public class RawPushDiag {

    private static final String PREFS = "rawgram_push";
    private static final String CHANNEL_ID = "rawgram_keepalive";
    private static final int NOTIFICATION_ID = 0x7261;

    public static volatile boolean serviceRunning;
    public static volatile boolean serviceForeground;
    public static volatile String serviceError;
    private static volatile long lastInternalSaved;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- hooks ----

    public static void onRemotePush(int pushType) {
        try {
            prefs().edit().putLong("last_remote", System.currentTimeMillis()).putInt("last_remote_type", pushType).apply();
        } catch (Throwable ignore) {
        }
    }

    /** Called from the network thread for every update batch on the push connection; throttled. */
    public static void onInternalPush() {
        long now = System.currentTimeMillis();
        if (now - lastInternalSaved < 10_000) {
            return;
        }
        lastInternalSaved = now;
        try {
            prefs().edit().putLong("last_internal", now).apply();
        } catch (Throwable ignore) {
        }
    }

    public static void onRegisterResult(int account, int pushType, boolean ok, TLRPC.TL_error error) {
        try {
            String result = "акк. " + account + ", token_type " + pushType + ": " + (ok ? "OK" : (error != null ? error.code + " " + error.text : "false"));
            prefs().edit().putString("reg_result", result).putLong("reg_time", System.currentTimeMillis()).apply();
        } catch (Throwable ignore) {
        }
    }

    // ---- keep-alive service ----

    public static void startService(Context context) {
        Intent intent = new Intent(context, NotificationsService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            serviceError = null;
        } catch (Throwable e) {
            // Android 12+ forbids starting a foreground service from the background unless battery-optimization exempt
            serviceError = e.getClass().getSimpleName();
            FileLog.e(e);
            try {
                context.startService(intent);
            } catch (Throwable ignore) {
            }
        }
    }

    public static void onServiceStart(Service service) {
        serviceRunning = true;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Фоновое соединение", NotificationManager.IMPORTANCE_MIN);
                channel.setDescription("Держит rawGram на связи, чтобы уведомления приходили вовремя. Канал можно скрыть.");
                channel.setShowBadge(false);
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.enableLights(false);
                nm.createNotificationChannel(channel);
            }
            Intent settings = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, service.getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID);
            PendingIntent pi = PendingIntent.getActivity(service, 0, settings, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new NotificationCompat.Builder(service, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle("rawGram на связи")
                    .setContentText("Фоновое соединение для уведомлений. Нажми, чтобы скрыть.")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else {
                service.startForeground(NOTIFICATION_ID, notification);
            }
            serviceForeground = true;
        } catch (Throwable e) {
            serviceForeground = false;
            serviceError = e.getClass().getSimpleName();
            FileLog.e(e);
        }
    }

    public static void onServiceDestroy(Service service) {
        serviceRunning = false;
        serviceForeground = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                service.stopForeground(true);
            }
        } catch (Throwable ignore) {
        }
    }

    // ---- diagnostics ----

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean isXiaomi() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.US);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    private static String time(long ms) {
        if (ms <= 0) {
            return "никогда";
        }
        return new SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(new Date(ms));
    }

    public static String buildReport(Context context) {
        StringBuilder sb = new StringBuilder();
        SharedPreferences p = prefs();
        SharedPreferences global = MessagesController.getGlobalNotificationsSettings();

        boolean gms = false;
        try {
            gms = ApplicationLoader.getPushProvider().hasServices();
        } catch (Throwable ignore) {
        }
        sb.append("Google Play Services: ").append(gms ? "есть" : "нет").append('\n');
        sb.append("Push-токен: ");
        if (!TextUtils.isEmpty(SharedConfig.pushString)) {
            sb.append("получен (token_type ").append(SharedConfig.pushType).append(", ").append(SharedConfig.pushString.length()).append(" симв.)");
        } else {
            sb.append("нет");
        }
        if (!TextUtils.isEmpty(SharedConfig.pushStringStatus)) {
            sb.append(", статус ").append(SharedConfig.pushStringStatus);
        }
        sb.append('\n');
        sb.append("registerDevice: ");
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (uc.isClientActivated()) {
                sb.append("акк. ").append(a).append(uc.registeredForPush ? " да; " : " нет; ");
            }
        }
        sb.append('\n');
        String reg = p.getString("reg_result", null);
        if (reg != null) {
            sb.append("Последний ответ: ").append(reg).append(" (").append(time(p.getLong("reg_time", 0))).append(")\n");
        }
        sb.append("Последний FCM-пуш: ").append(time(p.getLong("last_remote", 0))).append('\n');
        sb.append('\n');

        boolean pushConnection = ConnectionsManager.getInstance(UserConfig.selectedAccount).isPushConnectionEnabled();
        sb.append("Фоновое соединение (token_type 7): ").append(pushConnection ? "вкл" : "выкл").append('\n');
        sb.append("Последнее событие по нему: ").append(time(p.getLong("last_internal", 0))).append('\n');
        sb.append("Служба: ").append(global.getBoolean("pushService", true) ? "вкл" : "выкл").append(", ")
                .append(serviceRunning ? (serviceForeground ? "работает (foreground)" : "работает (без foreground)") : "не запущена");
        if (serviceError != null) {
            sb.append(", ошибка ").append(serviceError);
        }
        sb.append('\n');
        boolean battery = isIgnoringBatteryOptimizations(context);
        sb.append("Оптимизация батареи: ").append(battery ? "отключена" : "ВКЛЮЧЕНА - система будет усыплять rawGram").append('\n');
        boolean notifications = true;
        try {
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled();
        } catch (Throwable ignore) {
        }
        sb.append("Уведомления в системе: ").append(notifications ? "разрешены" : "ЗАПРЕЩЕНЫ").append('\n');
        sb.append('\n');
        sb.append("Telegram шлёт FCM-пуши только приложениям, ключи Firebase которых ему известны. Для собственного api_id rawGram их, скорее всего, не будет, поэтому уведомления идут через фоновое соединение: нужна служба, отключённая оптимизация батареи");
        if (isXiaomi()) {
            sb.append(" и автозапуск (HyperOS/MIUI)");
        }
        sb.append('.');
        return sb.toString();
    }

    public static void show(BaseFragment fragment) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        String report = buildReport(activity);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Диагностика уведомлений");
        builder.setMessage(report);
        builder.setPositiveButton("Закрыть", null);
        if (!isIgnoringBatteryOptimizations(activity)) {
            builder.setNegativeButton("Батарея", (d, w) -> requestBatteryExemption(activity));
        } else {
            builder.setNegativeButton("Копировать", (d, w) -> AndroidUtilities.addToClipboard(report));
        }
        if (isXiaomi()) {
            builder.setNeutralButton("Автозапуск", (d, w) -> openAutostart(activity));
        }
        fragment.showDialog(builder.create());
    }

    public static void requestBatteryExemption(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Throwable e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignore) {
            }
        }
    }

    public static void openAutostart(Activity activity) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            activity.startActivity(intent);
        } catch (Throwable e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName())));
            } catch (Throwable ignore) {
            }
        }
    }
}
