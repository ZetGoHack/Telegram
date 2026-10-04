package org.telegram.rawgram;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TextStyleSpan;
import org.telegram.ui.Components.spoilers.SpoilersTextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Backup and restore of the app's data, three levels:
 * <ul>
 * <li>settings — rawGram and Telegram app settings (shared_prefs);</li>
 * <li>full — settings plus what rawGram collected (crash reports, push diagnostics, plugins);</li>
 * <li>storage (long press on «full») — the whole app storage that an uninstall would wipe, except caches: sessions,
 * databases, settings. Encrypted with a generated password shown once under a spoiler.</li>
 * </ul>
 * A backup is a zip with relative paths under the app's data dir and a {@link #MANIFEST}. The storage backup is the
 * same zip encrypted in OpenSSL's format, so it can also be opened outside the app:
 * {@code openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 -in file.rgbak -out backup.zip}.
 * <p>
 * Restore never writes over files the running app has open: the zip is staged in files/rawgram_restore and applied
 * from ApplicationLoader.attachBaseContext on the next start, before anything reads settings or sessions.
 */
public final class RawBackup {

    public static final int KIND_SETTINGS = 0, KIND_DATA = 1, KIND_STORAGE = 2;
    public static final int REQUEST_PICK = 0x7a11;

    private static final String MANIFEST = "rawgram-backup.json";
    private static final String RESTORE_DIR = "rawgram_restore";
    private static final String STAGED = "staged.zip";
    private static final String PENDING = "PENDING";
    private static final byte[] SALTED = "Salted__".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 200_000;
    private static final int LOCK_SECONDS = 10;

    /** The owner's accounts: no countdown on the storage warning. */
    private static final long[] TRUSTED = {1226061708L, 1703168702L};

    private static final String[] SETTINGS_PREFS = {
            "rawgram", "rawgram_ui", "rawgram_chat_ui", "rawgram_classic", "rawgram_object_sheet",
            "mainconfig", "themeconfig", "langconfig", "exteraconfig", "plugin_settings"
    };
    private static final String[] DATA_PREFS = {"rawgram_crashlog", "rawgram_push"};
    private static final String[] DATA_DIRS = {"files/" + RawCrashLog.DIR, "files/plugins"};
    /** Never part of a storage backup and never wiped by a restore. */
    private static final String[] STORAGE_SKIP = {"cache", "code_cache", "lib", "app_webview", "files/cache", "files/" + RESTORE_DIR};

    private RawBackup() {
    }

    // region backup

    public static void backupSettings(BaseFragment fragment) {
        create(fragment, KIND_SETTINGS, null);
    }

    public static void backupData(BaseFragment fragment) {
        create(fragment, KIND_DATA, null);
    }

