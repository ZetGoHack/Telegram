package org.telegram.rawgram;

import android.widget.FrameLayout;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.PhotoViewer;

import java.io.File;

/** "Скопировать фото" in the ⋮ menu of the full-screen PhotoViewer. */
public class RawPhotoCopy {

    /** Snapshot of what the viewer currently shows (PhotoViewer fields are private, so it passes them in). */
    public static class Item {
        final int account;
        final MessageObject message;
        final ImageLocation avatar;
        final boolean avatarInternal;
        final TLObject viewerLocation;
        final PhotoViewer.PageBlocksAdapter pages;
        final int index;

        /**
         * @param message        currentMessageObject (chat media, link previews, service "photo changed")
         * @param avatar         currentFileLocation (profile / chat photo gallery, still image even for video avatars)
         * @param avatarInternal same flag the viewer's save uses for avatars (avatarsDialogId != 0 || isEvent)
         * @param viewerLocation PhotoViewer.getFileLocation(currentIndex) - photo size for web page / service messages
         * @param pages          instant view / page blocks gallery
         */
        public Item(int account, MessageObject message, ImageLocation avatar, boolean avatarInternal,
                    TLObject viewerLocation, PhotoViewer.PageBlocksAdapter pages, int index) {
            this.account = account;
            this.message = message;
            this.avatar = avatar;
            this.avatarInternal = avatarInternal;
            this.viewerLocation = viewerLocation;
            this.pages = pages;
            this.index = index;
        }
    }

    /** Still image (not video / gif / sticker)? The caller also requires the save item to be visible (noforwards etc). */
    public static boolean isPhoto(Item item) {
        if (item.pages != null) {
            return item.index >= 0 && item.index < item.pages.getItemsCount()
                    && !item.pages.isVideo(item.index) && !item.pages.isHardwarePlayer(item.index);
        }
        MessageObject m = item.message;
        if (m != null) {
            if (m.isSponsored() || m.isVideo() || m.isGif() || m.isRoundVideo() || m.isVideoSticker() || m.needDrawBluredPreview()) {
                return false;
            }
            if (m.messageOwner instanceof TLRPC.TL_messageService) {
                return item.viewerLocation != null;
            }
            return RawMessageDetails.isImage(m);
        }
        return item.avatar != null;
    }

    /** Local file of the current photo, null if it isn't downloaded. Same lookup order as the viewer's "save to gallery". */
    public static File file(Item item) {
        FileLoader loader = FileLoader.getInstance(item.account);
        if (item.pages != null) {
            return existing(item.pages.getFile(item.index));
        }
        if (item.message != null) {
            File f = item.message.messageOwner instanceof TLRPC.TL_messageService ? null : RawMessageDetails.mediaFile(item.account, item.message);
            // web page photo without document / service message photo; for documents viewerLocation is only the small thumb
            if (f == null && item.viewerLocation != null && item.message.getDocument() == null) {
                f = existing(loader.getPathToAttach(item.viewerLocation, true));
                if (f == null) f = existing(loader.getPathToAttach(item.viewerLocation, false));
            }
            return f;
        }
        if (item.avatar != null) {
            TLObject loc = item.avatar.location != null ? item.avatar.location : item.avatar.photoSize;
            if (loc == null) return null;
            File f = existing(loader.getPathToAttach(loc, null, item.avatarInternal));
            if (f == null) f = existing(loader.getPathToAttach(loc, null, !item.avatarInternal));
            return f;
        }
        return null;
    }

    public static void copy(Item item, FrameLayout container, Theme.ResourcesProvider resourcesProvider) {
        File f = file(item);
        if (f == null) {
            BulletinFactory.of(container, resourcesProvider).createErrorBulletin("Фото ещё не загружено").show();
            return;
        }
        String mime = item.message != null && item.pages == null ? RawMessageDetails.imageMime(item.message, f) : RawClipboard.guessImageMime(f);
        RawClipboard.copyImage(container.getContext(), f, mime, ok -> {
            if (ok) {
                BulletinFactory.of(container, resourcesProvider).createCopyBulletin("Фото скопировано").show();
            } else {
                BulletinFactory.of(container, resourcesProvider).createErrorBulletin("Не удалось скопировать фото").show();
            }
        });
    }

    /** The file itself, or its namesake in the cache dir (where the viewer's save also looks), or null. */
    private static File existing(File f) {
        if (f == null) return null;
        if (f.exists() && f.length() > 0) return f;
        File cached = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), f.getName());
        return cached.exists() && cached.length() > 0 ? cached : null;
    }
}
