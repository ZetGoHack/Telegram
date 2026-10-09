package org.telegram.rawgram;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.AnimatedFileDrawable;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * Message menu items ported from Nagram (ShowCopyPhoto, ShowCopyFrame, showDeleteDownloadedFile, ShowSetReminder):
 * «Копировать фото», «Копировать кадр», «Удалить скачанный файл», «Напомнить».
 */
final class RawMessageExtras {

    static final int OPTION_COPY_PHOTO = 9324;
    static final int OPTION_COPY_FRAME = 9325;
    static final int OPTION_DELETE_FILE = 9326;
    static final int OPTION_REMINDER = 9327;

    private RawMessageExtras() {
    }

    static boolean canCopyPhoto(MessageObject message) {
        return RawChatUiConfig.menuCopyPhoto.get() && !message.isAnyKindOfSticker() && RawMessageDetails.isImage(message);
    }

    static boolean canCopyFrame(MessageObject message) {
        return RawChatUiConfig.menuCopyPhoto.get() && (message.isVideo() || message.isGif() || message.isRoundVideo());
    }

    static boolean canDeleteFile(int account, MessageObject message) {
        if (!RawChatUiConfig.menuDeleteFile.get() || message.getId() <= 0) {
            return false;
        }
        boolean media = message.getDocument() != null || message.type == MessageObject.TYPE_PHOTO;
        return media && !files(account, message).isEmpty();
    }

    static boolean canRemind(MessageObject message) {
        return RawChatUiConfig.menuReminder.get() && message.getId() > 0;
    }

    static void copyPhoto(Context context, int account, MessageObject message) {
        RawMessageDetails.copyMedia(context, account, message, null);
    }

    /** The frame the bubble shows now (or the first one when it isn't playing), as a PNG in the clipboard. */
    static void copyFrame(ChatActivity chat, int account, MessageObject message) {
        File video = FileLoader.getInstance(account).getPathToMessage(message.messageOwner);
        if ((video == null || !video.exists()) && message.messageOwner.attachPath != null) {
            video = new File(message.messageOwner.attachPath);
        }
        if (video == null || !video.exists()) {
            RawNotify.show(R.drawable.msg_download, "Видео ещё не загружено");
            return;
        }
        long positionMs = 0;
        if (chat.findMessageCell(message.getId(), false) instanceof ChatMessageCell) {
            AnimatedFileDrawable animation = ((ChatMessageCell) chat.findMessageCell(message.getId(), false)).getPhotoImage().getAnimation();
            if (animation != null) {
                positionMs = animation.getCurrentProgressMs();
            }
        }
        final File source = video;
        final long position = positionMs;
        final Context context = chat.getParentActivity();
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap frame = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(source.getAbsolutePath());
                frame = retriever.getFrameAtTime(position * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                try {
                    retriever.release();
                } catch (Throwable ignore) {
                }
            }
            if (frame == null) {
                AndroidUtilities.runOnUIThread(() -> RawNotify.show(R.drawable.msg_info, "Не удалось получить кадр"));
                return;
            }
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

    static void deleteFile(ChatActivity chat, int account, MessageObject message) {
        ArrayList<File> files = files(account, message);
        Utilities.globalQueue.postRunnable(() -> {
            for (File f : files) {
                try {
                    if (!f.delete()) {
                        f.deleteOnExit();
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            message.checkMediaExistance();
            AndroidUtilities.runOnUIThread(() -> {
                if (chat.findMessageCell(message.getId(), false) instanceof ChatMessageCell) {
                    ((ChatMessageCell) chat.findMessageCell(message.getId(), false)).updateButtonState(false, true, false);
                }
                if (BulletinFactory.canShowBulletin(chat)) {
                    BulletinFactory.of(chat).createSimpleBulletin(R.raw.ic_delete, "Файл удалён с устройства").show();
                }
            });
        });
    }

    /** «Напомнить»: the message (or album) forwarded to Saved Messages at the picked time. */
    static void remind(ChatActivity chat, int account, MessageObject message, MessageObject.GroupedMessages group) {
        ArrayList<MessageObject> list = new ArrayList<>();
        if (group != null && !group.messages.isEmpty()) {
            list.addAll(group.messages);
        } else {
            list.add(message);
        }
        long self = UserConfig.getInstance(account).getClientUserId();
        AlertsCreator.createScheduleDatePickerDialog(chat.getParentActivity(), self, (notify, scheduleDate, scheduleRepeatPeriod) -> {
            SendMessagesHelper.getInstance(account).sendMessage(list, self, false, false, notify, scheduleDate, 0);
            if (BulletinFactory.canShowBulletin(chat)) {
                BulletinFactory.of(chat).createSimpleBulletin(R.raw.saved_messages, "Напоминание в Избранном").show();
            }
        }, chat.getResourceProvider());
    }

    /** Downloaded copies of the message media that exist on the device. */
    private static ArrayList<File> files(int account, MessageObject message) {
        ArrayList<File> out = new ArrayList<>();
        FileLoader loader = FileLoader.getInstance(account);
        add(out, loader.getPathToMessage(message.messageOwner));
        if (message.getDocument() != null) {
            add(out, loader.getPathToAttach(message.getDocument(), false));
            add(out, loader.getPathToAttach(message.getDocument(), true));
        }
        return out;
    }

    private static void add(ArrayList<File> out, File f) {
        if (f != null && f.exists() && !out.contains(f)) {
            out.add(f);
        }
    }
}