    /** Warning with the generated password; «Продолжить» unlocks after 10 s (at once on the owner's accounts). */
    public static void backupStorage(BaseFragment fragment) {
        Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null) {
            return;
        }
        String password = generatePassword();
        boolean trusted = hasTrustedAccount();

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView text = new TextView(activity);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        text.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, fragment.getResourceProvider()));
        text.setText(trusted
                ? "Копия всего хранилища приложения, включая сессии аккаунтов. Файл зашифрован паролем ниже — без него копию не восстановить."
                : "Будет сохранено всё хранилище приложения, кроме кэша: настройки, базы и сессии всех аккаунтов.\n\n"
                + "Тот, у кого окажутся файл и пароль, получит полный доступ к аккаунтам без кода и двухэтапной проверки. "
                + "Не отправляй копию никому и не храни пароль рядом с файлом.\n\n"
                + "Файл зашифрован паролем ниже — без него копию не восстановить. Нажми на пароль, чтобы показать и скопировать:");
        layout.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 12));

        SpoilersTextView passwordView = new SpoilersTextView(activity, true, fragment.getResourceProvider());
        passwordView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        passwordView.setTypeface(android.graphics.Typeface.MONOSPACE);
        passwordView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, fragment.getResourceProvider()));
        passwordView.setGravity(Gravity.CENTER);
        SpannableString spoiler = new SpannableString(password);
        TextStyleSpan.TextStyleRun run = new TextStyleSpan.TextStyleRun();
        run.flags |= TextStyleSpan.FLAG_STYLE_SPOILER;
        spoiler.setSpan(new TextStyleSpan(run), 0, spoiler.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        passwordView.setText(spoiler);
        passwordView.setOnLongClickListener(v -> {
            AndroidUtilities.addToClipboard(password);
            RawNotify.show(R.drawable.msg_copy, "Пароль скопирован");
            return true;
        });
        // the first tap reveals (SpoilersTextView), the next one copies
        passwordView.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(password);
            RawNotify.show(R.drawable.msg_copy, "Пароль скопирован");
        });
        layout.addView(passwordView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));

        AlertDialog dialog = new AlertDialog.Builder(activity, fragment.getResourceProvider())
                .setTitle("Резервная копия хранилища")
                .setView(layout)
                .setPositiveButton("Продолжить", (d, w) -> create(fragment, KIND_STORAGE, password))
                .setNegativeButton("Отмена", null)
                .create();
        fragment.showDialog(dialog);
        if (!trusted) {
            View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (button instanceof TextView) {
                lockButton((TextView) button, LOCK_SECONDS);
            }
        }
    }

    private static void lockButton(TextView button, int seconds) {
        if (seconds <= 0) {
            button.setEnabled(true);
            button.setAlpha(1f);
            button.setText("Продолжить");
            return;
        }
        button.setEnabled(false);
        button.setAlpha(0.5f);
        button.setText("Продолжить (" + seconds + ")");
        button.postDelayed(() -> lockButton(button, seconds - 1), 1000);
    }

    private static boolean hasAnyAccount() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTrustedAccount() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (!config.isClientActivated()) {
                continue;
            }
            long id = config.getClientUserId();
            for (long trusted : TRUSTED) {
                if (id == trusted) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String generatePassword() {
        final String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0 && i % 5 == 0) {
                sb.append('-');
            }
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static void create(BaseFragment fragment, int kind, String password) {
        Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null) {
            return;
        }
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        Utilities.globalQueue.postRunnable(() -> {
            File out = null;
            String error = null;
            try {
                flushPrefs();
                File dir = RawReportExport.exportDir();
                if (dir == null) {
                    throw new IllegalStateException("нет доступа к памяти");
                }
                String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
                String name = kind == KIND_SETTINGS ? "rawgram-settings-" : kind == KIND_DATA ? "rawgram-backup-" : "rawgram-storage-";
                out = new File(dir, name + stamp + (kind == KIND_STORAGE ? ".rgbak" : ".zip"));
                writeBackup(kind, out, password);
            } catch (Throwable e) {
                FileLog.e(e);
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (out != null) {
                    out.delete();
                }
            }
            File result = out;
            String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                if (failure != null) {
                    RawNotify.show(R.drawable.msg_warning, "Копия не создана: " + failure);
                    return;
                }
                RawNotify.show(R.drawable.msg_download, "Копия готова: " + AndroidUtilities.formatFileSize(result.length())
                        + ". Сохрани её в загрузки — папка приложения удаляется вместе с ним.");
                RawReportExport.showFileActions(fragment, result, kind == KIND_STORAGE ? "application/octet-stream" : "application/zip", null);
            });
        });
    }

    /** Pending apply() writes reach the disk before the files are copied. */
    private static void flushPrefs() {
        Context ctx = ApplicationLoader.applicationContext;
        for (String name : SETTINGS_PREFS) {
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit();
        }
        for (String name : DATA_PREFS) {
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit();
        }
    }

    private static void writeBackup(int kind, File out, String password) throws Exception {
        File dataDir = new File(ApplicationLoader.applicationContext.getApplicationInfo().dataDir);
        Map<String, File> files = new LinkedHashMap<>();
        if (kind == KIND_STORAGE) {
            collect(dataDir, "", files);
        } else {
            for (String name : SETTINGS_PREFS) {
                addIfExists(dataDir, "shared_prefs/" + name + ".xml", files);
            }
            if (kind == KIND_DATA) {
                for (String name : DATA_PREFS) {
                    addIfExists(dataDir, "shared_prefs/" + name + ".xml", files);
                }
                for (String path : DATA_DIRS) {
                    File d = new File(dataDir, path);
                    if (d.isDirectory()) {
                        collect(d, path + "/", files);
                    }
                }
            }
        }

        OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16);
        try {
            if (password != null) {
                byte[] salt = new byte[8];
                new SecureRandom().nextBytes(salt);
                os.write(SALTED);
                os.write(salt);
                os = new CipherOutputStream(os, cipher(Cipher.ENCRYPT_MODE, password, salt));
            }
            try (ZipOutputStream zip = new ZipOutputStream(os)) {
                zip.putNextEntry(new ZipEntry(MANIFEST));
                zip.write(manifest(kind, files.size()).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                byte[] buffer = new byte[1 << 16];
                for (Map.Entry<String, File> e : files.entrySet()) {
                    File f = e.getValue();
                    if (!f.canRead()) {
                        continue;
                    }
                    ZipEntry entry = new ZipEntry(e.getKey());
                    entry.setTime(f.lastModified());
                    zip.putNextEntry(entry);
                    try (InputStream in = new FileInputStream(f)) {
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            zip.write(buffer, 0, n);
                        }
                    }
                    zip.closeEntry();
                }
            }
        } finally {
            try {
                os.close();
            } catch (Exception ignore) {
            }
        }
    }

    private static void addIfExists(File dataDir, String path, Map<String, File> files) {
        File f = new File(dataDir, path);
        if (f.isFile()) {
            files.put(path, f);
        }
    }

    private static void collect(File dir, String prefix, Map<String, File> files) {
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            String path = prefix + f.getName();
            if (isSkipped(path)) {
                continue;
            }
            if (f.isDirectory()) {
                collect(f, path + "/", files);
            } else if (f.isFile()) {
                files.put(path, f);
            }
        }
    }

    private static boolean isSkipped(String path) {
        for (String skip : STORAGE_SKIP) {
            if (path.equals(skip) || path.startsWith(skip + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String manifest(int kind, int count) throws Exception {
        JSONObject json = new JSONObject();
        json.put("format", 1);
        json.put("kind", kind == KIND_SETTINGS ? "settings" : kind == KIND_DATA ? "data" : "storage");
        json.put("created", System.currentTimeMillis());
        json.put("package", ApplicationLoader.applicationContext.getPackageName());
        json.put("version", BuildVars.BUILD_VERSION_STRING);
        json.put("files", count);
        JSONArray accounts = new JSONArray();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accounts.put(UserConfig.getInstance(a).getClientUserId());
            }
        }
        json.put("accounts", accounts);
        return json.toString(2);
    }

    // endregion

    // region crypto (OpenSSL enc -aes-256-cbc -pbkdf2 -md sha256 compatible)

    private static Cipher cipher(int mode, String password, byte[] salt) throws Exception {
        byte[] keyIv = pbkdf2(password.getBytes(StandardCharsets.UTF_8), salt, ITERATIONS, 48);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(keyIv, 0, 32, "AES"), new IvParameterSpec(keyIv, 32, 16));
        return cipher;
    }

    /** PBKDF2-HMAC-SHA256 by hand: SecretKeyFactory "PBKDF2WithHmacSHA256" only exists from API 26 (minSdk is 21). */
    private static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        int hLen = mac.getMacLength();
        int blocks = (length + hLen - 1) / hLen;
        byte[] out = new byte[blocks * hLen];
        for (int i = 1; i <= blocks; i++) {
            mac.update(salt);
            mac.update(new byte[]{(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i});
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int c = 1; c < iterations; c++) {
                u = mac.doFinal(u);
                for (int k = 0; k < hLen; k++) {
                    t[k] ^= u[k];
                }
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen);
        }
        return Arrays.copyOf(out, length);
    }

    // endregion

    // region restore

    /** Long press on the Telegram icon of the login screen. */
    public static void showLoginMenu(BaseFragment fragment, View anchor) {
        ItemOptions.makeOptions(fragment, anchor)
                .setGravity(Gravity.CENTER_HORIZONTAL)
                .add(R.drawable.msg_download, "Восстановить данные", () -> pickRestore(fragment))
                .show();
    }

    public static void pickRestore(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            fragment.startActivityForResult(intent, REQUEST_PICK);
        } catch (Exception e) {
            RawNotify.show(R.drawable.msg_warning, "Не удалось открыть выбор файла");
        }
    }

    /** Call from the host fragment's onActivityResultFragment. */
    public static boolean onActivityResult(BaseFragment fragment, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK) {
            return false;
        }
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            receive(fragment, data.getData());
        }
        return true;
    }

    private static File restoreDir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), RESTORE_DIR);
        dir.mkdirs();
        return dir;
    }

    private static void receive(BaseFragment fragment, Uri uri) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        Utilities.globalQueue.postRunnable(() -> {
            File incoming = new File(restoreDir(), "incoming.bin");
            String error = null;
            boolean encrypted = false;
            try (InputStream in = activity.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(incoming)) {
                if (in == null) {
                    throw new IllegalStateException("файл недоступен");
                }
                copy(in, out);
                encrypted = startsWith(incoming, SALTED);
            } catch (Throwable e) {
                FileLog.e(e);
                error = e.getMessage();
            }
            String failure = error;
            boolean needsPassword = encrypted;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                if (failure != null) {
                    RawNotify.show(R.drawable.msg_warning, "Не удалось прочитать файл: " + failure);
                } else if (needsPassword) {
                    askPassword(fragment, incoming);
                } else {
                    stage(fragment, incoming, null);
                }
            });
        });
    }

    private static void askPassword(BaseFragment fragment, File incoming) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setHint("xxxxx-xxxxx-xxxxx-xxxxx");
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, fragment.getResourceProvider()));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, fragment.getResourceProvider()));
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout layout = new LinearLayout(activity);
        layout.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));
        AlertDialog dialog = new AlertDialog.Builder(activity, fragment.getResourceProvider())
                .setTitle("Пароль копии")
                .setMessage("Копия хранилища зашифрована. Введи пароль, который был показан при её создании.")
                .setView(layout)
                .setPositiveButton("Расшифровать", (d, w) -> stage(fragment, incoming, input.getText().toString().trim()))
                .setNegativeButton("Отмена", (d, w) -> incoming.delete())
                .create();
        fragment.showDialog(dialog);
        AndroidUtilities.runOnUIThread(() -> {
            input.requestFocus();
            AndroidUtilities.showKeyboard(input);
        }, 200);
    }

    /** Decrypts (when needed) into staged.zip, reads the manifest and asks for confirmation. */
    private static void stage(BaseFragment fragment, File incoming, String password) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        Utilities.globalQueue.postRunnable(() -> {
            File staged = new File(restoreDir(), STAGED);
            JSONObject manifest = null;
            String error = null;
            try {
                if (password != null) {
                    try (InputStream raw = new BufferedInputStream(new FileInputStream(incoming), 1 << 16)) {
                        byte[] header = new byte[16];
                        if (raw.read(header) != 16) {
                            throw new IllegalStateException("файл повреждён");
                        }
                        Cipher cipher = cipher(Cipher.DECRYPT_MODE, password, Arrays.copyOfRange(header, 8, 16));
                        try (InputStream in = new CipherInputStream(raw, cipher); OutputStream out = new FileOutputStream(staged)) {
                            copy(in, out);
                        }
                    }
                    incoming.delete();
                } else if (!incoming.renameTo(staged)) {
                    throw new IllegalStateException("не удалось подготовить файл");
                }
                manifest = readManifest(staged);
                if (manifest == null) {
                    throw new IllegalStateException(password != null ? "неверный пароль" : "это не резервная копия rawGram");
                }
            } catch (Throwable e) {
                FileLog.e(e);
                error = password != null && !(e instanceof IllegalStateException) ? "неверный пароль" : e.getMessage();
                staged.delete();
                incoming.delete();
            }
            String failure = error;
            JSONObject info = manifest;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                if (failure != null) {
                    RawNotify.show(R.drawable.msg_warning, "Восстановление невозможно: " + failure);
                } else {
                    confirm(fragment, staged, info);
                }
            });
        });
    }

    private static void confirm(BaseFragment fragment, File staged, JSONObject manifest) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        String kind = manifest.optString("kind");
        // a fresh install (nobody logged in) has nothing to lose: no talk of wiping, no red button
        boolean fresh = !hasAnyAccount();
        boolean destructive = "storage".equals(kind) && !fresh;
        String what = "storage".equals(kind) ? (fresh ? "всё хранилище: аккаунты, базы и настройки" : "всё хранилище: сессии, базы и настройки. Текущие данные приложения будут удалены")
                : "data".equals(kind) ? "настройки и собранные данные rawGram" : "настройки rawGram и Telegram";
        String date = new SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.getDefault()).format(new Date(manifest.optLong("created")));
        String text = "Копия от " + date + " (" + manifest.optString("package") + ", " + manifest.optString("version") + ").\n\n"
                + "Будет восстановлено " + what + ". Приложение перезапустится.";
        AlertDialog dialog = new AlertDialog.Builder(activity, fragment.getResourceProvider())
                .setTitle("Восстановить данные?")
                .setMessage(text)
                .setPositiveButton("Восстановить", (d, w) -> {
                    try {
                        new File(restoreDir(), PENDING).createNewFile();
                    } catch (Exception e) {
                        RawNotify.show(R.drawable.msg_warning, "Не удалось подготовить восстановление");
                        return;
                    }
                    RawUiSettingsActivity.restartApp(activity);
                })
                .setNegativeButton("Отмена", (d, w) -> staged.delete())
                .create();
        fragment.showDialog(dialog);
        if (destructive) {
            TextView button = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (button != null) {
                button.setTextColor(Theme.getColor(Theme.key_text_RedBold, fragment.getResourceProvider()));
            }
        }
    }

    private static JSONObject readManifest(File zip) {
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (MANIFEST.equals(entry.getName())) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    copy(in, bytes);
                    return new JSONObject(bytes.toString("UTF-8"));
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /**
     * ApplicationLoader.attachBaseContext: applies a staged restore before anything reads settings, databases or
     * sessions. A storage restore first clears the data dir (except caches and the staged file).
     */
    public static void applyPending(Context base) {
        File dir;
        try {
            dir = new File(base.getFilesDir(), RESTORE_DIR);
        } catch (Throwable e) {
            return;
        }
        File pending = new File(dir, PENDING);
        if (!pending.exists()) {
            return;
        }
        // never retry a broken restore on every start
        pending.delete();
        File staged = new File(dir, STAGED);
        try {
            JSONObject manifest = readManifest(staged);
            if (manifest == null) {
                return;
            }
            File dataDir = new File(base.getApplicationInfo().dataDir);
            String canonicalRoot = dataDir.getCanonicalPath() + File.separator;
            if ("storage".equals(manifest.optString("kind"))) {
                wipe(dataDir, "");
            }
            byte[] buffer = new byte[1 << 16];
            try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(staged), 1 << 16))) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || MANIFEST.equals(name) || isSkipped(name)) {
                        continue;
                    }
                    File target = new File(dataDir, name);
                    if (!target.getCanonicalPath().startsWith(canonicalRoot)) {
                        continue; // zip slip
                    }
                    File parent = target.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (OutputStream out = new FileOutputStream(target)) {
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            out.write(buffer, 0, n);
                        }
                    }
                }
            }
            resetServerListTimers(base);
        } catch (Throwable e) {
            android.util.Log.e("rawGram", "restore failed", e);
        } finally {
            staged.delete();
            new File(dir, "incoming.bin").delete();
        }
    }

    /**
     * Telegram refetches recent stickers, GIFs, favorites and emoji packs at most once an hour and keeps the time of
     * the last fetch in the emoji prefs. Restored with the backup, those times say «fresh», so lists missing from the
     * restored database would stay empty for up to an hour: drop the times so everything is fetched on start.
     */
    private static void resetServerListTimers(Context base) {
        File prefsDir = new File(base.getApplicationInfo().dataDir, "shared_prefs");
        String[] names = prefsDir.list();
        if (names == null) {
            return;
        }
        for (String file : names) {
            if (!file.matches("emoji\\d*\\.xml")) {
                continue;
            }
            android.content.SharedPreferences prefs = base.getSharedPreferences(file.substring(0, file.length() - 4), Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = prefs.edit();
            for (String key : prefs.getAll().keySet()) {
                if (key.startsWith("last") && key.contains("LoadTime")) {
                    editor.remove(key);
                }
            }
            editor.commit();
        }
    }

    private static void wipe(File dir, String prefix) {
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            String path = prefix + f.getName();
            if (isSkipped(path)) {
                continue;
            }
            if (f.isDirectory()) {
                wipe(f, path + "/");
                if (!isAncestorOfSkipped(path)) {
                    f.delete();
                }
            } else {
                f.delete();
            }
        }
    }

    private static boolean isAncestorOfSkipped(String path) {
        for (String skip : STORAGE_SKIP) {
            if (skip.startsWith(path + "/")) {
                return true;
            }
        }
        return false;
    }

    // endregion

    private static boolean startsWith(File file, byte[] prefix) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[prefix.length];
            return in.read(head) == prefix.length && Arrays.equals(head, prefix);
        } catch (Exception e) {
            return false;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buffer = new byte[1 << 16];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
    }
}
