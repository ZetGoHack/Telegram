package org.telegram.rawgram;

import android.content.Context;
import android.text.TextUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagePreviewParams;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

/**
 * «Скрыть подписи» / «Скрыть отправителя» for the quick forward paths (the forward chat picker and ShareAlert),
 * plus the initial state of the in-chat forward preview.
 *
 * MTProto: messages.forwardMessages drop_media_captions only works together with drop_author (the server ignores it
 * otherwise, same as TDLib's remove_caption without send_copy), so hiding captions implies hiding the sender —
 * exactly what Telegram's own forward preview does. Captions only exist on media: text messages are never stripped,
 * and for a set without captioned media the caption toggle doesn't force the sender off.
 *
 * The choice lives for the session (statics), starting from the defaults in RawChatUiConfig; changing a default in
 * settings overrides the session choice.
 */
public final class RawForward {

    private RawForward() {
    }

    private static int senderOverride = -1;
    private static boolean senderBase;
    private static int captionsOverride = -1;
    private static boolean captionsBase;

    public static boolean hideSender() {
        boolean def = RawChatUiConfig.fwdHideSender.get();
        return senderOverride >= 0 && senderBase == def ? senderOverride == 1 : def;
    }

    public static boolean hideCaptions() {
        boolean def = RawChatUiConfig.fwdHideCaptions.get();
        return captionsOverride >= 0 && captionsBase == def ? captionsOverride == 1 : def;
    }

    public static void setHideSender(boolean value) {
        senderBase = RawChatUiConfig.fwdHideSender.get();
        senderOverride = value ? 1 : 0;
    }

    public static void setHideCaptions(boolean value) {
        captionsBase = RawChatUiConfig.fwdHideCaptions.get();
        captionsOverride = value ? 1 : 0;
    }

    /** A media message with a non-empty caption (what drop_media_captions removes). */
    public static boolean hasMediaCaption(MessageObject m) {
        if (m == null || m.messageOwner == null) {
            return false;
        }
        if (!TextUtils.isEmpty(m.caption)) {
            return true;
        }
        TLRPC.MessageMedia media = m.messageOwner.media;
        return media != null && !(media instanceof TLRPC.TL_messageMediaEmpty) && !(media instanceof TLRPC.TL_messageMediaWebPage)
                && !TextUtils.isEmpty(m.messageOwner.message);
    }

