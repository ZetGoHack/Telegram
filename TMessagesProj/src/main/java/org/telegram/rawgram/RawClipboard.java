package org.telegram.rawgram;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.util.Locale;

/** Puts local image files on the system clipboard as a content Uri (so other apps can paste them). */
public class RawClipboard {

    /**
     * Copies {@code file} as a content Uri with the given mime. Files outside the FileProvider paths
     * (internal cache) are first copied to files/cache/rawgram_clip. {@code done} gets the result on the UI thread.
     */
    public static void copyImage(Context context, File file, String mime, Utilities.Callback<Boolean> done) {
        if (file == null || !file.exists()) {
            if (done != null) done.run(false);
            return;
        }
        final String type = mime != null ? mime : guessImageMime(file);
        Context app = ApplicationLoader.applicationContext;
        Uri uri = null;
        try {
            uri = FileProvider.getUriForFile(context != null ? context : app, ApplicationLoader.getApplicationId() + ".provider", file);
        } catch (IllegalArgumentException e) {
            // file is in a dir the provider doesn't expose (internal cache): copy it under files/cache first
        }
        if (uri != null) {
            boolean ok = setClip(context != null ? context : app, uri, type);
            if (done != null) done.run(ok);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            Uri copied = null;
            try {
                File dir = new File(app.getFilesDir(), "cache/rawgram_clip");
                File[] old = dir.listFiles();
                if (old != null) {
                    for (File f : old) f.delete();
                }
                dir.mkdirs();
                File copy = new File(dir, file.getName());
                if (AndroidUtilities.copyFile(file, copy)) {
                    copied = FileProvider.getUriForFile(app, ApplicationLoader.getApplicationId() + ".provider", copy);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            final Uri result = copied;
            AndroidUtilities.runOnUIThread(() -> {
                boolean ok = result != null && setClip(app, result, type);
                if (done != null) done.run(ok);
            });
        });
    }

    /** Image mime by file extension, jpeg when unknown (Telegram photos are stored as .jpg). */
    public static String guessImageMime(File file) {
        String name = file != null ? file.getName().toLowerCase(Locale.ROOT) : "";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".heic")) return "image/heic";
        return "image/jpeg";
    }

    private static boolean setClip(Context context, Uri uri, String mime) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(new ClipData("rawGram photo", new String[]{mime}, new ClipData.Item(uri)));
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }
}
