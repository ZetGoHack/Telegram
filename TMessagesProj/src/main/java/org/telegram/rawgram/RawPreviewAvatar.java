package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.view.View;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AvatarDrawable;

/**
 * «Аватарки отправителей в превью» (Nagram's UserAvatarsInMessagePreview): in a group's row of the chats list, a small
 * avatar of the last message's sender before «Имя: текст». One per DialogCell.
 */
public final class RawPreviewAvatar {

    public static final int SIZE = 18;
    /** Avatar plus the gap before the text. */
    public static final int WIDTH = SIZE + 4;

    private final ImageReceiver image;
    private final AvatarDrawable drawable = new AvatarDrawable();
    private boolean shown;

    public RawPreviewAvatar(View cell) {
        image = new ImageReceiver(cell);
        image.setRoundRadius(dp(SIZE / 2f));
        image.setAllowLoadingOnAttachedOnly(true);
    }

    public void attach() {
        image.onAttachedToWindow();
    }

    public void detach() {
        image.onDetachedFromWindow();
    }

    /** DialogCell.update: whether the row shows a sender avatar, and which. */
    public void update(int account, MessageObject message, TLRPC.Chat chat, boolean draft, int folderId, boolean drawAvatar) {
        TLObject sender = null;
        if (RawChatUiConfig.previewAvatars.get() && drawAvatar && !LocaleController.isRTL && message != null && chat != null
                && !ChatObject.isChannelOrGiga(chat) && !ChatObject.isForum(chat) && !message.isOut() && !draft && folderId != 1
                && !(message.messageOwner instanceof TLRPC.TL_messageService)) {
            long id = message.getSenderId();
            MessagesController mc = MessagesController.getInstance(account);
            if (id > 0) {
                TLRPC.User user = mc.getUser(id);
                sender = user != null && !UserObject.isAnonymous(user) ? user : null;
            } else if (id < 0) {
                sender = mc.getChat(-id);
            }
        }
        shown = sender != null;
        if (shown) {
            drawable.setInfo(account, sender);
            image.setForUserOrChat(sender, drawable);
            image.setRoundRadius(RawUi.avatarR(dp(SIZE / 2f)));
        } else {
            image.clearImage();
        }
    }

    /** DialogCell.buildLayout: the text moves right by {@link #WIDTH} when shown. */
    public boolean shown() {
        return shown;
    }

    /** DialogCell.onDraw: the avatar at the start of the message line (the name line in the three-line layout). */
    public void draw(Canvas canvas, int left, int top, float alpha) {
        if (!shown || alpha <= 0) {
            return;
        }
        image.setImageCoords(left, top, dp(SIZE), dp(SIZE));
        image.setAlpha(alpha);
        image.draw(canvas);
    }
}
