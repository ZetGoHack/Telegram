package org.telegram.rawgram;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.messenger.video.MediaCodecVideoConvertor;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.RLottieNative;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * «Сохранить в галерею» for stickers: a static sticker becomes a PNG with transparency, an animated (TGS) or video
 * (WebM) sticker an MP4 on a black background — formats the gallery and other apps open. The MP4 is rendered by
 * Telegram's own story exporter (a photo with one sticker entity), so no extra codec is needed.
 */
public final class RawStickerExport {

    private static final int SIZE = 512;
    private static final int BACKGROUND = Color.BLACK;

    private RawStickerExport() {
    }

    public static boolean isSticker(MessageObject message) {
        return message != null && message.isAnyKindOfSticker() && message.getDocument() != null;
    }

    /** 0 — picture (static sticker), 1 — video (animated / video sticker), as MediaController.saveFile types. */
    public static int saveType(MessageObject message) {
        return message.isAnimatedSticker() || message.isVideoSticker() ? 1 : 0;
    }

    /** Converts and saves on a background thread; {@code onSaved} runs like MediaController.saveFile's callback. */
    public static void save(Activity activity, int account, MessageObject message, Utilities.Callback<Uri> onSaved) {
        final TLRPC.Document document = message.getDocument();
        final File source = FileLoader.getInstance(account).getPathToMessage(message.messageOwner);
        final File file = source != null && source.exists() ? source : FileLoader.getInstance(account).getPathToAttach(document, true);
        final boolean lottie = message.isAnimatedSticker();
        final boolean video = message.isVideoSticker();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (file == null || !file.exists()) {
                    AndroidUtilities.runOnUIThread(() -> RawNotify.show(org.telegram.messenger.R.drawable.msg_download, "Файл ещё не загружен"));
                    return;
                }
                File dir = new File(AndroidUtilities.getCacheDir(), "rawgram_stickers");
                dir.mkdirs();
                String base = "sticker_" + document.id;
                if (!lottie && !video) {
                    Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                    if (bitmap == null) {
                        return;
                    }
                    File out = new File(dir, base + ".png");
                    try (FileOutputStream stream = new FileOutputStream(out)) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                    } finally {
                        bitmap.recycle();
                    }
                    MediaController.saveFile(out.getAbsolutePath(), activity, 0, null, null, onSaved);
                    return;
                }
                File out = new File(dir, base + ".mp4");
                if (convert(account, file, lottie, document, out)) {
                    MediaController.saveFile(out.getAbsolutePath(), activity, 1, null, null, onSaved);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static boolean convert(int account, File sticker, boolean lottie, TLRPC.Document document, File out) throws Exception {
        File background = new File(out.getParentFile(), "background.png");
        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(BACKGROUND);
        try (FileOutputStream stream = new FileOutputStream(background)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } finally {
            bitmap.recycle();
        }

        long durationMs = 3000;
        if (lottie) {
            try {
                durationMs = (long) (RLottieNative.getDuration(sticker.getAbsolutePath(), null) * 1000L);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        } else {
            durationMs = (long) (MessageObject.getDocumentDuration(document) * 1000L);
        }
        if (durationMs <= 0) {
            durationMs = 3000;
        }

        VideoEditedInfo.MediaEntity entity = new VideoEditedInfo.MediaEntity();
        entity.type = VideoEditedInfo.MediaEntity.TYPE_STICKER;
        entity.subType |= lottie ? 1 : 4;
        entity.text = sticker.getAbsolutePath();
        entity.document = document;
        // keep the sticker's own proportions: fit into the square, centred
        float[] fit = fit(document);
        entity.width = fit[0];
        entity.height = fit[1];
        entity.x = (1f - entity.width) / 2f;
        entity.y = (1f - entity.height) / 2f;
        entity.viewWidth = SIZE;
        entity.viewHeight = SIZE;

        VideoEditedInfo info = new VideoEditedInfo();
        info.isPhoto = true;
        info.muted = true;
        info.account = account;
        info.mediaEntities = new ArrayList<>();
        info.mediaEntities.add(entity);

        if (out.exists()) {
            out.delete();
        }
        MediaCodecVideoConvertor.ConvertVideoParams params = MediaCodecVideoConvertor.ConvertVideoParams.of(
                background.getAbsolutePath(), out, 0,
                0, false,
                SIZE, SIZE,
                SIZE, SIZE,
                30, -1, -1,
                -1, -1, -1,
                true, durationMs,
                new MediaController.VideoConvertorListener() {
                    @Override
                    public boolean checkConversionCanceled() {
                        return false;
                    }

                    @Override
                    public void didWriteData(long availableSize, float progress) {
                    }
                },
                info);
        boolean error = new MediaCodecVideoConvertor().convertVideo(params);
        return !error && out.exists() && out.length() > 0;
    }

    /** Width and height of the sticker inside the square frame, as fractions of its side. */
    private static float[] fit(TLRPC.Document document) {
        int w = 0, h = 0;
        if (document != null && document.attributes != null) {
            for (TLRPC.DocumentAttribute attribute : document.attributes) {
                if ((attribute instanceof TLRPC.TL_documentAttributeVideo || attribute instanceof TLRPC.TL_documentAttributeImageSize)
                        && attribute.w > 0 && attribute.h > 0) {
                    w = attribute.w;
                    h = attribute.h;
                    break;
                }
            }
        }
        if (w <= 0 || h <= 0 || w == h) {
            return new float[]{1f, 1f};
        }
        return w > h ? new float[]{1f, h / (float) w} : new float[]{w / (float) h, 1f};
    }
}
