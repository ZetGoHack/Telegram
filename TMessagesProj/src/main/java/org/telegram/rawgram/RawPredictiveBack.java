package org.telegram.rawgram;

import android.animation.ValueAnimator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarLayout;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.inset.WindowInsetsInAppController;

import java.util.WeakHashMap;

/**
 * Predictive back for the emoji / sticker panel in chats: while the back gesture is held the panel sinks
 * by up to 10 % of its height; letting go hides it, cancelling brings it back. Without it the gesture
 * previews leaving the chat although back would only close the panel.
 * <p>
 * Ported from exteraGram (utils.ui.PredictiveBackHandler, ChatActivity.getPredictiveBackHandler,
 * ChatActivityEnterView.popupBackHandler / setPopupBackProgress and the LaunchActivity back callback), GPLv3.
 * Hooks: LaunchActivity's OnBackAnimationCallback, ChatActivity.onBackPressed (popup branch),
 * ChatActivityEnterView (gesture check, insets controller), WindowInsetsStateHolder (inset shrink).
 */
public final class RawPredictiveBack {

    /** A screen part that takes over the back gesture instead of the fragment transition. */
    public interface Handler {
        void onBackProgress(float progress);

        void onBackCancelled();

        /** Finishes the gesture; false when back has to go to the fragment after all. */
        boolean onBackInvoked();
    }

    private static Handler current;
    private static boolean popupBranch;

    private RawPredictiveBack() {
    }

    // ---- LaunchActivity's OnBackAnimationCallback ----

    /** onBackStarted. */
    public static void reset() {
        current = null;
    }

    /** onBackStartedInternal, once the gesture really starts; true when a handler took it over. */
    public static boolean start(ActionBarLayout layout) {
        current = null;
        if (layout == null || !RawMotion.active()) {
            return false;
        }
        current = handlerFor(layout.getLastFragment());
        return current != null;
    }

    /** onBackProgressed; true when a handler consumed it. */
    public static boolean progress(float progress) {
        if (current == null) {
            return false;
        }
        current.onBackProgress(progress);
        return true;
    }

    /** onBackCancelled, or back swallowed by an overlay; true when a handler consumed it. */
    public static boolean cancel() {
        Handler handler = current;
        current = null;
        if (handler == null) {
            return false;
        }
        handler.onBackCancelled();
        return true;
    }

    /** onBackInvoked after the activity let back through; true when a handler finished it. */
    public static boolean invoke() {
        Handler handler = current;
        current = null;
        return handler != null && handler.onBackInvoked();
    }

    // ---- chat emoji panel ----

    /** ChatActivity.onBackPressed(false) reached the «hide the panel» branch. */
    public static void onPopupBackChecked() {
        popupBranch = true;
    }

    private static Handler handlerFor(BaseFragment fragment) {
        if (!(fragment instanceof ChatActivity)) {
            return null;
        }
        ChatActivityEnterView enterView = ((ChatActivity) fragment).getChatActivityEnterView();
        if (enterView == null || !enterView.rawgramCanHidePopupByBackGesture()) {
            return null;
        }
        // back must go to the panel, not to a sheet, a selection or a menu above it
        popupBranch = false;
        fragment.onBackPressed(false);
        if (!popupBranch) {
            return null;
        }
        PopupHandler handler = popupHandlers.get(enterView);
        if (handler == null) {
            handler = new PopupHandler(enterView);
            popupHandlers.put(enterView, handler);
        }
        return handler;
    }

    private static final WeakHashMap<ChatActivityEnterView, PopupHandler> popupHandlers = new WeakHashMap<>();

    private static final class PopupHandler implements Handler {
        /** The panel inset shrinks by at most this share. */
        private static final float MAX_SHRINK = 0.1f;
        private static final Interpolator INTERPOLATOR = new PathInterpolator(0.1f, 0.1f, 0f, 1f);

        private final java.lang.ref.WeakReference<ChatActivityEnterView> enterViewRef;
        private ValueAnimator animator;
        private float progress;

        PopupHandler(ChatActivityEnterView enterView) {
            enterViewRef = new java.lang.ref.WeakReference<>(enterView);
        }

        private WindowInsetsInAppController controller() {
            ChatActivityEnterView enterView = enterViewRef.get();
            return enterView != null ? enterView.rawgramInAppController() : null;
        }

        private void setProgress(float value) {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            apply(value);
        }

        private void apply(float value) {
            progress = value;
            WindowInsetsInAppController controller = controller();
            if (controller != null) {
                controller.setInAppKeyboardBackProgress(value);
            }
        }

        @Override
        public void onBackProgress(float p) {
            setProgress(INTERPOLATOR.getInterpolation(Utilities.clamp01(p)) * MAX_SHRINK);
        }

        @Override
        public void onBackCancelled() {
            if (progress == 0f) {
                return;
            }
            if (animator != null) {
                animator.cancel();
            }
            ValueAnimator a = ValueAnimator.ofFloat(progress, 0f);
            a.addUpdateListener(va -> apply((float) va.getAnimatedValue()));
            a.setInterpolator(CubicBezierInterpolator.DEFAULT);
            a.setDuration(200);
            animator = a;
            a.start();
        }

        @Override
        public boolean onBackInvoked() {
            ChatActivityEnterView enterView = enterViewRef.get();
            if (enterView == null || !enterView.canHidePopupByBackButton()) {
                onBackCancelled();
                return false;
            }
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            // the inset keeps its shrink until the panel's hide animation resets it
            progress = 0f;
            enterView.hidePopup(true);
            return true;
        }
    }
}
