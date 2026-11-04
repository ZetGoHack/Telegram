package org.telegram.rawgram.badges;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.rawgram.RawBadgeEditor;
import org.telegram.rawgram.RawBadges;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.LaunchActivity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supporter side of exteraGram's badges: who counts as a supporter, the badge number, and what a tap on a badge
 * opens (the supporter sheet, the owner's «Твои бонусы» sheet, or the caption bulletin).
 * <p>
 * Ported from exteraGram's {@code BadgesController} (getNumber / formatNumber / hasCustomBadge / getCustomBadgeId /
 * openProfileBadge) and {@code SupporterBottomSheet.showAlert} (exteraGram, GPLv3).
 */
public final class RawSupporters {

    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)\\)");

    private RawSupporters() {
    }

    // ---------------------------------------------------------------- lookup

    private static long peerId(TLObject peer) {
        if (peer instanceof TLRPC.User) {
            return ((TLRPC.User) peer).id;
        } else if (peer instanceof TLRPC.Chat) {
            return ((TLRPC.Chat) peer).id;
        }
        return 0;
    }

    /** Supporter or developer (exteraGram's isSupporter). */
    public static boolean isSupporter(long id) {
        int status = RawBadges.status(id);
        return status == RawBadges.STATUS_SUPPORTER || status == RawBadges.STATUS_DEVELOPER;
    }

    public static boolean isDeveloper(long id) {
        return RawBadges.status(id) == RawBadges.STATUS_DEVELOPER;
    }

    /** Badge number of a supporter, or 0 (not shown for others even when the list has one). */
    public static int getNumber(long id) {
        int number = RawBadges.rawNumber(id);
        return number > 0 && isSupporter(id) ? number : 0;
    }

    public static int getNumber(TLObject peer) {
        long id = peerId(peer);
        return id == 0 ? 0 : getNumber(id);
    }

    public static String formatNumber(int number) {
        return "#" + number;
    }

    /** The badge's emoji when it is not the default developer / supporter one, else 0. */
    public static long getCustomBadgeId(TLObject peer) {
        long id = peerId(peer);
        RawBadges.Badge badge = id == 0 ? null : RawBadges.get(peer);
        if (badge == null) {
            return 0;
        }
        long defaultId = isDeveloper(id) ? RawBadges.DEV_BADGE_ID : RawBadges.SUPPORTER_BADGE_ID;
        return badge.documentId == defaultId ? 0 : badge.documentId;
    }

    /** A badge that is not the plain supporter arrow: someone's own emoji, or a badge given without supporting. */
    public static boolean hasCustomBadge(TLObject peer) {
        long id = peerId(peer);
        return id != 0 && !isDeveloper(id) && (!isSupporter(id) || getCustomBadgeId(peer) != 0);
    }

    // ---------------------------------------------------------------- opening

    /**
     * A tap on someone's badge: your own (as a supporter) opens «Твои бонусы»; a developer's or a custom badge shows
     * its caption in a bulletin (with a button to the supporter sheet unless it is a developer); a plain supporter
     * badge opens the supporter sheet right away.
     */
    public static void openProfileBadge(BaseFragment fragment, TLRPC.User user, Theme.ResourcesProvider resourcesProvider, RawSupporterEnterFrom enterFrom) {
        if (fragment == null || user == null) {
            return;
        }
        boolean developer = isDeveloper(user.id);
        if (!developer && user.id == UserConfig.getInstance(fragment.getCurrentAccount()).getClientUserId() && isSupporter(user.id)) {
            showAlert(fragment, null, resourcesProvider, enterFrom);
            return;
        }
        if (developer || hasCustomBadge(user)) {
            if (RawBadges.get(user) == null) {
                return;
            }
            RawBadges.showInfo(fragment, user, developer ? null : () -> showAlert(fragment, user, resourcesProvider, null));
            return;
        }
        showAlert(fragment, user, resourcesProvider, enterFrom);
    }

    /**
     * Your own badge outside the profile (the side menu header): the badge editor when you may pick your badge,
     * else what a tap on it opens in the profile.
     */
    public static void onOwnBadgeTap(SimpleTextView name) {
        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null) {
            return;
        }
        TLRPC.User self = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
        if (self != null && RawBadges.canChangeBadge(self.id)) {
            RawBadgeEditor.show(fragment, name);
        } else {
            openProfileBadge(fragment, self, fragment.getResourceProvider(), null);
        }
    }

    /** A chat's badge: the caption bulletin, with the supporter sheet behind a button unless it is an official one. */
    public static void openChatBadge(BaseFragment fragment, TLRPC.Chat chat, Theme.ResourcesProvider resourcesProvider) {
        if (fragment == null || chat == null) {
            return;
        }
        boolean official = isDeveloper(chat.id);
        RawBadges.showInfo(fragment, chat, official ? null : () -> showAlert(fragment, chat, resourcesProvider, null));
    }

    /**
     * The supporter sheet about {@code peer}, or, for {@code peer == null}, your own «Твои бонусы» when you are a
     * supporter (nothing otherwise: rawGram has no donation screen).
     */
    public static BottomSheet showAlert(BaseFragment fragment, TLObject peer, Theme.ResourcesProvider resourcesProvider, RawSupporterEnterFrom enterFrom) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return null;
        }
        if (peer == null) {
            int account = fragment.getCurrentAccount();
            if (!isSupporter(UserConfig.getInstance(account).getClientUserId())) {
                return null;
            }
            return RawSupporterWelcomeSheet.show(fragment, RawSupporterWelcomeSheet.MODE_PERKS, resourcesProvider, enterFrom,
                    getCustomBadgeId(UserConfig.getInstance(account).getCurrentUser()));
        }
        RawSupporterInfoSheet sheet = new RawSupporterInfoSheet(fragment, peer, resourcesProvider);
        sheet.setEnterFrom(enterFrom);
        return fragment.showDialog(sheet) != null ? sheet : null;
    }

    // ---------------------------------------------------------------- caption

    /**
     * A badge caption as exteraGram shows it: {@code {name}} becomes the holder's name, {@code **bold**},
     * {@code [text](url)} and {@code @username} work. {@code onClick} runs before a link opens (closes the sheet).
     */
    public static CharSequence formatCaption(String text, String name, BaseFragment fragment, Runnable onClick) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        String replaced = text.replace("{name}", name == null ? "" : name);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        Matcher matcher = MARKDOWN_LINK.matcher(replaced);
        int last = 0;
        while (matcher.find()) {
            sb.append(replaced, last, matcher.start());
            int start = sb.length();
            sb.append(matcher.group(1));
            final String url = matcher.group(2);
            sb.setSpan(new URLSpanNoUnderline(url) {
                @Override
                public void onClick(View widget) {
                    if (onClick != null) {
                        onClick.run();
                    }
                    Browser.openUrl(widget.getContext(), url);
                }
            }, start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = matcher.end();
        }
        sb.append(replaced, last, replaced.length());
        return AndroidUtilities.replaceTags(new SpannableStringBuilder(RawBadges.linkUsernames(sb, fragment, onClick)));
    }
}
