package org.telegram.rawgram;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.TextureView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Media viewer ⋮ items from Nagram (MediaViewerMenuItemCopyFrame / SetProfilePhoto / ScanQRCode):
 * «Копировать кадр», «Поставить на аватарку», «Сканировать QR-код».
 */
public final class RawViewerExtras {

    private RawViewerExtras() {
    }

    public static boolean enabled() {
        return RawChatUiConfig.viewerExtras.get();
    }

    /** The frame on screen now, from the player's texture. */
    public static void copyFrame(Context context, TextureView texture) {
        Bitmap frame = texture != null && texture.isAvailable() ? texture.getBitmap() : null;
        if (frame == null) {
            RawNotify.show(R.drawable.msg_info, "Не удалось получить кадр");
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            File out = new File(AndroidUtilities.getCacheDir(), "rawgram_frame.png");
            try (FileOutputStream stream = new FileOutputStream(out)) {
                frame.compress(Bitmap.CompressFormat.PNG, 100, stream);
            } catch (Throwable e) {
                FileLog.e(e);
                return;
            } finally {
                frame.recycle();
            }
            AndroidUtilities.runOnUIThread(() -> RawClipboard.copyImage(context, out, "image/png", ok ->
                    RawNotify.show(ok ? R.drawable.msg_copy : R.drawable.msg_info, ok ? "Кадр скопирован" : "Не удалось скопировать кадр")));
        });
    }

    /** The photo as the account's profile photo, after a confirmation. */
    public static void setAvatar(Context context, int account, File file) {
        if (file == null || !file.exists()) {
            RawNotify.show(R.drawable.msg_download, "Фото ещё не загружено");
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle("Поставить на аватарку")
                .setMessage("Это фото станет фотографией твоего профиля.")
                .setPositiveButton("Поставить", (d, w) -> Utilities.globalQueue.postRunnable(() -> {
                    Bitmap bitmap = ImageLoader.loadBitmap(file.getAbsolutePath(), null, 800, 800, true);
                    if (bitmap == null) {
                        return;
                    }
                    TLRPC.PhotoSize big = ImageLoader.scaleAndSaveImage(bitmap, 800, 800, 80, false, 320, 320);
                    bitmap.recycle();
                    if (big == null || big.location == null) {
                        return;
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        MessagesController.getInstance(account).uploadAndApplyUserAvatar(big.location);
                        RawNotify.show(R.drawable.msg_openprofile, "Аватарка обновляется");
                    });
                }))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    /** QR code on the photo: its text with «Открыть» (links) and «Копировать». */
    public static void scanQr(Context context, File file) {
        if (file == null || !file.exists()) {
            RawNotify.show(R.drawable.msg_download, "Фото ещё не загружено");
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            String text = null;
            Bitmap bitmap = ImageLoader.loadBitmap(file.getAbsolutePath(), null, 1280, 1280, true);
            if (bitmap != null) {
                try {
                    int w = bitmap.getWidth(), h = bitmap.getHeight();
                    int[] pixels = new int[w * h];
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
                    Result result = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, pixels))));
                    text = result != null ? result.getText() : null;
                } catch (Throwable ignore) {
                } finally {
                    bitmap.recycle();
                }
            }
            final String found = text;
            AndroidUtilities.runOnUIThread(() -> {
                if (TextUtils.isEmpty(found)) {
                    RawNotify.show(R.drawable.msg_qrcode, "QR-код не найден");
                    return;
                }
                boolean link = found.startsWith("http://") || found.startsWith("https://") || found.startsWith("tg://");
                AlertDialog.Builder b = new AlertDialog.Builder(context)
                        .setTitle("QR-код")
                        .setMessage(found)
                        .setNegativeButton("Копировать", (d, w) -> {
                            AndroidUtilities.addToClipboard(found);
                            RawNotify.show(R.drawable.msg_copy, "Скопировано");
                        });
                if (link) {
                    b.setPositiveButton("Открыть", (d, w) -> Browser.openUrl(context, found));
                } else {
                    b.setPositiveButton(LocaleController.getString(R.string.OK), null);
                }
                b.show();
            });
        });
    }

    /** A still image file for the avatar / QR items. */
    public static File photo(RawPhotoCopy.Item item) {
        return RawPhotoCopy.isPhoto(item) ? RawPhotoCopy.file(item) : null;
    }
}
