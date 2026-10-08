package org.telegram.rawgram;

import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.text.Normalizer;

/**
 * The folder saved files go to inside Pictures / Movies / Download / Music: Telegram hardcodes "Telegram", rawGram uses
 * the «Папка для сохранения» setting (default "rawGram", empty = straight into the system folder), optionally with a
 * subfolder per chat. Port of Nagram's customSavePath + saveToChatSubfolder (GPLv3).
 *
 * MediaController.saveFile only gets a path, so the screens that know the message call {@link #hint} right before it,
 * and saveFile picks the hint up with {@link #take} on the same thread.
 */
public final class RawSaveFolder {

    public static final String DEFAULT = "rawGram";

    private static volatile MessageObject hinted;
    private static volatile long hintedAt;

    private RawSaveFolder() {
    }

    /** The folder for files of {@code message} (null = no chat subfolder). Never null; "" means the system folder itself. */
    public static String name(MessageObject message) {
        String folder = RawChatUiConfig.getSaveFolder();
        if (message != null && RawChatUiConfig.saveByChat.get()) {
            String chat = chatFolderName(message);
            folder = folder.isEmpty() ? chat : folder + File.separator + chat;
        }
        return folder;
    }

    /** The message the next saveFile call saves (ChatActivity / PhotoViewer). */
    public static void hint(MessageObject message) {
        hinted = message;
        hintedAt = SystemClock.elapsedRealtime();
    }

    /** The folder for the saveFile call in progress; consumes the hint (a stale one, older than a few seconds, is dropped). */
    public static String take() {
        MessageObject message = hinted;
        hinted = null;
        if (message != null && SystemClock.elapsedRealtime() - hintedAt > 5000) {
            message = null;
        }
        return name(message);
    }

    /**
     * The attach menu's «Файлы» list descends into the save folder of Downloads (Telegram: only "Telegram"): the old
     * "Telegram" folder, the current one and, with per-chat folders, the chat folders inside it.
     */
    public static boolean isSaveDir(File dir) {
        String name = dir.getName();
        if ("Telegram".equals(name)) {
            return true;
        }
        String folder = RawChatUiConfig.getSaveFolder();
        if (folder.isEmpty()) {
            return false;
        }
        if (folder.equals(name)) {
            return true;
        }
        File parent = dir.getParentFile();
        return RawChatUiConfig.saveByChat.get() && parent != null && folder.equals(parent.getName());
    }

    /** Keeps a folder name typed in settings to safe characters; anything else falls back to the default. */
    public static String sanitize(String input) {
        if (input == null) {
            return "";
        }
        String s = input.trim();
        if (s.isEmpty()) {
            return "";
        }
        return s.matches("^(?!\\.{1,2}$)[\\p{L}\\p{N}._ -]{1,64}$") ? s : DEFAULT;
    }

    static String chatFolderName(MessageObject message) {
        long peerId = MessageObject.getPeerId(message.messageOwner.peer_id);
        MessagesController controller = MessagesController.getInstance(message.currentAccount);
        String name = null;
        if (DialogObject.isUserDialog(peerId)) {
            TLRPC.User user = controller.getUser(peerId);
            if (user != null) {
                name = UserObject.getUserName(user);
            }
        } else {
            TLRPC.Chat chat = controller.getChat(-peerId);
            if (chat != null) {
                name = chat.title;
            }
        }
        if (name == null) {
            name = "";
        }
        name = Normalizer.normalize(name, Normalizer.Form.NFKC)
                .replaceAll("[\\u200B-\\u206F]", "")
                .replaceAll("[\\p{Cc}\\p{Cf}\\\\/:*?\"<>|]", "_")
                .trim()
                .replaceAll("^\\.+|\\.+$", "");
        if (name.length() > 80) {
            name = name.substring(0, 80).trim();
        }
        return TextUtils.isEmpty(name) ? String.valueOf(peerId) : name;
    }
}
