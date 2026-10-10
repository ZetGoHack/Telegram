package org.telegram.rawgram;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.StickersAlert;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Files inside raw objects: the photo, video, document, sticker, voice or audio a TL object carries, and what can be
 * done with it — open, download (with progress), save to the gallery / Downloads / Music, share, copy the path.
 * Shared by the «Медиа» tab of {@link RawObjectSheet} ({@link Panel}) and the long-press menu of media nodes in
 * «Дерево» ({@link #showNodeMenu}).
 * <p>
 * Downloads need a parent object that can refresh an expired file_reference (the message, web page, sticker set,
 * user, story…): it is the nearest such object above the node, or what the sheet's caller passed. Without one only a
 * file that is already in the cache is offered.
 */
public final class RawMediaActions {

    private RawMediaActions() {
    }

    private static int fakeMessageId;

    // ---- what a node stands for ----

    /** One file found in a raw object. */
    public static final class Ref {
        final int account;
        /** The photo this file belongs to, or null. */
        final TLRPC.Photo photo;
        /** The document this file is (or whose thumbnail it is), or null. */
        final TLRPC.Document document;
        /** The photo size or document thumbnail to load; null = the whole document. */
        final TLRPC.PhotoSize size;
        /** Refreshes an expired file_reference; null = only a file already in the cache can be used. */
        Object parent;
        /** The real message whose own media this is: photo viewer, playback and saving like in the chat. */
        MessageObject message;
        /** Paid media not bought yet: only a blurred preview, no file. */
        TLRPC.TL_messageExtendedMediaPreview preview;
        /** Price of the paid media this item belongs to, in Stars; 0 = not paid media. */
        long stars;

        Ref(int account, TLRPC.Photo photo, TLRPC.Document document, TLRPC.PhotoSize size) {
            this.account = account;
            this.photo = photo;
            this.document = document;
            this.size = size;
        }

        boolean isPreview() {
            return preview != null;
        }

        TLObject attach() {
            return size != null ? size : document;
        }

        /** The photo or document as a whole, for the type label. */
        TLObject owner() {
            return photo != null ? photo : document;
        }

        String fileName() {
            TLObject attach = attach();
            return attach != null ? FileLoader.getAttachFileName(attach) : "";
        }

        boolean isThumb() {
            return document != null && size != null;
        }

        boolean isWholeDocument() {
            return document != null && size == null;
        }

        boolean canDownload() {
            return parent != null && !TextUtils.isEmpty(fileName());
        }

        /** The downloaded file, or null. */
        File file() {
            try {
                if (message != null) {
                    String path = RawMessageDetails.galleryPath(account, message);
                    if (path != null) {
                        return new File(path);
                    }
                }
                TLObject attach = attach();
                if (attach == null) {
                    return null;
                }
                FileLoader loader = FileLoader.getInstance(account);
                File f = loader.getPathToAttach(attach, false);
                if (f != null && f.exists() && f.length() > 0) {
                    return f;
                }
                f = loader.getPathToAttach(attach, true);
                if (f != null && f.exists() && f.length() > 0) {
                    return f;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            return null;
        }

        boolean isLoading() {
            String name = fileName();
            return !TextUtils.isEmpty(name) && FileLoader.getInstance(account).isLoadingFile(name);
        }

        /** {loaded, total} of a running download, or null. */
        long[] progress() {
            String name = fileName();
            return TextUtils.isEmpty(name) ? null : ImageLoader.getInstance().getFileProgressSizes(name);
        }

        String mime() {
            if (isPreview()) {
                return "";
            }
            if (isWholeDocument()) {
                return document.mime_type != null ? document.mime_type.toLowerCase(Locale.ROOT) : "";
            }
            return "image/jpeg";
        }

        long expectedSize() {
            return size != null ? size.size : document != null ? document.size : 0;
        }

        int dcId() {
            return photo != null ? photo.dc_id : document != null ? document.dc_id : 0;
        }

        String displayName() {
            if (isWholeDocument()) {
                String name = FileLoader.getDocumentFileName(document);
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
            }
            return fileName();
        }

        boolean isSticker() {
            return isWholeDocument() && RawStickerExport.isSticker(document);
        }

        boolean isVideoLike() {
            return isWholeDocument() && !isSticker() && (MessageObject.isVideoDocument(document) || MessageObject.isGifDocument(document)
                    || MessageObject.isRoundVideoDocument(document));
        }

        boolean isAudio() {
            return isWholeDocument() && (MessageObject.isVoiceDocument(document) || MessageObject.isMusicDocument(document)
                    || mime().startsWith("audio/"));
        }

        /** Opens in Telegram's photo viewer: photos and videos (a thumbnail or a single photo size opens as an image file). */
        boolean opensInViewer() {
            return photo != null && size == mainSize(photo) || isVideoLike();
        }

        String kind() {
            if (isPreview()) {
                return preview.video_duration > 0 ? "Платное видео" : "Платное фото";
            }
            if (photo != null) {
                return size == mainSize(photo) ? "Фото" : "Фото, размер " + size.type;
            }
            if (size != null) {
                return "Миниатюра, размер " + size.type;
            }
            if (isCustomEmoji(document)) return "Эмодзи";
            if (MessageObject.isVideoStickerDocument(document)) return "Видеостикер";
            if (MessageObject.isAnimatedStickerDocument(document, true)) return "Анимированный стикер";
            if (MessageObject.isStickerDocument(document)) return "Стикер";
            if (MessageObject.isRoundVideoDocument(document)) return "Видеосообщение";
            if (MessageObject.isGifDocument(document)) return "GIF";
            if (MessageObject.isVideoDocument(document)) return "Видео";
            if (MessageObject.isVoiceDocument(document)) return "Голосовое";
            if (MessageObject.isMusicDocument(document) || mime().startsWith("audio/")) return "Аудио";
            if (mime().startsWith("image/")) return "Изображение";
            return "Файл";
        }

        /** MediaController.saveFile type: 0 pictures, 1 movies, 2 downloads, 3 music. */
        int saveType() {
            if (message != null) {
                return RawMessageDetails.saveType(message);
            }
            if (!isWholeDocument()) return 0;
            if (isSticker()) return RawStickerExport.saveType(document);
            String mime = mime();
            if (mime.startsWith("image/")) return 0;
            if (isVideoLike() || mime.startsWith("video/")) return 1;
            if (isAudio()) return 3;
            return 2;
        }

        String saveLabel() {
            return RawMessageDetails.saveLabel(saveType());
        }

        int[] dimensions() {
            if (isPreview()) {
                return new int[]{preview.w, preview.h};
            }
            if (size != null) {
                return new int[]{size.w, size.h};
            }
            if (document == null) {
                return new int[]{0, 0};
            }
            for (TLRPC.DocumentAttribute a : document.attributes) {
                if ((a instanceof TLRPC.TL_documentAttributeVideo || a instanceof TLRPC.TL_documentAttributeImageSize) && a.w > 0 && a.h > 0) {
                    return new int[]{a.w, a.h};
                }
            }
            return new int[]{0, 0};
        }

        /** "Тип: Фото", "Размер: …" — only what is known. */
        ArrayList<String[]> info(boolean withPath) {
            ArrayList<String[]> out = new ArrayList<>();
            out.add(new String[]{"Тип", kind()});
            if (isPreview()) {
                int[] wh = dimensions();
                if (wh[0] > 0 && wh[1] > 0) out.add(new String[]{"Разрешение", wh[0] + "x" + wh[1]});
                if (preview.video_duration > 0) out.add(new String[]{"Длительность", RawMessageDetails.duration(preview.video_duration)});
                if (stars > 0) out.add(new String[]{"Цена", stars + " ⭐"});
                out.add(new String[]{"Куплено", "нет"});
                return out;
            }
            if (expectedSize() > 0) out.add(new String[]{"Размер", RawMessageDetails.size(expectedSize())});
            out.add(new String[]{"MIME", mime()});
            if (dcId() > 0) out.add(new String[]{"DC", RawMessageDetails.dc(dcId())});
            int[] wh = dimensions();
            if (wh[0] > 0 && wh[1] > 0) out.add(new String[]{"Разрешение", wh[0] + "x" + wh[1]});
            if (isWholeDocument()) {
                double duration = MessageObject.getDocumentDuration(document);
                if (duration > 0) out.add(new String[]{"Длительность", RawMessageDetails.duration(duration)});
            }
            out.add(new String[]{"Имя файла", displayName()});
            File file = file();
            String state;
            if (file != null) {
                state = "да";
            } else if (isLoading()) {
                long[] p = progress();
                state = p != null && p[1] > 0
                        ? percent(p) + "%  (" + AndroidUtilities.formatFileSize(p[0]) + " из " + AndroidUtilities.formatFileSize(p[1]) + ")"
                        : "идёт загрузка";
            } else {
                state = "нет";
            }
            out.add(new String[]{"Загружено", state});
            if (stars > 0) out.add(new String[]{"Цена", stars + " ⭐, куплено"});
            if (withPath && file != null) out.add(new String[]{"Путь", file.getAbsolutePath()});
            return out;
        }

        /** One line for a compact album card: size, dimensions, duration and the file state (or the price). */
        String shortLine() {
            ArrayList<String> parts = new ArrayList<>();
            int[] wh = dimensions();
            if (wh[0] > 0 && wh[1] > 0) parts.add(wh[0] + "x" + wh[1]);
            if (isPreview()) {
                if (preview.video_duration > 0) parts.add(RawMessageDetails.duration(preview.video_duration));
                parts.add(stars > 0 ? "не куплено · " + stars + " ⭐" : "не куплено");
                return TextUtils.join(" · ", parts);
            }
            if (expectedSize() > 0) parts.add(0, AndroidUtilities.formatFileSize(expectedSize()));
            if (isWholeDocument()) {
                double duration = MessageObject.getDocumentDuration(document);
                if (duration > 0) parts.add(RawMessageDetails.duration(duration));
            }
            if (file() != null) {
                parts.add("в кеше");
            } else if (isLoading()) {
                long[] p = progress();
                parts.add(p != null && p[1] > 0 ? "загрузка " + percent(p) + "%" : "загрузка");
            } else {
                parts.add("не в кеше");
            }
            return TextUtils.join(" · ", parts);
        }

        MessageObject viewerMessage(File file) {
            return message != null ? message : fakeMessage(this, file);
        }
    }

    private static int percent(long[] p) {
        return p == null || p[1] <= 0 ? 0 : (int) Math.min(100, p[0] * 100 / p[1]);
    }

    private static boolean isCustomEmoji(TLRPC.Document document) {
        for (TLRPC.DocumentAttribute a : document.attributes) {
            if (a instanceof TLRPC.TL_documentAttributeCustomEmoji) {
                return true;
            }
        }
        return false;
    }

    /** The size the chat loads and saves for a photo (same pick as FileLoader.getPathToMessage). */
    static TLRPC.PhotoSize mainSize(TLRPC.Photo photo) {
        return photo == null || photo.sizes == null ? null
                : FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
    }

    private static boolean hasFile(TLRPC.PhotoSize size) {
        return size != null && !(size instanceof TLRPC.TL_photoStrippedSize) && !(size instanceof TLRPC.TL_photoPathSize)
                && !(size instanceof TLRPC.TL_photoSizeEmpty) && !TextUtils.isEmpty(FileLoader.getAttachFileName(size));
    }

    /**
     * The file {@code source} stands for, with its download parent; null when it carries none. {@code ancestors} are
     * the objects above it in the raw tree, nearest first. {@code node}: only media nodes themselves (MessageMedia,
     * WebPage, game, Photo, Document, PhotoSize), not the objects that merely contain them (Message, inline result).
     */
    public static Ref find(int account, Object source, List<Object> ancestors, Object sheetParent, MessageObject sheetMessage, boolean node) {
        Ref ref;
        try {
            ref = resolve(account, source, ancestors, node);
            if (ref == null) {
                return null;
            }
            ArrayList<Object> chain = new ArrayList<>();
            chain.add(source);
            if (ancestors != null) {
                chain.addAll(ancestors);
            }
            attachContext(ref, chain, sheetParent, sheetMessage);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
        return ref;
    }

    /**
     * Every file of the sheet's main object, in order: the items of paid media (bought ones and previews of the
     * rest), a transaction's media, or the messages of an album ({@code group}, when the caller knows it).
     * Empty when the object carries no media.
     */
    public static ArrayList<Ref> findAll(int account, Object root, Object sheetParent, MessageObject sheetMessage, List<MessageObject> group) {
        ArrayList<Ref> out = new ArrayList<>();
        try {
            if (group != null && group.size() > 1) {
                for (MessageObject message : group) {
                    for (Ref ref : itemsOf(account, MessageObject.getMedia(message.messageOwner))) {
                        ArrayList<Object> chain = new ArrayList<>();
                        chain.add(message.messageOwner);
                        attachContext(ref, chain, null, message);
                        out.add(ref);
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            }
            TLRPC.MessageMedia media = null;
            if (root instanceof MessageObject) media = MessageObject.getMedia(((MessageObject) root).messageOwner);
            else if (root instanceof TLRPC.Message) media = MessageObject.getMedia((TLRPC.Message) root);
            else if (root instanceof TLRPC.TL_messageMediaPaidMedia) media = (TLRPC.MessageMedia) root;
            ArrayList<Ref> items = new ArrayList<>();
            if (media instanceof TLRPC.TL_messageMediaPaidMedia) {
                items.addAll(itemsOf(account, media));
            } else if (root instanceof org.telegram.tgnet.tl.TL_stars.StarsTransaction) {
                for (TLRPC.MessageMedia m : ((org.telegram.tgnet.tl.TL_stars.StarsTransaction) root).extended_media) {
                    items.addAll(itemsOf(account, m));
                }
            }
            if (items.isEmpty()) {
                Ref single = find(account, root, null, sheetParent, sheetMessage, false);
                if (single != null) {
                    out.add(single);
                }
                return out;
            }
            ArrayList<Object> chain = new ArrayList<>();
            chain.add(root);
            for (Ref ref : items) {
                if (!ref.isPreview()) {
                    attachContext(ref, chain, sheetParent, sheetMessage);
                }
                out.add(ref);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return out;
    }

    /** The files of one media: each item of paid media (previews included), otherwise the single file. */
    private static ArrayList<Ref> itemsOf(int account, TLRPC.MessageMedia media) {
        ArrayList<Ref> out = new ArrayList<>();
        if (media instanceof TLRPC.TL_messageMediaPaidMedia) {
            for (TLRPC.MessageExtendedMedia extended : media.extended_media) {
                Ref ref = null;
                if (extended instanceof TLRPC.TL_messageExtendedMedia) {
                    ref = fromMedia(account, ((TLRPC.TL_messageExtendedMedia) extended).media);
                } else if (extended instanceof TLRPC.TL_messageExtendedMediaPreview) {
                    ref = new Ref(account, null, null, null);
                    ref.preview = (TLRPC.TL_messageExtendedMediaPreview) extended;
                }
                if (ref != null) {
                    ref.stars = media.stars_amount;
                    out.add(ref);
                }
            }
        } else {
            Ref ref = fromMedia(account, media);
            if (ref != null) {
                out.add(ref);
            }
        }
        return out;
    }

    private static Ref resolve(int account, Object source, List<Object> ancestors, boolean node) {
        if (source instanceof TLRPC.MessageMedia) return fromMedia(account, (TLRPC.MessageMedia) source);
        if (source instanceof TLRPC.TL_messageExtendedMedia) return fromMedia(account, ((TLRPC.TL_messageExtendedMedia) source).media);
        if (source instanceof TLRPC.WebPage) return fromWebPage(account, (TLRPC.WebPage) source);
        if (source instanceof TLRPC.TL_game) return fromGame(account, (TLRPC.TL_game) source);
        if (source instanceof TLRPC.Photo) return fromPhoto(account, (TLRPC.Photo) source);
        if (source instanceof TLRPC.Document) return fromDocument(account, (TLRPC.Document) source);
        if (source instanceof TLRPC.PhotoSize) {
            TLRPC.PhotoSize size = (TLRPC.PhotoSize) source;
            if (!hasFile(size) || ancestors == null) {
                return null;
            }
            for (Object a : ancestors) {
                if (a instanceof TLRPC.Photo) {
                    return new Ref(account, (TLRPC.Photo) a, null, size);
                } else if (a instanceof TLRPC.Document) {
                    return new Ref(account, null, (TLRPC.Document) a, size);
                } else if (a instanceof TLObject && !(a instanceof TLRPC.PhotoSize)) {
                    return null; // a size that belongs to something else (a user's photo location, a set thumb)
                }
            }
            return null;
        }
        if (node) {
            return null;
        }
        if (source instanceof MessageObject) return fromMedia(account, MessageObject.getMedia(((MessageObject) source).messageOwner));
        if (source instanceof TLRPC.Message) return fromMedia(account, MessageObject.getMedia((TLRPC.Message) source));
        if (source instanceof TL_stories.StoryItem) return fromMedia(account, ((TL_stories.StoryItem) source).media);
        if (source instanceof org.telegram.tgnet.tl.TL_stars.StarsTransaction) {
            // paid media bought or sold: the transaction keeps the media itself
            for (TLRPC.MessageMedia media : ((org.telegram.tgnet.tl.TL_stars.StarsTransaction) source).extended_media) {
                Ref ref = fromMedia(account, media);
                if (ref != null) {
                    return ref;
                }
            }
            return null;
        }
        if (source instanceof TLRPC.BotInlineResult) {
            TLRPC.BotInlineResult result = (TLRPC.BotInlineResult) source;
            Ref ref = fromDocument(account, result.document);
            return ref != null ? ref : fromPhoto(account, result.photo);
        }
        return null;
    }

    private static Ref fromMedia(int account, TLRPC.MessageMedia media) {
        if (media == null) {
            return null;
        }
        if (media instanceof TLRPC.TL_messageMediaWebPage) {
            return fromWebPage(account, media.webpage);
        }
        if (media instanceof TLRPC.TL_messageMediaStory) {
            return media.storyItem != null ? fromMedia(account, media.storyItem.media) : null;
        }
        if (media instanceof TLRPC.TL_messageMediaGame) {
            return fromGame(account, media.game);
        }
        if (media instanceof TLRPC.TL_messageMediaPaidMedia) {
            for (TLRPC.MessageExtendedMedia extended : media.extended_media) {
                if (extended instanceof TLRPC.TL_messageExtendedMedia) {
                    Ref ref = fromMedia(account, ((TLRPC.TL_messageExtendedMedia) extended).media);
                    if (ref != null) {
                        return ref;
                    }
                }
            }
            return null;
        }
        Ref ref = fromDocument(account, media.document);
        return ref != null ? ref : fromPhoto(account, media.photo);
    }

    private static Ref fromWebPage(int account, TLRPC.WebPage webPage) {
        if (webPage == null) {
            return null;
        }
        Ref ref = fromDocument(account, webPage.document);
        return ref != null ? ref : fromPhoto(account, webPage.photo);
    }

    private static Ref fromGame(int account, TLRPC.TL_game game) {
        if (game == null) {
            return null;
        }
        Ref ref = fromDocument(account, game.document);
        return ref != null ? ref : fromPhoto(account, game.photo);
    }

    private static Ref fromPhoto(int account, TLRPC.Photo photo) {
        if (photo == null || photo instanceof TLRPC.TL_photoEmpty) {
            return null;
        }
        TLRPC.PhotoSize size = mainSize(photo);
        return hasFile(size) ? new Ref(account, photo, null, size) : null;
    }

    private static Ref fromDocument(int account, TLRPC.Document document) {
        if (document == null || document instanceof TLRPC.TL_documentEmpty || document.id == 0) {
            return null;
        }
        return new Ref(account, null, document, null);
    }

    /** Finds the download parent and the real message (if the media is a message's own) along the node's path. */
    private static void attachContext(Ref ref, List<Object> chain, Object sheetParent, MessageObject sheetMessage) {
        for (Object o : chain) {
            MessageObject message = null;
            if (sheetMessage != null && (o == sheetMessage || o == sheetMessage.messageOwner)) {
                message = sheetMessage;
            } else if (o instanceof MessageObject) {
                message = (MessageObject) o;
            } else if (o instanceof TLRPC.Message && !(o instanceof TLRPC.TL_messageEmpty)) {
                // a message inside a log or a response: a MessageObject refreshes file references, a bare Message can't
                try {
                    message = new MessageObject(ref.account, (TLRPC.Message) o, false, false);
                } catch (Throwable e) {
                    FileLog.e(e);
                    ref.parent = o;
                    return;
                }
            }
            if (message != null) {
                ref.parent = message;
                if (isOwnMedia(ref, message.messageOwner)) {
                    ref.message = message;
                }
                return;
            }
            Object parent = parentOf(ref.account, o);
            if (parent != null) {
                ref.parent = parent;
                return;
            }
        }
        if (ref.document != null) {
            ref.parent = stickerSetOf(ref.document);
        }
        if (ref.parent == null) {
            ref.parent = sheetParent;
        }
    }

    /** The message shows exactly this file as its photo / video / document (not a thumbnail or another size). */
    private static boolean isOwnMedia(Ref ref, TLRPC.Message message) {
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (!(media instanceof TLRPC.TL_messageMediaPhoto || media instanceof TLRPC.TL_messageMediaDocument
                || media instanceof TLRPC.TL_messageMediaWebPage)) {
            return false;
        }
        Ref own = fromMedia(ref.account, media);
        if (own == null) {
            return false;
        }
        return ref.photo != null ? own.photo == ref.photo && own.size == ref.size : own.document == ref.document && ref.size == null;
    }

    /** Objects Telegram itself passes as the parent of their files (FileRefController can refresh through them). */
    private static Object parentOf(int account, Object o) {
        if (o instanceof TLRPC.WebPage || o instanceof TLRPC.BotInlineResult || o instanceof TLRPC.User || o instanceof TLRPC.Chat
                || o instanceof TLRPC.TL_wallPaper || o instanceof TLRPC.TL_theme || o instanceof TL_bots.BotInfo
                || o instanceof TLRPC.TL_availableReaction || o instanceof TLRPC.TL_attachMenuBot || o instanceof TLRPC.TL_help_premiumPromo) {
            return o;
        }
        if (o instanceof TL_stories.StoryItem) {
            return ((TL_stories.StoryItem) o).dialogId != 0 ? o : null;
        }
        if (o instanceof org.telegram.tgnet.tl.TL_stars.StarsTransaction) {
            return o; // downloads with the file reference the transaction came with
        }
        if (o instanceof TLRPC.TL_messages_stickerSet) {
            return ((TLRPC.TL_messages_stickerSet) o).set != null ? o : null;
        }
        if (o instanceof TLRPC.StickerSetCovered) {
            return ((TLRPC.StickerSetCovered) o).set != null ? o : null;
        }
        if (o instanceof TLRPC.InputStickerSet) {
            return o instanceof TLRPC.TL_inputStickerSetEmpty ? null : o;
        }
        if (o instanceof TLRPC.UserFull) {
            return MessagesController.getInstance(account).getUser(((TLRPC.UserFull) o).id);
        }
        if (o instanceof TLRPC.ChatFull) {
            return MessagesController.getInstance(account).getChat(((TLRPC.ChatFull) o).id);
        }
        return null;
    }

    private static TLRPC.InputStickerSet stickerSetOf(TLRPC.Document document) {
        for (TLRPC.DocumentAttribute a : document.attributes) {
            if ((a instanceof TLRPC.TL_documentAttributeSticker || a instanceof TLRPC.TL_documentAttributeCustomEmoji)
                    && a.stickerset != null && !(a.stickerset instanceof TLRPC.TL_inputStickerSetEmpty)) {
                return a.stickerset;
            }
        }
        return null;
    }

    // ---- actions ----

    private static Activity activity() {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        return fragment != null ? fragment.getParentActivity() : null;
    }

    private static void notify(BottomSheet host, int icon, String text) {
        RawNotify.show(host, icon, text);
    }

    public static void download(Ref ref) {
        if (!ref.canDownload()) {
            return;
        }
        FileLoader loader = FileLoader.getInstance(ref.account);
        if (ref.size != null) {
            ImageLocation location = ref.photo != null ? ImageLocation.getForPhoto(ref.size, ref.photo) : ImageLocation.getForDocument(ref.size, ref.document);
            if (location != null) {
                loader.loadFile(location, ref.parent, "jpg", FileLoader.PRIORITY_HIGH, 0);
            }
        } else {
            loader.loadFile(ref.document, ref.parent, FileLoader.PRIORITY_HIGH, 0);
        }
    }

    public static void cancelDownload(Ref ref) {
        FileLoader loader = FileLoader.getInstance(ref.account);
        if (ref.size != null) {
            loader.cancelLoadFile(ref.size);
        } else if (ref.document != null) {
            loader.cancelLoadFile(ref.document);
        }
    }

    /** Downloads, then runs {@code then} once the file is there (nothing if the download fails or is cancelled). */
    private static void downloadThen(BottomSheet host, Ref ref, Runnable then) {
        final String name = ref.fileName();
        final NotificationCenter center = NotificationCenter.getInstance(ref.account);
        final NotificationCenter.NotificationCenterDelegate[] observer = new NotificationCenter.NotificationCenterDelegate[1];
        observer[0] = (id, account, args) -> {
            if (args.length == 0 || !name.equals(args[0])) {
                return;
            }
            center.removeObserver(observer[0], NotificationCenter.fileLoaded);
            center.removeObserver(observer[0], NotificationCenter.fileLoadFailed);
            if (id == NotificationCenter.fileLoaded) {
                then.run();
            } else if (!(args.length > 1 && args[1] instanceof Integer && (Integer) args[1] == 1)) {
                notify(host, R.drawable.msg_warning, "Не удалось загрузить файл");
            }
        };
        center.addObserver(observer[0], NotificationCenter.fileLoaded);
        center.addObserver(observer[0], NotificationCenter.fileLoadFailed);
        download(ref);
        notify(host, R.drawable.msg_download, "Загрузка…");
    }

    public static void open(BottomSheet host, Ref ref, Theme.ResourcesProvider rp) {
        Activity activity = activity();
        if (activity == null) {
            return;
        }
        try {
            if (ref.isSticker()) {
                TLRPC.InputStickerSet set = stickerSetOf(ref.document);
                if (set != null) {
                    BaseFragment fragment = LaunchActivity.getSafeLastFragment();
                    if (isCustomEmoji(ref.document)) {
                        ArrayList<TLRPC.InputStickerSet> sets = new ArrayList<>();
                        sets.add(set);
                        new org.telegram.ui.Components.EmojiPacksAlert(fragment, activity, rp, sets).show();
                    } else {
                        new StickersAlert(activity, fragment, set, null, null, rp, false).show();
                    }
                    return;
                }
            }
            File file = ref.file();
            if (ref.opensInViewer()) {
                if (ref.message != null || file != null) {
                    openViewer(ref.viewerMessage(file), rp);
                    return;
                }
            } else if (ref.message != null && ref.isAudio()) {
                MediaController.getInstance().playMessage(ref.message);
                return;
            } else if (file != null) {
                if (!AndroidUtilities.openForView(file, ref.displayName(), ref.mime(), activity, rp, false)) {
                    notify(host, R.drawable.msg_warning, "Нет приложения, чтобы открыть файл");
                }
                return;
            }
            if (ref.canDownload()) {
                downloadThen(host, ref, () -> open(host, ref, rp));
            } else {
                notify(host, R.drawable.msg_download, "Файла нет в кеше");
            }
        } catch (Throwable e) {
            FileLog.e(e);
            notify(host, R.drawable.msg_warning, "Не удалось открыть: " + e.getMessage());
        }
    }

    private static void openViewer(MessageObject message, Theme.ResourcesProvider rp) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null || message == null) {
            return;
        }
        PhotoViewer.getInstance().setParentActivity(fragment, rp);
        PhotoViewer.getInstance().openPhoto(message, 0, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider(), true);
    }

    /** A local, never-sent message carrying the file, so the photo viewer can show media found outside a message. */
    private static MessageObject fakeMessage(Ref ref, File file) {
        long selfId = UserConfig.getInstance(ref.account).getClientUserId();
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = 0x70000000 + ++fakeMessageId;
        message.date = (int) (System.currentTimeMillis() / 1000);
        message.dialog_id = selfId;
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = selfId;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = selfId;
        message.message = "";
        if (ref.photo != null) {
            TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
            media.photo = ref.photo;
            media.flags |= 1;
            message.media = media;
        } else {
            TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
            media.document = ref.document;
            media.flags |= 1;
            message.media = media;
        }
        message.flags |= 512;
        if (file != null) {
            message.attachPath = file.getAbsolutePath();
        }
        return new MessageObject(ref.account, message, false, false);
    }

    public static void save(BottomSheet host, Ref ref) {
        Activity activity = activity();
        if (activity == null) {
            return;
        }
        final int saveType = ref.saveType();
        Utilities.Callback<Uri> onSaved = uri -> AndroidUtilities.runOnUIThread(() -> notify(host,
                saveType >= 2 ? R.drawable.msg_download : R.drawable.msg_gallery,
                saveType == 2 ? "Сохранено в загрузки" : saveType == 3 ? "Сохранено в музыку" : "Сохранено в галерею"));
        try {
            if (ref.message != null && RawMessageDetails.saveMessageFile(activity, ref.account, ref.message, onSaved)) {
                return;
            }
            File file = ref.file();
            if (file == null) {
                if (ref.canDownload()) {
                    downloadThen(host, ref, () -> save(host, ref));
                } else {
                    notify(host, R.drawable.msg_download, "Файла нет в кеше");
                }
                return;
            }
            if (RawMessageDetails.requestStoragePermission(activity)) {
                return;
            }
            if (ref.isSticker()) {
                RawStickerExport.save(activity, ref.account, ref.document, file, onSaved);
                return;
            }
            String name = null;
            String mime = null;
            if (saveType >= 2) {
                name = ref.displayName();
                if (TextUtils.isEmpty(name)) {
                    name = file.getName();
                }
                mime = ref.mime();
            }
            MediaController.saveFile(file.getAbsolutePath(), activity, saveType, name, mime, onSaved);
        } catch (Throwable e) {
            FileLog.e(e);
            notify(host, R.drawable.msg_warning, "Не удалось сохранить: " + e.getMessage());
        }
    }

    public static void share(BottomSheet host, Ref ref) {
        Activity activity = activity();
        File file = ref.file();
        if (activity == null || file == null) {
            notify(host, R.drawable.msg_download, "Файла нет в кеше");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            String mime = ref.mime();
            intent.setType(TextUtils.isEmpty(mime) ? "*/*" : mime);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, "Поделиться"));
        } catch (Throwable e) {
            FileLog.e(e);
            notify(host, R.drawable.msg_warning, "Не удалось поделиться: " + e.getMessage());
        }
    }

    public static void copyPath(BottomSheet host, Ref ref) {
        File file = ref.file();
        if (file == null) {
            notify(host, R.drawable.msg_download, "Файла нет в кеше");
            return;
        }
        AndroidUtilities.addToClipboard(file.getAbsolutePath());
        notify(host, R.drawable.msg_copy, "Путь скопирован");
    }

    // ---- «Дерево»: long press on a media node ----

    /**
     * Shows «Скопировать JSON / Открыть / Сохранить / Скачать» for a media node. False (the caller copies right away)
     * when the node isn't media, or its file is neither in the cache nor downloadable.
     */
    public static boolean showNodeMenu(BottomSheet sheet, View anchor, int account, Object source, List<Object> ancestors,
                                       Object sheetParent, MessageObject sheetMessage, Theme.ResourcesProvider rp, Runnable copyJson) {
        Ref ref = find(account, source, ancestors, sheetParent, sheetMessage, true);
        if (ref == null || sheet.container == null) {
            return false;
        }
        boolean cached = ref.file() != null;
        boolean downloadable = ref.canDownload();
        boolean viewable = ref.message != null && (ref.opensInViewer() || ref.isAudio()) || ref.isSticker() && stickerSetOf(ref.document) != null;
        if (!cached && !downloadable && !viewable) {
            return false;
        }
        boolean loading = !cached && ref.isLoading();
        ItemOptions.makeOptions(sheet.container, rp, anchor)
                .add(R.drawable.msg_copy, "Скопировать JSON", copyJson)
                .add(R.drawable.msg_openin, "Открыть", () -> open(sheet, ref, rp))
                .addIf(cached || downloadable, ref.saveType() >= 2 ? R.drawable.msg_download : R.drawable.msg_gallery, ref.saveLabel(), () -> save(sheet, ref))
                .addIf(!cached && downloadable && !loading, R.drawable.msg_download, "Скачать", () -> {
                    download(ref);
                    notify(sheet, R.drawable.msg_download, "Загрузка…");
                })
                .addIf(loading, R.drawable.msg_cancel, "Отменить загрузку", () -> cancelDownload(ref))
                .setDrawScrim(false)
                .show();
        return true;
    }

    // ---- «Медиа» tab ----

    /**
     * Contents of the «Медиа» tab: one card per file (preview, info, that file's action chips), with an «N медиа»
     * header for albums and paid media with several items. Follows downloads while attached.
     */
    static final class Panel extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {

        interface ChipFactory {
            TextView create(String text, View.OnClickListener listener);
        }

        private final BottomSheet sheet;
        private final Theme.ResourcesProvider rp;
        private final ChipFactory chips;
        private final TextView headerView;
        private final LinearLayout cardsLayout;
        private final ArrayList<Card> cards = new ArrayList<>();
        private int account = -1;
        private int observingAccount = -1;

        Panel(Context context, BottomSheet sheet, Theme.ResourcesProvider rp, ChipFactory chips) {
            super(context);
            this.sheet = sheet;
            this.rp = rp;
            this.chips = chips;
            setOrientation(VERTICAL);
            headerView = new TextView(context);
            headerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            headerView.setTypeface(AndroidUtilities.bold());
            headerView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, rp));
            headerView.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), 0);
            addView(headerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            cardsLayout = new LinearLayout(context);
            cardsLayout.setOrientation(VERTICAL);
            addView(cardsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        void bind(List<Ref> refs) {
            cardsLayout.removeAllViews();
            cards.clear();
            boolean list = refs.size() > 1;
            headerView.setText(refs.size() + " медиа");
            headerView.setVisibility(list ? VISIBLE : GONE);
            int divider = Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack, rp), 0.08f);
            for (int i = 0; i < refs.size(); i++) {
                if (list && i > 0) {
                    View line = new View(getContext());
                    line.setBackgroundColor(divider);
                    cardsLayout.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 12, 0, 12, 0));
                }
                Card card = new Card(getContext(), refs.get(i), list ? i + 1 : 0);
                cards.add(card);
                cardsLayout.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            account = refs.isEmpty() ? -1 : refs.get(0).account;
            observe();
        }

        /** "Тип: …" lines as plain text, for the copy button; albums numbered. */
        String infoText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cards.size(); i++) {
                if (sb.length() > 0) sb.append("\n\n");
                if (cards.size() > 1) sb.append('#').append(i + 1).append('\n');
                StringBuilder item = new StringBuilder();
                for (String[] line : cards.get(i).ref.info(true)) {
                    if (item.length() > 0) item.append('\n');
                    item.append(line[0]).append(": ").append(line[1]);
                }
                sb.append(item);
            }
            return sb.toString();
        }

        private void observe() {
            if (!isAttachedToWindow() || account < 0 || observingAccount == account) {
                return;
            }
            stopObserving();
            observingAccount = account;
            NotificationCenter center = NotificationCenter.getInstance(observingAccount);
            center.addObserver(this, NotificationCenter.fileLoaded);
            center.addObserver(this, NotificationCenter.fileLoadFailed);
            center.addObserver(this, NotificationCenter.fileLoadProgressChanged);
        }

        private void stopObserving() {
            if (observingAccount < 0) {
                return;
            }
            NotificationCenter center = NotificationCenter.getInstance(observingAccount);
            center.removeObserver(this, NotificationCenter.fileLoaded);
            center.removeObserver(this, NotificationCenter.fileLoadFailed);
            center.removeObserver(this, NotificationCenter.fileLoadProgressChanged);
            observingAccount = -1;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            observe();
            for (Card card : cards) {
                card.refresh();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopObserving();
        }

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (args.length == 0 || !(args[0] instanceof String)) {
                return;
            }
            for (Card card : cards) {
                if (!card.ref.isPreview() && args[0].equals(card.ref.fileName())) {
                    card.refresh();
                }
            }
        }

        /** One file: preview, info and its chips. {@code number} > 0 = a compact card of a list. */
        private final class Card extends LinearLayout {

            final Ref ref;
            private final int number;
            private final BackupImageView imageView;
            private final ImageView iconView;
            private final TextView infoView;
            private final TextView pathView;
            private final TextView hintView;
            private final HorizontalScrollView chipsScroll;
            private final TextView openChip, downloadChip, saveChip, shareChip, pathChip;

            Card(Context context, Ref ref, int number) {
                super(context);
                this.ref = ref;
                this.number = number;
                boolean compact = number > 0;
                setOrientation(VERTICAL);
                setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(compact ? 10 : 12));
                int text = Theme.getColor(Theme.key_dialogTextBlack, rp);
                int gray = Theme.getColor(Theme.key_dialogTextGray2, rp);

                LinearLayout top = new LinearLayout(context);
                top.setOrientation(HORIZONTAL);
                addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

                FrameLayout previewFrame = new FrameLayout(context);
                GradientDrawable previewBg = new GradientDrawable();
                previewBg.setCornerRadius(AndroidUtilities.dp(10));
                previewBg.setColor(Theme.multAlpha(text, 0.06f));
                previewFrame.setBackground(previewBg);
                imageView = new BackupImageView(context);
                imageView.setRoundRadius(AndroidUtilities.dp(10));
                imageView.getImageReceiver().setAspectFit(!ref.isPreview());
                previewFrame.addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                iconView = new ImageView(context);
                iconView.setScaleType(ImageView.ScaleType.CENTER);
                previewFrame.addView(iconView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                int side = compact ? 64 : 96;
                top.addView(previewFrame, LayoutHelper.createLinear(side, side));

                infoView = new TextView(context);
                infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                infoView.setTextColor(text);
                infoView.setLineSpacing(AndroidUtilities.dp(2), 1f);
                top.addView(infoView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

                pathView = new TextView(context);
                pathView.setTypeface(android.graphics.Typeface.MONOSPACE);
                pathView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                pathView.setTextColor(gray);
                pathView.setOnClickListener(v -> {
                    copyPath(sheet, ref);
                    RawMotion.copied(v);
                });
                addView(pathView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

                hintView = new TextView(context);
                hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                hintView.setTextColor(gray);
                hintView.setText("Файла нет в кеше, а скачать его отсюда нельзя.");
                addView(hintView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

                chipsScroll = new HorizontalScrollView(context);
                chipsScroll.setHorizontalScrollBarEnabled(false);
                LinearLayout chipsRow = new LinearLayout(context);
                chipsRow.setOrientation(HORIZONTAL);
                chipsScroll.addView(chipsRow);
                addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, compact ? 8 : 12, 0, 0));

                openChip = chips.create("Открыть", v -> open(sheet, ref, rp));
                downloadChip = chips.create("Скачать", v -> {
                    if (ref.isLoading()) {
                        cancelDownload(ref);
                    } else {
                        download(ref);
                    }
                    AndroidUtilities.runOnUIThread(this::refresh, 150);
                });
                saveChip = chips.create("Сохранить в галерею", v -> save(sheet, ref));
                shareChip = chips.create("Поделиться", v -> share(sheet, ref));
                pathChip = chips.create("Скопировать путь", v -> {
                    copyPath(sheet, ref);
                    RawMotion.copied(v);
                });
                for (TextView chip : new TextView[]{openChip, downloadChip, saveChip, shareChip, pathChip}) {
                    chipsRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, compact ? 34 : 38, 0, 0, 8, 0));
                }
                bindPreview();
                refresh();
            }

            private void bindPreview() {
                Ref r = ref;
                imageView.clearImage();
                iconView.setImageDrawable(null);
                iconView.setVisibility(GONE);
                if (r.isPreview()) {
                    // what the chat bubble shows for paid media not bought yet: the stripped thumbnail, blurred
                    android.graphics.Bitmap blurred = r.preview.thumb instanceof TLRPC.TL_photoStrippedSize && r.preview.thumb.bytes != null
                            ? ImageLoader.getStrippedPhotoBitmap(r.preview.thumb.bytes, "b") : null;
                    if (blurred != null) {
                        imageView.setImageBitmap(blurred);
                    } else {
                        iconView.setImageResource(R.drawable.msg_media);
                        iconView.setVisibility(VISIBLE);
                    }
                } else if (r.photo != null) {
                    TLRPC.PhotoSize medium = FileLoader.getClosestPhotoSizeWithSize(r.photo.sizes, 320, false, null, true);
                    TLRPC.PhotoSize stripped = FileLoader.getClosestPhotoSizeWithSize(r.photo.sizes, 40);
                    imageView.setImage(ImageLocation.getForPhoto(medium != null ? medium : r.size, r.photo), "200_200",
                            ImageLocation.getForPhoto(stripped, r.photo), "b", 0, r.parent);
                } else if (r.size != null) {
                    imageView.setImage(ImageLocation.getForDocument(r.size, r.document), "200_200", null, null, 0, r.parent);
                } else if (r.isSticker()) {
                    TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(r.document.thumbs, 90);
                    imageView.setImage(ImageLocation.getForDocument(r.document), "200_200",
                            thumb != null ? ImageLocation.getForDocument(thumb, r.document) : null, null, 0, r.parent);
                } else {
                    TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(r.document.thumbs, 320);
                    if (thumb != null && hasFile(thumb) || thumb instanceof TLRPC.TL_photoStrippedSize) {
                        TLRPC.PhotoSize stripped = FileLoader.getClosestPhotoSizeWithSize(r.document.thumbs, 40);
                        imageView.setImage(ImageLocation.getForDocument(thumb, r.document), "200_200",
                                stripped != thumb ? ImageLocation.getForDocument(stripped, r.document) : null, "b", 0, r.parent);
                    } else {
                        iconView.setImageResource(AndroidUtilities.getThumbForNameOrMime(r.displayName(), r.mime(), true));
                        iconView.setVisibility(VISIBLE);
                    }
                }
            }

            /** Re-reads the file state: info lines, path, which chips are offered and their labels. */
            void refresh() {
                Ref r = ref;
                int key = Theme.getColor(Theme.key_dialogTextGray2, rp);
                SpannableStringBuilder sb = new SpannableStringBuilder();
                String path = null;
                if (number > 0) {
                    sb.append("#").append(String.valueOf(number)).append("  ");
                    sb.setSpan(new ForegroundColorSpan(key), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    int start = sb.length();
                    sb.append(r.kind());
                    sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    start = sb.length();
                    sb.append('\n').append(r.shortLine());
                    sb.setSpan(new ForegroundColorSpan(key), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    for (String[] line : r.info(true)) {
                        if ("Путь".equals(line[0])) {
                            path = line[1];
                            continue;
                        }
                        if (sb.length() > 0) sb.append('\n');
                        int start = sb.length();
                        sb.append(line[0]).append(": ");
                        sb.setSpan(new ForegroundColorSpan(key), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sb.append(line[1]);
                    }
                }
                infoView.setText(sb);
                pathView.setText(path);
                pathView.setVisibility(path != null ? VISIBLE : GONE);

                if (r.isPreview()) {
                    // not bought: there is no file yet, only the info above
                    chipsScroll.setVisibility(GONE);
                    hintView.setVisibility(GONE);
                    return;
                }
                boolean cached = r.file() != null;
                boolean downloadable = r.canDownload();
                boolean loading = !cached && r.isLoading();
                boolean viewable = r.message != null && (r.opensInViewer() || r.isAudio()) || r.isSticker() && stickerSetOf(r.document) != null;
                chipsScroll.setVisibility(VISIBLE);
                openChip.setVisibility(cached || downloadable || viewable ? VISIBLE : GONE);
                downloadChip.setVisibility(!cached && downloadable ? VISIBLE : GONE);
                if (loading) {
                    long[] p = r.progress();
                    downloadChip.setText(p != null && p[1] > 0 ? "Отменить загрузку · " + percent(p) + "%" : "Отменить загрузку");
                } else {
                    downloadChip.setText("Скачать");
                }
                saveChip.setVisibility(cached || downloadable ? VISIBLE : GONE);
                saveChip.setText(r.saveLabel());
                shareChip.setVisibility(cached ? VISIBLE : GONE);
                pathChip.setVisibility(cached ? VISIBLE : GONE);
                hintView.setVisibility(!cached && !downloadable && !viewable ? VISIBLE : GONE);
            }
        }
    }

    /** The header line of the «Медиа» tab: "media · TL_photo", or "media · 3 items" for a list. */
    static String typeLine(List<Ref> refs) {
        if (refs.size() > 1) {
            return "media · " + refs.size() + " items";
        }
        Ref ref = refs.get(0);
        Object shown = ref.isPreview() ? ref.preview : ref.size != null ? ref.size : ref.owner();
        return "media · " + TLDumper.typeName(shown);
    }

    /** Subtitle of the «Медиа» tab: kind, size and whether the file is here; for a list, how many are in the cache. */
    static String summary(List<Ref> refs) {
        if (refs.size() > 1) {
            int cached = 0, previews = 0;
            for (Ref ref : refs) {
                if (ref.isPreview()) {
                    previews++;
                } else if (ref.file() != null) {
                    cached++;
                }
            }
            String s = refs.size() + " медиа · в кеше " + cached;
            return previews > 0 ? s + " · не куплено " + previews : s;
        }
        Ref ref = refs.get(0);
        if (ref.isPreview()) {
            return ref.kind() + " · не куплено" + (ref.stars > 0 ? " · " + ref.stars + " ⭐" : "");
        }
        StringBuilder sb = new StringBuilder(ref.kind());
        if (ref.expectedSize() > 0) {
            sb.append(" · ").append(AndroidUtilities.formatFileSize(ref.expectedSize()));
        }
        sb.append(ref.file() != null ? " · в кеше" : " · не в кеше");
        return sb.toString();
    }

    static String tabLabel(Object root) {
        String s = RawTreeView.shortType(root);
        return s.isEmpty() ? "Объект" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
