package org.telegram.rawgram;

import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Technical summary of a document (sticker / custom emoji / GIF): real media properties only, nothing guessed. */
public class RawDocInfo {

    /** Document behind a preview: the document itself or the one inside an inline result. */
    public static TLRPC.Document docOf(TLRPC.Document document, TLRPC.BotInlineResult inlineResult) {
        if (document != null) {
            return document;
        }
        return inlineResult != null ? inlineResult.document : null;
    }

    public static void copyId(TLRPC.Document document) {
        if (document == null) {
            return;
        }
        copyId(document.id);
    }

    public static void copyId(long documentId) {
        AndroidUtilities.addToClipboard(String.valueOf(documentId));
        RawNotify.show(R.drawable.msg_copy, "ID скопирован");
    }

    public static boolean isTgs(TLRPC.Document document) {
        return document != null && "application/x-tgsticker".equals(document.mime_type);
    }

    public static String kind(TLRPC.Document document) {
        if (document == null) {
            return "Document";
        }
        if (MessageObject.isAnimatedEmoji(document)) {
            return "Custom emoji";
        }
        if (MessageObject.isStickerDocument(document) || MessageObject.isAnimatedStickerDocument(document, true) || MessageObject.isVideoStickerDocument(document)) {
            return "Sticker";
        }
        if (MessageObject.isGifDocument(document)) {
            return "GIF";
        }
        return "Document";
    }

    /** Sticker set referenced by the sticker / custom emoji attribute, if any. */
    public static TLRPC.InputStickerSet stickerSetOf(TLRPC.Document document) {
        if (document == null || document.attributes == null) {
            return null;
        }
        for (TLRPC.DocumentAttribute a : document.attributes) {
            if ((a instanceof TLRPC.TL_documentAttributeSticker || a instanceof TLRPC.TL_documentAttributeCustomEmoji) && a.stickerset != null && !(a.stickerset instanceof TLRPC.TL_inputStickerSetEmpty)) {
                return a.stickerset;
            }
        }
        return null;
    }

    /** Multi-line summary built from the document and its attributes (sync, no disk access). */
    public static String summary(int account, TLRPC.Document document) {
        if (document == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(kind(document)).append(" · id ").append(document.id).append(" · dc ").append(document.dc_id);
        sb.append('\n').append(TextUtils.isEmpty(document.mime_type) ? "?" : document.mime_type)
            .append(" · ").append(AndroidUtilities.formatFileSize(document.size)).append(" (").append(document.size).append(" B)");

        String alt = null;
        String dims = null;
        String video = null;
        String flags = null;
        for (TLRPC.DocumentAttribute a : document.attributes) {
            if (a instanceof TLRPC.TL_documentAttributeSticker) {
                if (alt == null && !TextUtils.isEmpty(a.alt)) {
                    alt = a.alt;
                }
                if (a.mask) {
                    flags = append(flags, "mask");
                }
            } else if (a instanceof TLRPC.TL_documentAttributeCustomEmoji) {
                TLRPC.TL_documentAttributeCustomEmoji e = (TLRPC.TL_documentAttributeCustomEmoji) a;
                if (!TextUtils.isEmpty(e.alt)) {
                    alt = e.alt;
                }
                flags = append(flags, e.free ? "free" : "premium");
                if (e.text_color) {
                    flags = append(flags, "text_color");
                }
            } else if (a instanceof TLRPC.TL_documentAttributeImageSize) {
                if (dims == null && a.w > 0 && a.h > 0) {
                    dims = a.w + "×" + a.h;
                }
            } else if (a instanceof TLRPC.TL_documentAttributeVideo) {
                if (a.w > 0 && a.h > 0) {
                    dims = a.w + "×" + a.h;
                }
                StringBuilder v = new StringBuilder();
                if (a.duration > 0) {
                    v.append(String.format(Locale.US, "%.3f s", a.duration));
                }
                if (!TextUtils.isEmpty(a.video_codec)) {
                    v.append(v.length() > 0 ? " · " : "").append(a.video_codec);
                }
                if (a.nosound) {
                    v.append(v.length() > 0 ? " · " : "").append("nosound");
                }
                if (v.length() > 0) {
                    video = v.toString();
                }
            } else if (a instanceof TLRPC.TL_documentAttributeAnimated) {
                flags = append(flags, "animated");
            }
        }
        if (dims != null) {
            sb.append(" · ").append(dims);
        }
        if (video != null) {
            sb.append('\n').append("Видео: ").append(video);
        }
        if (alt != null) {
            sb.append('\n').append("Эмодзи: ").append(alt);
        }
        TLRPC.InputStickerSet input = stickerSetOf(document);
        if (input != null) {
            String shortName = input.short_name;
            String title = null;
            TLRPC.TL_messages_stickerSet set = MediaDataController.getInstance(account).getStickerSet(input, true);
            if (set != null && set.set != null) {
                shortName = set.set.short_name;
                title = set.set.title;
            }
            sb.append('\n').append("Набор: ");
            if (!TextUtils.isEmpty(shortName)) {
                sb.append(shortName);
                if (!TextUtils.isEmpty(title)) {
                    sb.append(" (").append(title).append(")");
                }
            } else {
                sb.append("id ").append(input.id);
            }
        }
        if (flags != null) {
            sb.append('\n').append(flags);
        }
        return sb.toString();
    }

    private static String append(String list, String item) {
        return list == null ? item : list + " · " + item;
    }

    public interface Callback {
        void run(String line);
    }

    /**
     * Reads frame info from the downloaded .tgs file on a background queue.
     * Calls back on the UI thread with a line, or not at all if the file is absent / unreadable.
     */
    public static void loadTgsInfo(int account, TLRPC.Document document, Callback callback) {
        if (!isTgs(document) || callback == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            String line = readTgs(account, document);
            if (line != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(line));
            }
        });
    }

    private static String readTgs(int account, TLRPC.Document document) {
        File file = FileLoader.getInstance(account).getPathToAttach(document, true);
        if (file == null || !file.exists()) {
            file = FileLoader.getInstance(account).getPathToAttach(document);
        }
        if (file == null || !file.exists()) {
            return null;
        }
        try (InputStream in = new GZIPInputStream(new FileInputStream(file))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            JSONObject json = new JSONObject(out.toString("UTF-8"));
            StringBuilder sb = new StringBuilder("Lottie:");
            double fr = json.optDouble("fr", 0);
            if (json.has("ip") && json.has("op")) {
                double frames = json.optDouble("op") - json.optDouble("ip");
                sb.append(' ').append(num(frames)).append(" кадров");
                if (fr > 0) {
                    sb.append(" · ").append(num(fr)).append(" fps · ").append(String.format(Locale.US, "%.2f s", frames / fr));
                }
            } else if (fr > 0) {
                sb.append(' ').append(num(fr)).append(" fps");
            }
            if (json.has("w") && json.has("h")) {
                sb.append(" · ").append(json.optInt("w")).append("×").append(json.optInt("h"));
            }
            String v = json.optString("v", "");
            if (!TextUtils.isEmpty(v)) {
                sb.append(" · v").append(v);
            }
            return sb.toString();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static String num(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.US, "%.2f", d);
    }
}
