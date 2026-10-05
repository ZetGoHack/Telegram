package org.telegram.rawgram;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Report export instead of the clipboard: «Переслать» (document to a Telegram chat), «Поделиться» (ACTION_SEND)
 * and «Сохранить в загрузки». Files are copied with readable names to externalFilesDir/rawgram_export:
 * internal paths are refused by Telegram's sender (AndroidUtilities.isInternalUri) and by MediaController.saveFile.
 */
public class RawReportExport {

    static final String MIME_TEXT = "text/plain";
    static final String MIME_ZIP = "application/zip";
    private static final String EXPORT_DIR = "rawgram_export";
    private static final long EXPORT_TTL = 24L * 60 * 60 * 1000;

    // region files

    static File exportDir() {
        Context ctx = ApplicationLoader.applicationContext;
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            base = ctx.getExternalCacheDir();
        }
        if (base == null) {
            return null;
        }
        File d = new File(base, EXPORT_DIR);
        d.mkdirs();
        // old exports are not needed once sent; keep recent ones (uploads may still be running)
        File[] old = d.listFiles();
        if (old != null) {
            long cutoff = System.currentTimeMillis() - EXPORT_TTL;
            for (File f : old) {
                if (f.lastModified() < cutoff) {
                    f.delete();
                }
            }
        }
        return d;
    }

    /** rawgram-crash-2026-10-01_18-41-07 */
    static String baseName(RawCrashLog.Report r) {
        return "rawgram-" + r.type + "-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date(r.time));
    }

    /** Copy of the report with a readable name in the export dir, or null. */
    static File prepare(File report) {
        try {
            File d = exportDir();
            if (d == null) {
                return null;
            }
            File out = new File(d, baseName(RawCrashLog.readHeader(report)) + ".txt");
            copy(report, out);
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    static File writeText(String name, String text) {
        try {
            File d = exportDir();
            if (d == null) {
                return null;
            }
            File out = new File(d, name);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
                w.write(text);
            }
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    /** All reports in one zip. Blocking. */
    static File zip(List<File> reports) {
        try {
            File d = exportDir();
            if (d == null || reports.isEmpty()) {
                return null;
            }
            File out = new File(d, "rawgram-reports-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date()) + ".zip");
            HashSet<String> names = new HashSet<>();
            byte[] buf = new byte[16384];
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
                for (File f : reports) {
                    String name = baseName(RawCrashLog.readHeader(f));
                    String entry = name + ".txt";
                    for (int i = 2; !names.add(entry); i++) {
                        entry = name + "-" + i + ".txt";
                    }
                    ZipEntry ze = new ZipEntry(entry);
                    ze.setTime(f.lastModified());
                    zos.putNextEntry(ze);
                    try (FileInputStream in = new FileInputStream(f)) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            zos.write(buf, 0, n);
                        }
                    }
                    zos.closeEntry();
                }
            }
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void copy(File from, File to) throws Exception {
        try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    // endregion

    // region actions

    /** Options for one report: forward / share / save / copy summary. */
    static void showReportActions(BaseFragment fragment, File report, Runnable onDelete) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        ArrayList<CharSequence> items = new ArrayList<>();
        ArrayList<Integer> icons = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        items.add("Переслать");
        icons.add(R.drawable.msg_forward);
        actions.add(() -> forwardReport(fragment, report));
        items.add("Поделиться");
        icons.add(R.drawable.msg_share);
        actions.add(() -> withExport(fragment, report, f -> share(fragment, f, MIME_TEXT)));
        items.add("Сохранить в загрузки");
        icons.add(R.drawable.msg_download);
        actions.add(() -> withExport(fragment, report, f -> saveToDownloads(fragment, f, MIME_TEXT)));
        items.add("Копировать сводку");
        icons.add(R.drawable.msg_copy);
        actions.add(() -> copySummary(report));
        if (onDelete != null) {
            items.add("Удалить");
            icons.add(R.drawable.msg_delete);
            actions.add(onDelete);
        }
        showOptions(fragment, report.getName(), items, icons, actions);
    }

    /** Options for an already exported file (e.g. the zip of all reports). */
    static void showFileActions(BaseFragment fragment, File file, String mime, String caption) {
        ArrayList<CharSequence> items = new ArrayList<>();
        ArrayList<Integer> icons = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        items.add("Переслать");
        icons.add(R.drawable.msg_forward);
        actions.add(() -> forward(fragment, file, mime, caption));
        items.add("Поделиться");
        icons.add(R.drawable.msg_share);
        actions.add(() -> share(fragment, file, mime));
        items.add("Сохранить в загрузки");
        icons.add(R.drawable.msg_download);
        actions.add(() -> saveToDownloads(fragment, file, mime));
        showOptions(fragment, file.getName(), items, icons, actions);
    }

    private static void showOptions(BaseFragment fragment, String title, ArrayList<CharSequence> items, ArrayList<Integer> icons, ArrayList<Runnable> actions) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        int[] iconArray = new int[icons.size()];
        for (int i = 0; i < iconArray.length; i++) {
            iconArray[i] = icons.get(i);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        builder.setTitle(title);
        builder.setItems(items.toArray(new CharSequence[0]), iconArray, (dialog, which) -> {
            if (which >= 0 && which < actions.size()) {
                actions.get(which).run();
            }
        });
        fragment.showDialog(builder.create());
    }

    private interface FileAction {
        void run(File file);
    }

    private static void withExport(BaseFragment fragment, File report, FileAction action) {
        File f = prepare(report);
        if (f == null) {
            RawNotify.show(R.drawable.msg_warning, "Не удалось подготовить файл (нет внешнего хранилища?)");
            return;
        }
        action.run(f);
    }

    static void forwardReport(BaseFragment fragment, File report) {
        withExport(fragment, report, f -> forward(fragment, f, MIME_TEXT, RawCrashLog.summaryLine(RawCrashLog.readHeader(report))));
    }

    static void shareReport(BaseFragment fragment, File report) {
        withExport(fragment, report, f -> share(fragment, f, MIME_TEXT));
    }

    static void saveReport(BaseFragment fragment, File report) {
        withExport(fragment, report, f -> saveToDownloads(fragment, f, MIME_TEXT));
    }

    /** One section of a report as its own small text file. */
    static void shareSection(BaseFragment fragment, File report, String sectionId, String text) {
        File f = writeText(baseName(RawCrashLog.readHeader(report)) + "-" + sectionId + ".txt", text);
        if (f == null) {
            RawNotify.show(R.drawable.msg_warning, "Не удалось подготовить файл (нет внешнего хранилища?)");
            return;
        }
        share(fragment, f, MIME_TEXT);
    }

    static void copySummary(File report) {
        AndroidUtilities.addToClipboard(RawCrashLog.summaryLine(RawCrashLog.readHeader(report)));
        RawNotify.show(R.drawable.msg_copy, "Сводка скопирована");
    }

    /** Zips every report and offers the same three actions. */
    static void sendAll(BaseFragment fragment) {
        ArrayList<File> files = RawCrashLog.listFiles();
        if (files.isEmpty()) {
            RawNotify.show(R.drawable.msg_info, "Отчётов нет");
            return;
        }
        new Thread(() -> {
            File zip = zip(files);
            AndroidUtilities.runOnUIThread(() -> {
                if (zip == null) {
                    RawNotify.show(R.drawable.msg_warning, "Не удалось собрать архив");
                } else {
                    showFileActions(fragment, zip, MIME_ZIP, "rawGram: отчёты (" + files.size() + ")");
                }
            });
        }, "rawgram-report-zip").start();
    }

    /** Telegram chat picker, then sends the file as a document to each picked chat. */
    static void forward(BaseFragment from, File file, String mime, String caption) {
        if (from == null || file == null) {
            return;
        }
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) {
                return false;
            }
            String text = !TextUtils.isEmpty(message) ? message.toString() : caption;
            if (text != null && text.length() > 900) {
                text = text.substring(0, 900) + "…";
            }
            AccountInstance account = AccountInstance.getInstance(fragment.getCurrentAccount());
            String path = file.getAbsolutePath();
            for (MessagesStorage.TopicKey key : dids) {
                SendMessagesHelper.prepareSendingDocument(account, path, path, null, text, mime, key.dialogId, null, null, null, null, null, notify, scheduleDate, null, null, false);
            }
            long did = dids.get(0).dialogId;
            if (dids.size() == 1 && !DialogObject.isEncryptedDialog(did)) {
                fragment.presentFragment(ChatActivity.of(did), true);
            } else {
                fragment.finishFragment();
                RawNotify.show(R.drawable.msg_forward, "Отправлено в чатов: " + dids.size());
            }
            return true;
        });
        from.presentFragment(picker);
    }

    static void share(BaseFragment from, File file, String mime) {
        if (from == null || from.getParentActivity() == null || file == null) {
            return;
        }
        Context ctx = from.getParentActivity();
        try {
            Uri uri = FileProvider.getUriForFile(ctx, ApplicationLoader.getApplicationId() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_SUBJECT, file.getName());
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(Intent.createChooser(intent, "Поделиться отчётом"));
        } catch (Throwable e) {
            RawNotify.show(R.drawable.msg_warning, "Не удалось поделиться: " + e.getMessage());
        }
    }

    static void saveToDownloads(BaseFragment from, File file, String mime) {
        if (from == null || from.getParentActivity() == null || file == null) {
            return;
        }
        Activity activity = from.getParentActivity();
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
            RawNotify.show(R.drawable.msg_warning, "Нужен доступ к памяти — разреши и повтори");
            return;
        }
        String name = file.getName();
        // rawGram's own folder (Download/rawGram), not Telegram's: backups and reports are easy to find and stay apart
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            String error = null;
            try {
                copyToDownloads(activity, file, name, mime);
            } catch (Throwable e) {
                org.telegram.messenger.FileLog.e(e);
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            String failure = error;
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                if (failure != null) {
                    RawNotify.show(R.drawable.msg_warning, "Не удалось сохранить: " + failure);
                } else {
                    RawNotify.show(R.drawable.msg_download, "Сохранено в «Загрузки/rawGram»: " + name);
                }
            });
        });
    }

    static final String DOWNLOADS_FOLDER = "rawGram";

    private static void copyToDownloads(Context ctx, File file, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOADS_FOLDER + "/");
            Uri uri = ctx.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("MediaStore insert failed");
            }
            try (java.io.OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                copy(file, out);
            }
        } else {
            File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), DOWNLOADS_FOLDER);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IllegalStateException("нет доступа к «Загрузкам»");
            }
            File dest = new File(dir, name);
            try (java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
                copy(file, out);
            }
            android.media.MediaScannerConnection.scanFile(ctx, new String[]{dest.getAbsolutePath()}, new String[]{mime}, null);
        }
    }

    private static void copy(File from, java.io.OutputStream out) throws Exception {
        if (out == null) {
            throw new IllegalStateException("нет потока записи");
        }
        byte[] buffer = new byte[1 << 16];
        try (java.io.InputStream in = new java.io.FileInputStream(from)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
    }

    // endregion
}
