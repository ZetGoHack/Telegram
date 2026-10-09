package org.telegram.rawgram;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

/**
 * «Онлайн-статус у сообщений» (Nagram's ShowOnlineStatus): the green dot of the chats list on the sender avatars in
 * groups, cut out of the avatar the same way.
 */
public final class RawOnlineDot {

    private RawOnlineDot() {
    }

    private static Paint clearPaint;
    private static Paint dotPaint;

    /** Replaces imageReceiver.draw for a message avatar. */
    public static void draw(Canvas canvas, ImageReceiver image, TLRPC.User user, int account) {
        if (!RawChatUiConfig.onlineDot.get() || !online(user, account) || !image.hasImageSet()) {
            image.draw(canvas);
            return;
        }
        if (clearPaint == null) {
            clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        float size = Math.min(image.getImageWidth(), image.getImageHeight());
        // proportions of the chats list dot (DialogCell: 7 / 5 dp on a 54 dp avatar)
        float inner = size * 0.0926f;
        float outer = size * 0.1296f;
        float cx = image.getImageX2() - size * 0.12f;
        float cy = image.getImageY2() - size * 0.12f;
        int save = canvas.saveLayer(image.getImageX(), image.getImageY(), image.getImageX2() + 1, image.getImageY2() + 1, null);
        image.draw(canvas);
        canvas.drawCircle(cx, cy, outer, clearPaint);
        canvas.restoreToCount(save);
        dotPaint.setColor(Theme.getColor(Theme.key_chats_onlineCircle));
        dotPaint.setAlpha((int) (255 * image.getAlpha()));
        canvas.drawCircle(cx, cy, inner, dotPaint);
    }

    private static boolean online(TLRPC.User user, int account) {
        if (user == null || user.self || user.bot || user.status == null || MessagesController.isSupportUser(user)) {
            return false;
        }
        if (user.status.expires <= 0 && MessagesController.getInstance(account).onlinePrivacy.containsKey(user.id)) {
            return true;
        }
        return user.status.expires > ConnectionsManager.getInstance(account).getCurrentTime();
    }
}