    public static boolean anyCaption(List<MessageObject> messages) {
        if (messages != null) {
            for (int i = 0; i < messages.size(); i++) {
                if (hasMediaCaption(messages.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Telegram's own rule (MessagePreviewView): without Premium, formatted articles can't be forwarded without the sender. */
    private static boolean canDropAuthor(List<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        MessageObject first = messages.get(0);
        if (first != null && UserConfig.getInstance(first.currentAccount).isPremium()) {
            return true;
        }
        for (int i = 0; i < messages.size(); i++) {
            MessageObject m = messages.get(i);
            if (m != null && m.type == MessageObject.TYPE_ARTICLE) {
                return false;
            }
        }
        return true;
    }

    /** drop_media_captions for this forward. */
    public static boolean dropCaptions(List<MessageObject> messages) {
        return hideCaptions() && anyCaption(messages) && canDropAuthor(messages);
    }

    /** drop_author for this forward (forced on by dropCaptions). */
    public static boolean dropAuthor(List<MessageObject> messages) {
        return (hideSender() || dropCaptions(messages)) && canDropAuthor(messages);
    }

    /** Called when the in-chat forward panel is created: start from the session choice. */
    public static void applyToPreview(MessagePreviewParams params) {
        if (params == null || params.forwardMessages == null || params.noforwards) {
            return;
        }
        List<MessageObject> messages = params.forwardMessages.messages;
        if (!canDropAuthor(messages)) {
            return;
        }
        if (hideCaptions() && params.hasCaption) {
            params.hideCaption = true;
            params.hideForwardSendersName = true;
        }
        if (hideSender()) {
            params.hideForwardSendersName = true;
        }
    }

    // ---- ShareAlert: send-button long-press ----

    /**
     * Replaces Telegram's «Показать/Скрыть имя отправителя» pair in ShareAlert's send popup: the same pair, now
     * remembered for the session, plus a «Показать/Скрыть подпись» pair when a forwarded message has a media caption.
     */
    public static void addShareToggles(ActionBarPopupWindow.ActionBarPopupWindowLayout layout, Context context,
                                       Theme.ResourcesProvider resourcesProvider, int textColor, List<MessageObject> messages) {
        final boolean hasCaption = anyCaption(messages);
        ActionBarMenuSubItem show = new ActionBarMenuSubItem(context, true, true, false, resourcesProvider);
        ActionBarMenuSubItem hide = new ActionBarMenuSubItem(context, true, false, !hasCaption, resourcesProvider);
        // a Show/Hide pair for captions too, like Telegram's own sender pair
        ActionBarMenuSubItem showCaption = hasCaption ? new ActionBarMenuSubItem(context, true, false, false, resourcesProvider) : null;
        ActionBarMenuSubItem captions = hasCaption ? new ActionBarMenuSubItem(context, true, false, true, resourcesProvider) : null;
        show.setTextAndIcon(LocaleController.getString(R.string.ShowSendersName), 0);
        hide.setTextAndIcon(LocaleController.getString(R.string.HideSendersName), 0);
        layout.addView(show, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        layout.addView(hide, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        if (captions != null) {
            showCaption.setTextAndIcon(LocaleController.getString(R.string.ShowCaption), 0);
            captions.setTextAndIcon(LocaleController.getString(R.string.HideCaption), 0);
            layout.addView(showCaption, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
            layout.addView(captions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        }
        if (textColor != 0) {
            show.setTextColor(textColor);
            hide.setTextColor(textColor);
            if (captions != null) {
                showCaption.setTextColor(textColor);
                captions.setTextColor(textColor);
            }
        }
        Runnable refresh = () -> {
            boolean noSender = hideSender() || hideCaptions() && hasCaption;
            show.setChecked(!noSender);
            hide.setChecked(noSender);
            if (captions != null) {
                showCaption.setChecked(!hideCaptions());
                captions.setChecked(hideCaptions());
            }
        };
        refresh.run();
        show.setOnClickListener(v -> {
            setHideSender(false);
            setHideCaptions(false);
            refresh.run();
        });
        hide.setOnClickListener(v -> {
            setHideSender(true);
            refresh.run();
        });
        if (captions != null) {
            showCaption.setOnClickListener(v -> {
                setHideCaptions(false);
                refresh.run();
            });
            captions.setOnClickListener(v -> {
                setHideCaptions(true);
                refresh.run();
            });
        }
    }

    // ---- forward chat picker (DialogsActivity): send-button long-press ----

    /**
     * Adds «Скрыть подписи» and «Скрыть имя отправителя» check items on top of the picker's send menu.
     * The picker doesn't know the messages, so the caption item is always there; ChatActivity applies it only
     * to captioned media when it sends (see dropAuthor / dropCaptions).
     */
    public static ItemOptions addPickerToggles(ItemOptions options, boolean forwarding) {
        if (!forwarding) {
            return options;
        }
        // sender first: hiding captions is only possible with the sender hidden too (server rule)
        ActionBarMenuSubItem sender = options.addChecked();
        sender.setText(LocaleController.getString(R.string.HideSendersName));
        ActionBarMenuSubItem captions = options.addChecked();
        captions.setText(LocaleController.getString(R.string.HideCaption));
        Runnable refresh = () -> {
            captions.setChecked(hideCaptions());
            sender.setChecked(hideSender() || hideCaptions());
        };
        refresh.run();
        captions.setOnClickListener(v -> {
            setHideCaptions(!hideCaptions());
            refresh.run();
        });
        sender.setOnClickListener(v -> {
            if (hideSender() || hideCaptions()) {
                setHideSender(false);
                setHideCaptions(false);
            } else {
                setHideSender(true);
            }
            refresh.run();
        });
        options.addGap();
        return options;
    }
}
