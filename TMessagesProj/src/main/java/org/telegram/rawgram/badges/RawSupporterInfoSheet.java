package org.telegram.rawgram.badges;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.rawgram.RawBadges;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/**
 * The sheet behind someone else's supporter badge: «**Имя** поддерживает exteraGram», their caption, the badge
 * number on the arrow (or their custom emoji with a number pill), and what supporters get.
 * <p>
 * Ported from exteraGram's {@code SupporterBottomSheet} (exteraGram, GPLv3) without its donation part: the
 * «How to get» rows and the «Make a donation» button are left out, the button just closes the sheet.
 */
public class RawSupporterInfoSheet extends RawSupporterSheet {

    public RawSupporterInfoSheet(BaseFragment fragment, TLObject peer, Theme.ResourcesProvider resourcesProvider) {
        super(fragment, resourcesProvider);
        RawBadges.Badge badge = RawSupporters.hasCustomBadge(peer) ? RawBadges.get(peer) : null;
        if (badge != null) {
            setBadge(badge.documentId);
            setContent(formatOtherTitle(peer), null);
            addNumberPill(RawSupporters.getNumber(peer));
            if (!TextUtils.isEmpty(badge.text)) {
                addCaptionBubble(RawSupporters.formatCaption(badge.text, DialogObject.getName(peer), fragment, this::dismiss));
            }
            setCloseButton();
            commit();
            return;
        }

        long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        boolean self = peer == null || peer instanceof TLRPC.User && ((TLRPC.User) peer).id == selfId;
        if (self) {
            peer = UserConfig.getInstance(currentAccount).getCurrentUser();
        }
        RawBadges.Badge otherBadge = self ? null : RawBadges.get(peer);
        String caption = otherBadge != null ? otherBadge.text : null;
        setContent(formatOtherTitle(peer), RawBadgeText.get(R.string.RawSupporterOtherSubtitle));
        if (!TextUtils.isEmpty(caption)) {
            addCaptionBubble(RawSupporters.formatCaption(caption, DialogObject.getName(peer), fragment, this::dismiss));
        }
        int number = self ? 0 : RawSupporters.getNumber(peer);
        if (number > 0) {
            getArrowView().setNumber(RawSupporters.formatNumber(number));
        }
        RawSupporterPerks.addTo(fragment.getParentActivity(), this::addView, resourcesProvider, null, null, null);
        setCloseButton();
        commit();
    }

    private void setCloseButton() {
        button.setText(LocaleController.getString(R.string.GotIt), false);
        button.setOnClickListener(v -> dismiss());
    }

    private CharSequence formatOtherTitle(TLObject peer) {
        return AndroidUtilities.replaceSingleLink(RawBadgeText.format(R.string.RawSupporterOtherTitle, DialogObject.getName(peer)),
                getThemedColor(Theme.key_featuredStickers_addButton));
    }
}
