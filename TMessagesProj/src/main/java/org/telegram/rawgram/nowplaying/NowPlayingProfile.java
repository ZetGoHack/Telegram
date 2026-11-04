package org.telegram.rawgram.nowplaying;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.rawgram.RawBadges;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.RecyclerListView;

import java.util.Objects;

/**
 * Everything the «Сейчас играет» card adds to {@code ProfileActivity}: the refresh loop, the loaded card and the list
 * row. Ported from exteraGram's {@code ProfileActivity.checkNowPlaying} and its row / adapter hooks (exteraGram, GPLv3).
 * <p>
 * While the profile is on screen the track is checked every {@link #UPDATE_INTERVAL_MS}; the request is cancelled on
 * pause and when the profile closes. With the switch off nothing is requested and no row is added.
 */
public final class NowPlayingProfile {

    /** ProfileActivity list view type of the card (above Telegram's own types). */
    public static final int VIEW_TYPE = 95;

    private static final long UPDATE_INTERVAL_MS = 15_000L;
    /** First check after resume, and the shortest time before a new card shows (lets the open animation finish). */
    private static final long MIN_DELAY_MS = 325L;

    private final BaseFragment fragment;
    private final Utilities.Callback0Return<Long> userId;
    private final Utilities.Callback0Return<TLRPC.UserFull> userInfo;
    private final Utilities.Callback0Return<Boolean> myProfile;
    private final Runnable onChanged;
    private final Runnable onSavedMusicClick;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            View view = fragment.getFragmentView();
            if (view == null || !view.isAttachedToWindow()) {
                return;
            }
            check(false);
            handler.postDelayed(this, UPDATE_INTERVAL_MS);
        }
    };

    private NowPlayingCardData cardData;
    private NowPlayingController.Request request;
    private boolean loading;
    private Runnable pendingShow;
    private boolean destroyed;

    /**
     * @param onChanged         re-lays the profile list ({@code updateListAnimated(false)})
     * @param onSavedMusicClick plays the saved music, like a tap on the header's music row
     */
    public NowPlayingProfile(BaseFragment fragment, Utilities.Callback0Return<Long> userId, Utilities.Callback0Return<TLRPC.UserFull> userInfo,
                             Utilities.Callback0Return<Boolean> myProfile, Runnable onChanged, Runnable onSavedMusicClick) {
        this.fragment = fragment;
        this.userId = userId;
        this.userInfo = userInfo;
        this.myProfile = myProfile;
        this.onChanged = onChanged;
        this.onSavedMusicClick = onSavedMusicClick;
    }

    // ---------------------------------------------------------------- lifecycle hooks

    public void onResume() {
        if (!NowPlayingController.isEnabled()) {
            return;
        }
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, MIN_DELAY_MS);
    }

    public void onPause() {
        handler.removeCallbacks(refresh);
    }

    public void onFragmentDestroy() {
        destroyed = true;
        handler.removeCallbacks(refresh);
        cancelRequest();
        if (pendingShow != null) {
            handler.removeCallbacks(pendingShow);
            pendingShow = null;
        }
    }

    private void cancelRequest() {
        if (request != null && request.isActive()) {
            request.cancel();
        }
        request = null;
        loading = false;
    }

    // ---------------------------------------------------------------- check

    /** exteraGram's {@code checkNowPlaying}: {@code force} restarts a running check (user info just loaded). */
    public void check(boolean force) {
        if (destroyed) {
            return;
        }
        if (!NowPlayingController.isEnabled()) {
            if (cardData != null) {
                cardData = null;
                onChanged.run();
            }
            return;
        }
        long id = userId.run();
        boolean mine = myProfile.run();
        if ((id == fragment.getUserConfig().getClientUserId() && !mine) || id == 0) {
            return;
        }
        if (loading && !force) {
            return;
        }
        if (request != null && request.isActive()) {
            request.cancel();
            loading = false;
        }
        final TLRPC.User user = fragment.getMessagesController().getUser(id);
        if (user == null) {
            return;
        }
        if (UserObject.isUserSelf(user) && !mine) {
            return;
        }
        TLRPC.UserFull full = userInfo.run();
        final TLRPC.Document savedMusic = full != null ? full.saved_music : null;
        loading = true;
        request = NowPlayingController.getCurrentPlayingTrack(id, savedMusic, RawBadges.get(user) != null,
                (track, elapsed) -> onTrack(savedMusic, user, track, elapsed));
    }

    private void onTrack(TLRPC.Document savedMusic, TLRPC.User user, NowPlayingTrack track, long elapsed) {
        if (fragment.getParentActivity() == null) {
            loading = false;
            return;
        }
        if (track == null || !track.isPlaying) {
            loading = false;
            if (cardData != null) {
                cardData = null;
                onChanged.run();
            }
            return;
        }
        if (cardData != null && Objects.equals(cardData.track.trackName, track.trackName) && Objects.equals(cardData.track.artists, track.artists)) {
            loading = false;
            return;
        }
        if (track.isTelegram() && NowPlayingController.isSeparateStylesSupported()) {
            // the card would stay hidden (Telegram's own music row shows the saved music): skip loading the cover
            loading = false;
            if (cardData != null) {
                cardData = null;
                onChanged.run();
            }
            return;
        }
        NowPlayingCardData.create(track, savedMusic, data -> {
            if (destroyed) {
                return;
            }
            if (pendingShow != null) {
                handler.removeCallbacks(pendingShow);
            }
            pendingShow = () -> {
                pendingShow = null;
                loading = false;
                if (fragment.getParentActivity() == null) {
                    return;
                }
                if (user.profile_color != null) {
                    data.userEmoji = user.profile_color.background_emoji_id;
                }
                cardData = data;
                onChanged.run();
            };
            handler.postDelayed(pendingShow, elapsed < MIN_DELAY_MS ? MIN_DELAY_MS - elapsed : 0);
        });
    }

    // ---------------------------------------------------------------- list row

    /** Whether the card row is in the list ({@code updateRowsIds}). */
    public boolean shouldShowRow() {
        return NowPlayingController.isEnabled() && NowPlayingController.shouldShowCard(cardData);
    }

    /** The card view for {@link #VIEW_TYPE} (it draws its own section, so the list leaves it without one). */
    public View createView(Context context, Theme.ResourcesProvider resourcesProvider) {
        NowPlayingCard card = new NowPlayingCard(context, resourcesProvider) {
            @Override
            public void onSavedMusicClick() {
                if (onSavedMusicClick != null) {
                    onSavedMusicClick.run();
                }
            }
        };
        card.setTag(RecyclerListView.TAG_NOT_SECTION);
        return card;
    }

    public void bind(View view) {
        if (view instanceof NowPlayingCard && cardData != null) {
            ((NowPlayingCard) view).set(cardData);
        }
    }
}
