package org.telegram.rawgram;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EmojiPacksAlert;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.StickersAlert;
import org.telegram.ui.ProfileActivity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * "Подробности" submenu of the message context menu, in the spirit of Telegram Desktop's "Details":
 * icon + title + gray value per row (only the fields that are set). Rows with a natural action
 * (profile, set, link, reply, map) perform it on tap and show the value in the accent color;
 * the rest copy on tap. Long press always copies. Then "Скопировать фото", "Сохранить в галерею"
 * and "Посмотреть в raw" at the bottom.
 */
public class RawMessageDetails {

    private static class Row {
        final String title;
        final String value;
        final int icon;
        /** Tap action; null = tap copies the value. */
        final Runnable action;

        Row(String title, String value, int icon, Runnable action) {
            this.title = title;
            this.value = value;
            this.icon = icon;
            this.action = action;
        }
    }

    /** What the row builders need to wire actions. */
    private static class Env {
        final BaseFragment fragment;
        final int account;
        final MessageObject message;
        final Theme.ResourcesProvider rp;

        Env(BaseFragment fragment, int account, MessageObject message, Theme.ResourcesProvider rp) {
            this.fragment = fragment;
            this.account = account;
            this.message = message;
            this.rp = rp;
        }
    }

    public static LinearLayout build(BaseFragment fragment, int currentAccount, MessageObject message, Theme.ResourcesProvider rp,
                                     Runnable onBack, Runnable onRaw, Runnable onClose) {
        Context context = fragment.getParentActivity();
        Env env = new Env(fragment, currentAccount, message, rp);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        ActionBarMenuSubItem back = new ActionBarMenuSubItem(context, true, false, rp);
        back.setItemHeight(44);
        back.setTextAndIcon(LocaleController.getString(R.string.Back), R.drawable.msg_arrow_back);
        back.getTextView().setPadding(LocaleController.isRTL ? 0 : AndroidUtilities.dp(40), 0, LocaleController.isRTL ? AndroidUtilities.dp(40) : 0, 0);
        back.setOnClickListener(v -> onBack.run());
        root.addView(back, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        ArrayList<Row> messageRows = new ArrayList<>();
        messageFields(messageRows, env);
        ArrayList<Row> mediaRows = new ArrayList<>();
        mediaFields(mediaRows, env, MessageObject.getMedia(message.messageOwner));
        for (Row r : messageRows) {
            rows.addView(row(context, r, rp, onClose), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        if (!messageRows.isEmpty() && !mediaRows.isEmpty()) {
            rows.addView(new ActionBarPopupWindow.GapView(context, rp), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));
        }
        for (Row r : mediaRows) {
            rows.addView(row(context, r, rp, onClose), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // never taller than about five rows: the menu must not cover half the screen
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(250), MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(rows);
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // protected content (no forwarding/saving in this chat, self-destructing media) is not copied out
        boolean protectedContent = message.messageOwner.noforwards || message.needDrawBluredPreview()
                || MessagesController.getInstance(currentAccount).isPeerNoForwards(message.getDialogId());
        // only images: Android apps paste images from the clipboard, videos and files they ignore
        if (isImage(message) && !protectedContent) {
            ActionBarMenuSubItem copy = new ActionBarMenuSubItem(context, false, false, rp);
            copy.setTextAndIcon("Скопировать фото", R.drawable.msg_copy);
            copy.setOnClickListener(v -> copyMedia(context, currentAccount, message, onClose));
            root.addView(copy, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        if (hasMediaFile(message) && !protectedContent) {
            int saveType = saveType(message);
            ActionBarMenuSubItem save = new ActionBarMenuSubItem(context, false, false, rp);
            save.setTextAndIcon(saveType == 2 ? "Сохранить в загрузки" : saveType == 3 ? "Сохранить в музыку" : "Сохранить в галерею",
                    saveType >= 2 ? R.drawable.msg_download : R.drawable.msg_gallery);
            save.setOnClickListener(v -> saveToGallery(env, onClose));
            root.addView(save, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        ActionBarMenuSubItem raw = new ActionBarMenuSubItem(context, false, true, rp);
        raw.setTextAndIcon("Посмотреть в raw", R.drawable.msg_info);
        raw.setOnClickListener(v -> onRaw.run());
        root.addView(raw, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return root;
    }

    private static ActionBarMenuSubItem row(Context context, Row r, Theme.ResourcesProvider rp, Runnable onClose) {
        ActionBarMenuSubItem item = new ActionBarMenuSubItem(context, false, false, rp);
        item.setTextAndIcon(r.title, r.icon);
        item.setSubtext(r.value);
        if (r.action != null) {
            // accent-colored value hints that tap does something besides copying
            item.setSubtextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, rp));
        }
        // long values (links, file names) must ellipsize instead of stretching the popup
        item.getTextView().setMaxWidth(AndroidUtilities.dp(260));
        item.subtextView.setMaxWidth(AndroidUtilities.dp(260));
        item.setOnClickListener(v -> {
            if (r.action != null) {
                if (onClose != null) onClose.run();
                r.action.run();
            } else {
                copy(r);
            }
        });
        item.setOnLongClickListener(v -> {
            copy(r);
            return true;
        });
        return item;
    }

    private static void copy(Row r) {
        AndroidUtilities.addToClipboard(r.value);
        RawNotify.show(R.drawable.msg_copy, r.title + " скопировано");
    }

    private static void add(ArrayList<Row> out, String title, String value, int icon) {
        add(out, title, value, icon, null);
    }

    private static void add(ArrayList<Row> out, String title, String value, int icon, Runnable action) {
        if (!TextUtils.isEmpty(value)) {
            out.add(new Row(title, value, icon, action));
        }
    }

    private static void addPeer(ArrayList<Row> out, Env env, String title, RawPeers.Info info, int icon) {
        if (info != null) {
            add(out, title, info.format(), icon, () -> openProfile(env, info));
        }
    }

    // ---- actions ----

    private static void openProfile(Env env, RawPeers.Info info) {
        if (!info.isCached) {
            // ProfileActivity can't show a peer it has never seen
            AndroidUtilities.addToClipboard(info.id);
            RawNotify.show(R.drawable.msg_copy, "Нет в кеше — ID скопирован");
            return;
        }
        env.fragment.presentFragment(ProfileActivity.of(info.dialogId));
    }

    private static void openUrl(Env env, String url) {
        Browser.openUrl(env.fragment.getParentActivity(), url);
    }

    private static void openStickerSet(Env env, TLRPC.InputStickerSet input, boolean emoji) {
        Activity activity = env.fragment.getParentActivity();
        if (activity == null) return;
        if (emoji) {
            ArrayList<TLRPC.InputStickerSet> sets = new ArrayList<>();
            sets.add(input);
            env.fragment.showDialog(new EmojiPacksAlert(env.fragment, activity, env.rp, sets));
        } else {
            env.fragment.showDialog(new StickersAlert(activity, env.fragment, input, null, null, env.rp, false));
        }
    }

    private static void openMap(Env env, double lat, double lon, String fallback) {
        Activity activity = env.fragment.getParentActivity();
        try {
            String ll = String.format(Locale.US, "%.6f,%.6f", lat, lon);
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + ll + "?q=" + ll)));
        } catch (Exception e) {
            // no map app installed
            AndroidUtilities.addToClipboard(fallback);
            RawNotify.show(R.drawable.msg_copy, "Координаты скопированы");
        }
    }

    /** Sheets are created only here: their constructors need the activity, which may be gone by the time of the tap. */
    private static void showSheet(Env env, Utilities.Callback0Return<BottomSheet> factory) {
        if (env.fragment.getParentActivity() == null) return;
        env.fragment.showDialog(factory.run());
    }

    /** "3: bold, text_url, custom_emoji" — distinct types in order of appearance. */
    private static String entitiesSummary(ArrayList<TLRPC.MessageEntity> entities) {
        ArrayList<String> types = new ArrayList<>();
        for (TLRPC.MessageEntity e : entities) {
            String type = RawEntitiesSheet.entityType(e);
            if (!types.contains(type)) types.add(type);
        }
        return entities.size() + ": " + TextUtils.join(", ", types);
    }

    private static int buttonCount(TLRPC.ReplyMarkup markup) {
        int count = 0;
        if (markup instanceof TLRPC.TL_replyInlineMarkup) {
            for (TL_keyboard.KeyboardInlineButtonRow row : ((TLRPC.TL_replyInlineMarkup) markup).rows) count += row.buttons.size();
        } else if (markup instanceof TLRPC.TL_replyKeyboardMarkup) {
            for (TL_keyboard.KeyboardButtonRow row : ((TLRPC.TL_replyKeyboardMarkup) markup).rows) count += row.buttons.size();
        }
        return count;
    }

    // ---- message block ----

    private static void messageFields(ArrayList<Row> out, Env env) {
        MessageObject message = env.message;
        TLRPC.Message m = message.messageOwner;
        int account = env.account;
        MessagesController mc = MessagesController.getInstance(account);
        add(out, "ID", Integer.toString(m.id), R.drawable.menu_hashtag);
        if (m.date != 0) add(out, "Дата", date(m.date), R.drawable.msg_calendar2);
        if (m.edit_date != 0) add(out, "Изменено", date(m.edit_date), R.drawable.msg_edit);
        if (m.fwd_from != null) {
            if (m.fwd_from.date != 0) add(out, "Дата оригинала", date(m.fwd_from.date), R.drawable.msg_forward);
            if (m.fwd_from.from_id != null) addPeer(out, env, "Автор оригинала", RawPeers.resolve(account, m.fwd_from.from_id), R.drawable.msg_openprofile);
            add(out, "Имя автора оригинала", m.fwd_from.from_name, R.drawable.msg_text_outlined);
            if (m.fwd_from.channel_post != 0) add(out, "Пост в оригинале", Integer.toString(m.fwd_from.channel_post), R.drawable.msg_channel);
            if (m.fwd_from.saved_from_peer != null) addPeer(out, env, "Сохранено из", RawPeers.resolve(account, m.fwd_from.saved_from_peer), R.drawable.msg_saved);
        }
        if (m.from_id != null) addPeer(out, env, "Отправитель", RawPeers.resolve(account, m.from_id), R.drawable.msg_openprofile);
        if (m.peer_id != null) {
            addPeer(out, env, "Чат", RawPeers.resolve(account, m.peer_id), m.peer_id instanceof TLRPC.TL_peerChannel ? R.drawable.msg_channel : R.drawable.msg_groups);
            if (m.peer_id instanceof TLRPC.TL_peerChannel && m.id > 0) {
                TLRPC.Chat chat = mc.getChat(m.peer_id.channel_id);
                String username = ChatObject.getPublicUsername(chat);
                String link = TextUtils.isEmpty(username)
                        ? "https://t.me/c/" + m.peer_id.channel_id + "/" + m.id
                        : "https://t.me/" + username + "/" + m.id;
                add(out, "Ссылка", link, R.drawable.msg_link, () -> openUrl(env, link));
            }
        }
        if (m.reply_to != null && m.reply_to.reply_to_msg_id != 0) {
            int replyId = m.reply_to.reply_to_msg_id;
            // scroll only to replies within this chat (not cross-chat quotes) and only from a chat screen
            boolean sameChat = m.reply_to.reply_to_peer_id == null || DialogObject.getPeerDialogId(m.reply_to.reply_to_peer_id) == message.getDialogId();
            Runnable scroll = sameChat && env.fragment instanceof ChatActivity
                    ? () -> ((ChatActivity) env.fragment).scrollToMessageId(replyId, message.getId(), true, 0, true, 0)
                    : null;
            add(out, "Ответ на", Integer.toString(replyId), R.drawable.menu_reply, scroll);
        }
        if (m.reply_to != null && m.reply_to.reply_to_top_id != 0) add(out, "Тред", Integer.toString(m.reply_to.reply_to_top_id), R.drawable.msg_discussion);
        if (m.via_bot_id != 0) addPeer(out, env, "Через бота", RawPeers.user(account, m.via_bot_id), R.drawable.msg_bot);
        if (m.grouped_id != 0) add(out, "Альбом", Long.toString(m.grouped_id), R.drawable.msg_gallery);
        if (m.views > 0) add(out, "Просмотры", Integer.toString(m.views), R.drawable.msg_views);
        if (m.forwards > 0) add(out, "Пересылки", Integer.toString(m.forwards), R.drawable.msg_shareout);
        if (m.reactions != null && m.reactions.results != null && !m.reactions.results.isEmpty()) {
            int total = 0;
            for (TLRPC.ReactionCount rc : m.reactions.results) {
                total += rc.count;
            }
            add(out, "Реакции", total + "  (видов: " + m.reactions.results.size() + ")", R.drawable.msg_reactions,
                    () -> showSheet(env, () -> new RawReactionsSheet(env.fragment, account, message, env.rp)));
        }
        if (m.entities != null && !m.entities.isEmpty()) {
            add(out, "Форматирование", entitiesSummary(m.entities), R.drawable.msg_text_outlined,
                    () -> showSheet(env, () -> new RawEntitiesSheet(env.fragment, account, message, env.rp)));
        }
        int buttons = buttonCount(m.reply_markup);
        if (buttons > 0) {
            add(out, "Кнопки", buttons + "  (" + TLDumper.typeName(m.reply_markup) + ")", R.drawable.msg_bot,
                    () -> showSheet(env, () -> new RawEntitiesSheet(env.fragment, account, message, env.rp)));
        }
        if (m.ttl_period != 0) add(out, "Автоудаление", LocaleController.formatTTLString(m.ttl_period) + "  (" + m.ttl_period + " с)", R.drawable.msg_autodelete);
        add(out, "Подпись автора", m.post_author, R.drawable.msg_text_outlined);
    }

    // ---- media block ----

    private static void mediaFields(ArrayList<Row> out, Env env, TLRPC.MessageMedia media) {
        if (media == null) {
            return;
        }
        if (media instanceof TLRPC.TL_messageMediaWebPage && media.webpage != null) {
            String url = media.webpage.url;
            add(out, "Ссылка", url, R.drawable.msg_link2, () -> openUrl(env, url));
            if (media.webpage.document != null) {
                documentFields(out, env, media.webpage.document);
            } else if (media.webpage.photo != null) {
                photoFields(out, media.webpage.photo);
            }
        } else if (media.document != null) {
            documentFields(out, env, media.document);
        } else if (media.photo != null) {
            photoFields(out, media.photo);
        } else if (media instanceof TLRPC.TL_messageMediaPoll) {
            TLRPC.TL_messageMediaPoll poll = (TLRPC.TL_messageMediaPoll) media;
            if (poll.poll != null && poll.poll.question != null) {
                add(out, "Вопрос", poll.poll.question.text, R.drawable.msg_stats);
                if (poll.poll.answers != null) add(out, "Вариантов", Integer.toString(poll.poll.answers.size()), R.drawable.msg_stats);
            }
            if (poll.results != null) add(out, "Проголосовало", Integer.toString(poll.results.total_voters), R.drawable.msg_contacts);
        } else if (media.geo != null && !(media.geo instanceof TLRPC.TL_geoPointEmpty)) {
            add(out, "Название", media.title, R.drawable.msg_location);
            add(out, "Адрес", media.address, R.drawable.msg_location);
            double lat = media.geo.lat, lon = media.geo._long;
            String coords = String.format(Locale.US, "%.6f, %.6f", lat, lon);
            add(out, "Координаты", coords, R.drawable.msg_location, () -> openMap(env, lat, lon, coords));
            if (media.geo.accuracy_radius > 0) add(out, "Точность", media.geo.accuracy_radius + " м", R.drawable.msg_location);
        } else if (media instanceof TLRPC.TL_messageMediaContact) {
            String name = ((media.first_name != null ? media.first_name : "") + " " + (media.last_name != null ? media.last_name : "")).trim();
            if (media.user_id != 0) {
                RawPeers.Info contact = RawPeers.user(env.account, media.user_id);
                add(out, "Имя", name, R.drawable.msg_openprofile, () -> openProfile(env, contact));
                add(out, "Телефон", media.phone_number, R.drawable.msg_calls);
                add(out, "ID пользователя", Long.toString(media.user_id), R.drawable.menu_hashtag, () -> openProfile(env, contact));
            } else {
                add(out, "Имя", name, R.drawable.msg_openprofile);
                add(out, "Телефон", media.phone_number, R.drawable.msg_calls);
            }
        }
    }

    private static void documentFields(ArrayList<Row> out, Env env, TLRPC.Document doc) {
        if (doc.size > 0) add(out, "Размер файла", size(doc.size), R.drawable.msg_download);
        add(out, "Тип медиа", doc.mime_type, R.drawable.msg_media);
        String fileName = FileLoader.getDocumentFileName(doc);
        add(out, "Название файла", fileName, R.drawable.msg_sendfile);
        int w = 0, h = 0;
        double duration = 0;
        String codec = null, performer = null, title = null;
        TLRPC.InputStickerSet set = null;
        boolean emoji = false;
        String alt = null;
        for (TLRPC.DocumentAttribute a : doc.attributes) {
            if (a instanceof TLRPC.TL_documentAttributeVideo) {
                if (a.w > 0 && a.h > 0) { w = a.w; h = a.h; }
                if (a.duration > 0) duration = a.duration;
                if (!TextUtils.isEmpty(a.video_codec)) codec = a.video_codec;
            } else if (a instanceof TLRPC.TL_documentAttributeImageSize) {
                if (w == 0 && a.w > 0 && a.h > 0) { w = a.w; h = a.h; }
            } else if (a instanceof TLRPC.TL_documentAttributeAudio) {
                if (a.duration > 0) duration = a.duration;
                performer = a.performer;
                title = a.title;
            } else if (a instanceof TLRPC.TL_documentAttributeSticker || a instanceof TLRPC.TL_documentAttributeCustomEmoji) {
                emoji = a instanceof TLRPC.TL_documentAttributeCustomEmoji;
                set = a.stickerset;
                alt = a.alt;
            }
        }
        if (w > 0 && h > 0) add(out, "Разрешение", w + "x" + h, R.drawable.msg_photo_settings);
        if (duration > 0) add(out, "Длительность", duration(duration), R.drawable.msg_recent);
        add(out, "Кодек", codec, R.drawable.msg_filehq);
        add(out, "Исполнитель", performer, R.drawable.msg_openprofile);
        add(out, "Название трека", title, R.drawable.msg_text_outlined);
        add(out, "Эмодзи", alt, R.drawable.msg_emoji_cat);
        if (doc.dc_id > 0) add(out, "Датацентр", dc(doc.dc_id), R.drawable.msg_language);
        if (set != null && !(set instanceof TLRPC.TL_inputStickerSetEmpty)) {
            stickerSetFields(out, env, set, emoji);
        }
    }

    private static void stickerSetFields(ArrayList<Row> out, Env env, TLRPC.InputStickerSet input, boolean emoji) {
        TLRPC.TL_messages_stickerSet cached = MediaDataController.getInstance(env.account).getStickerSet(input, true);
        TLRPC.StickerSet set = cached != null ? cached.set : null;
        long setId = set != null ? set.id : input.id;
        String shortName = set != null ? set.short_name : input.short_name;
        String name;
        if (set != null && !TextUtils.isEmpty(set.title)) {
            name = TextUtils.isEmpty(shortName) ? set.title : set.title + "  ·  " + shortName;
        } else {
            name = shortName;
        }
        add(out, "Набор", name, emoji ? R.drawable.msg_emoji_stickers : R.drawable.msg_sticker, () -> openStickerSet(env, input, emoji));
        if (!TextUtils.isEmpty(shortName)) {
            String link = "https://t.me/" + (emoji ? "addemoji/" : "addstickers/") + shortName;
            add(out, "Ссылка на набор", link, R.drawable.msg_link, () -> openUrl(env, link));
        }
        if (setId != 0) {
            add(out, "ID набора", Long.toString(setId), R.drawable.menu_hashtag);
            // official sets are not created by a user: their id encodes no owner
            long ownerId = set != null && set.official ? 0 : stickerSetOwner(setId);
            if (ownerId > 0) addPeer(out, env, "Владелец набора", RawPeers.user(env.account, ownerId), R.drawable.msg_openprofile);
        }
    }

    /**
     * Owner user id encoded in a sticker set id; same formula as AyuGram Desktop
     * (getUserIdFromPackId, taken from TDesktop-x64/tdesktop#218).
     */
    private static long stickerSetOwner(long setId) {
        long owner = setId >>> 32;
        if (((setId >>> 16) & 0xff) == 0x3f) {
            owner |= 0x80000000L;
        }
        if (((setId >>> 24) & 0xff) != 0) {
            owner += 0x100000000L;
        }
        return owner;
    }

    private static void photoFields(ArrayList<Row> out, TLRPC.Photo photo) {
        TLRPC.PhotoSize largest = null;
        for (TLRPC.PhotoSize s : photo.sizes) {
            if (s == null || s instanceof TLRPC.TL_photoStrippedSize || s instanceof TLRPC.TL_photoPathSize) continue;
            if (largest == null || (long) s.w * s.h > (long) largest.w * largest.h) largest = s;
        }
        if (largest != null) {
            if (largest.size > 0) add(out, "Размер файла", size(largest.size), R.drawable.msg_download);
            add(out, "Тип медиа", "image/jpeg", R.drawable.msg_media);
            if (largest.w > 0 && largest.h > 0) add(out, "Разрешение", largest.w + "x" + largest.h, R.drawable.msg_photo_settings);
        }
        if (photo.dc_id > 0) add(out, "Датацентр", dc(photo.dc_id), R.drawable.msg_language);
    }

    // ---- copy media ----

    private static TLRPC.Document document(TLRPC.MessageMedia media) {
        if (media instanceof TLRPC.TL_messageMediaWebPage) {
            return media.webpage != null ? media.webpage.document : null;
        }
        return media instanceof TLRPC.TL_messageMediaDocument ? media.document : null;
    }

    private static boolean hasMediaFile(MessageObject message) {
        TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        if (media instanceof TLRPC.TL_messageMediaWebPage) {
            return media.webpage != null && (media.webpage.document != null || media.webpage.photo != null);
        }
        return media instanceof TLRPC.TL_messageMediaDocument && media.document != null
                || media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null;
    }

    static File mediaFile(int currentAccount, MessageObject message) {
        TLRPC.Message m = message.messageOwner;
        if (!TextUtils.isEmpty(m.attachPath)) {
            File f = new File(m.attachPath);
            if (f.exists() && f.length() > 0) return f;
        }
        FileLoader loader = FileLoader.getInstance(currentAccount);
        File f = loader.getPathToMessage(m);
        if (f != null && f.exists() && f.length() > 0) return f;
        f = loader.getPathToMessage(m, true, true);
        if (f != null && f.exists() && f.length() > 0) return f;
        return null;
    }

    private static void copyMedia(Context context, int currentAccount, MessageObject message, Runnable onClose) {
        File file = mediaFile(currentAccount, message);
        if (file == null) {
            RawNotify.show(R.drawable.msg_download, "Фото ещё не загружено");
            return;
        }
        if (onClose != null) onClose.run();
        RawClipboard.copyImage(context, file, imageMime(message, file), ok -> {
            if (ok) {
                RawNotify.show(R.drawable.msg_copy, "Фото скопировано");
            } else {
                RawNotify.show(R.drawable.msg_info, "Не удалось скопировать фото");
            }
        });
    }

    /** Document mime when it's an image document, otherwise guessed from the file (photos are jpeg). */
    static String imageMime(MessageObject message, File file) {
        String mime = mime(message);
        return mime.startsWith("image/") ? mime : RawClipboard.guessImageMime(file);
    }

    // ---- save to gallery (same path as ChatActivity's OPTION_SAVE_TO_GALLERY / saveMessageToGallery) ----

    private static boolean isVideoLike(MessageObject message) {
        return message.isVideo() || message.isGif() || message.isRoundVideo() || message.isVideoSticker();
    }

    private static String mime(MessageObject message) {
        TLRPC.Document doc = document(MessageObject.getMedia(message.messageOwner));
        return doc != null && doc.mime_type != null ? doc.mime_type.toLowerCase(Locale.ROOT) : "";
    }

    static boolean isImage(MessageObject message) {
        TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        boolean photo = media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null
                || media instanceof TLRPC.TL_messageMediaWebPage && media.webpage != null && media.webpage.photo != null && media.webpage.document == null;
        return photo || mime(message).startsWith("image/");
    }

    /** MediaController.saveFile type: 0 pictures, 1 movies, 2 downloads, 3 music (Telegram's own folders). */
    private static int saveType(MessageObject message) {
        String mime = mime(message);
        if (isImage(message)) return 0;
        if (isVideoLike(message) || mime.startsWith("video/")) return 1;
        if (message.isMusic() || message.isVoice() || mime.startsWith("audio/")) return 3;
        return 2;
    }

    private static String galleryPath(int account, MessageObject message) {
        File f = mediaFile(account, message);
        if (f != null) return f.getPath();
        // streamed videos: a cached quality or the quality picked for saving
        if (message.cachedQuality != null && message.cachedQuality.isCached() && message.cachedQuality.uri != null) {
            String p = message.cachedQuality.uri.getPath();
            if (p != null && new File(p).exists()) return p;
        }
        if (message.qualityToSave != null) {
            f = FileLoader.getInstance(account).getPathToAttach(message.qualityToSave, null, false, true);
            if (f != null && f.exists()) return f.getPath();
        }
        return null;
    }

    private static void saveToGallery(Env env, Runnable onClose) {
        Activity activity = env.fragment.getParentActivity();
        if (activity == null) return;
        MessageObject message = env.message;
        String path = galleryPath(env.account, message);
        if (path == null) {
            RawNotify.show(R.drawable.msg_download, "Файл ещё не загружен");
            return;
        }
        if (onClose != null) onClose.run();
        // same check as ChatActivity: only scoped-storage builds save through MediaStore without a permission
        if (Build.VERSION.SDK_INT >= 23 && (Build.VERSION.SDK_INT <= 28 || BuildVars.NO_SCOPED_STORAGE)
                && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
            return;
        }
        final int saveType = saveType(message);
        final BulletinFactory.FileType type = message.isLivePhoto() ? BulletinFactory.FileType.LIVEPHOTO
                : saveType == 0 ? BulletinFactory.FileType.PHOTO
                : saveType == 1 ? (message.isGif() ? BulletinFactory.FileType.GIF : BulletinFactory.FileType.VIDEO)
                : saveType == 3 ? BulletinFactory.FileType.AUDIO : BulletinFactory.FileType.UNKNOWN;
        Utilities.Callback<Uri> onSaved = uri -> {
            if (BulletinFactory.canShowBulletin(env.fragment)) {
                BulletinFactory.of(env.fragment).createDownloadBulletin(type, env.rp).show();
            } else {
                RawNotify.show(R.drawable.msg_gallery, saveType >= 2 ? "Сохранено" : "Сохранено в галерею");
            }
        };
        if (message.isLivePhoto()) {
            TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
            TLRPC.Document videoDoc = media != null ? media.document : null;
            if (videoDoc != null) {
                File video = FileLoader.getInstance(env.account).getPathToAttach(videoDoc, false);
                if (video == null || !video.exists()) {
                    video = FileLoader.getInstance(env.account).getPathToAttach(videoDoc, true);
                }
                if (video != null && video.exists()) {
                    MediaController.saveFile(path, video.getPath(), activity, onSaved);
                    return;
                }
            }
        }
        String name = null;
        String mime = null;
        if (saveType >= 2) {
            TLRPC.Document doc = document(MessageObject.getMedia(message.messageOwner));
            name = doc != null ? FileLoader.getDocumentFileName(doc) : null;
            if (TextUtils.isEmpty(name)) {
                name = new File(path).getName();
            }
            mime = doc != null ? doc.mime_type : null;
        }
        MediaController.saveFile(path, activity, saveType, name, mime, onSaved);
    }

    // ---- formatting ----

    private static String date(int unix) {
        return new SimpleDateFormat("dd.MM.yyyy 'в' HH:mm:ss", Locale.getDefault()).format(new Date(unix * 1000L));
    }

    private static String size(long bytes) {
        return AndroidUtilities.formatFileSize(bytes) + "  (" + bytes + " Б)";
    }

    private static String duration(double seconds) {
        long ms = Math.round(seconds * 1000);
        long total = ms / 1000;
        String base = total >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
                : String.format(Locale.US, "%d:%02d", total / 60, total % 60);
        return ms % 1000 != 0 ? base + String.format(Locale.US, ".%03d", ms % 1000) : base;
    }

    private static String dc(int id) {
        String where;
        switch (id) {
            case 1:
            case 3:
                where = "Miami FL, US";
                break;
            case 2:
            case 4:
                where = "Amsterdam, NL";
                break;
            case 5:
                where = "Singapore, SG";
                break;
            default:
                where = null;
        }
        return where != null ? "DC" + id + ", " + where : "DC" + id;
    }
}
