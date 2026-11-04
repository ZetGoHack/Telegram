package org.telegram.rawgram.nowplaying;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BotWebViewVibrationEffect;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.rawgram.RawNotify;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogRadioCell;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.CircularProgressDrawable;
import org.telegram.ui.Components.CrossfadeDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Linking your Last.fm / stats.fm account for the «Сейчас играет» card: pick a service, enter the username, done
 * sends it to the exteraGram API bot. Ported from exteraGram's {@code SetupNowPlayingActivity} (exteraGram, GPLv3).
 */
public class SetupNowPlayingActivity extends BaseFragment {

    private static final int DONE_BUTTON = 0;
    private static final int DONE_BUTTON_WIDTH_DP = 56;
    private static final long DONE_ANIMATION_MS = 180;
    private static final String LAST_FM_URL = "https://www.last.fm/";
    private static final String STATS_FM_URL = "https://stats.fm/";

    /** exteraGram's {@code NowPlayingState}: what the screen shows / what was loaded. */
    private static final class State {
        final NowPlayingService serviceType;
        final String username;

        State(NowPlayingService serviceType, String username) {
            this.serviceType = serviceType;
            this.username = username;
        }

        State withService(NowPlayingService service) {
            return new State(service, username);
        }

        State withUsername(String name) {
            return new State(serviceType, name);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof State)) {
                return false;
            }
            State s = (State) other;
            return serviceType == s.serviceType && Objects.equals(username, s.username);
        }

        @Override
        public int hashCode() {
            return Objects.hash(serviceType, username);
        }
    }

    private ActionBarMenuItem doneButton;
    private CrossfadeDrawable doneButtonDrawable;
    private State initialState;
    private State currentState = new State(NowPlayingService.NONE, null);
    private UniversalRecyclerView listView;
    private EditTextCell nowPlayingEdit;
    private int shiftDp = -4;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Сейчас играет");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed(true)) {
                        finishFragment();
                    }
                } else if (id == DONE_BUTTON) {
                    processDone(true);
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.adapter.setApplyBackground(false);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        nowPlayingEdit = new EditTextCell(context, LocaleController.getString(R.string.Username), false, false, -1, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                super.onTextChanged(newText);
                currentState = currentState.withUsername(newText.toString());
                checkDone(true);
            }
        };
        nowPlayingEdit.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        nowPlayingEdit.hideKeyboardOnEnter();

        NowPlayingController.getNowPlayingInfo(info -> {
            if (fragmentView == null) {
                return;
            }
            State state = info != null
                    ? new State(info.serviceType, info.username != null ? info.username : "")
                    : new State(NowPlayingService.NONE, "");
            currentState = state;
            initialState = state;
            nowPlayingEdit.setText(state.username);
            listView.adapter.update(true);
            checkDone(false);
        });

        Drawable done = ContextCompat.getDrawable(context, R.drawable.ic_ab_done);
        Drawable doneMutated = done != null ? done.mutate() : null;
        if (doneMutated != null) {
            doneMutated.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_actionBarDefaultIcon), PorterDuff.Mode.MULTIPLY));
        }
        doneButtonDrawable = new CrossfadeDrawable(doneMutated, new CircularProgressDrawable(Theme.getColor(Theme.key_actionBarDefaultIcon)));
        doneButton = actionBar.createMenu().addItemWithWidth(DONE_BUTTON, doneButtonDrawable, AndroidUtilities.dp(DONE_BUTTON_WIDTH_DP),
                LocaleController.getString(R.string.Done));
        checkDone(false);

        fragmentView = frameLayout;
        return frameLayout;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Сервис"));
        for (NowPlayingService service : NowPlayingService.values()) {
            items.add(UItem.asRadio(service.ordinal(), service.getDisplayName())
                    .setChecked(service == currentState.serviceType && initialState != null));
        }
        items.add(UItem.asShadow("Текущий трек показывается в профиле карточкой из сервиса скробблинга."));
        if (currentState.serviceType != NowPlayingService.NONE) {
            items.add(UItem.asHeader("Имя пользователя"));
            items.add(UItem.asCustom(nowPlayingEdit));
            items.add(UItem.asShadow(AndroidUtilities.replaceSingleTag("Имя пользователя можно узнать на **официальном сайте**.", () ->
                    Browser.openUrl(getParentActivity(), currentState.serviceType == NowPlayingService.LAST_FM ? LAST_FM_URL : STATS_FM_URL))));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (view instanceof DialogRadioCell) {
            NowPlayingService[] services = NowPlayingService.values();
            if (item.id < 0 || item.id >= services.length) {
                return;
            }
            currentState = currentState.withService(services[item.id]);
            listView.adapter.update(true);
            checkDone(true);
        }
    }

    private void processDone(boolean error) {
        if (doneButtonDrawable.getProgress() > 0f) {
            return;
        }
        if (error && currentState.serviceType != NowPlayingService.NONE) {
            CharSequence text = nowPlayingEdit.getText();
            if (TextUtils.isEmpty(text)) {
                BotWebViewVibrationEffect.APP_ERROR.vibrate();
                shiftDp = -shiftDp;
                AndroidUtilities.shakeViewSpring(nowPlayingEdit, shiftDp);
                return;
            }
        }
        doneButtonDrawable.animateToProgress(1f);
        final NowPlayingController.Info info = new NowPlayingController.Info(currentState.serviceType,
                currentState.serviceType == NowPlayingService.NONE ? null : currentState.username);
        NowPlayingController.updateNowPlayingInfo(getCurrentAccount(), info, ok -> {
            if (fragmentView == null) {
                return;
            }
            if (!ok) {
                doneButtonDrawable.animateToProgress(0f);
                RawNotify.warn("Не удалось сохранить");
            } else {
                finishFragment();
            }
        });
    }

    private void checkDone(boolean animated) {
        if (doneButton == null) {
            return;
        }
        boolean unchanged = Objects.equals(initialState, currentState);
        doneButton.setEnabled(!unchanged);
        float target = unchanged ? 0f : 1f;
        if (animated) {
            doneButton.animate().alpha(target).scaleX(target).scaleY(target).setDuration(DONE_ANIMATION_MS).start();
        } else {
            doneButton.setAlpha(target);
            doneButton.setScaleX(target);
            doneButton.setScaleY(target);
        }
    }
}
