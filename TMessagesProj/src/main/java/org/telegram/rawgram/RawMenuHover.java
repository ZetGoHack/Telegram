package org.telegram.rawgram;

import android.annotation.SuppressLint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;

import java.lang.ref.WeakReference;

/**
 * «Press and drag» for {@link org.telegram.ui.Components.ItemOptions} menus (popup and {@code ScrimOptions}): the finger
 * that long-pressed the anchor keeps going into the opened menu, the item under it is highlighted (with a tick of
 * haptics), and releasing over an item selects it without lifting the finger first.
 * <p>
 * Over stock Telegram this also follows the gesture when the anchor sits in a {@link RecyclerView} (the list takes the
 * gesture over through an item touch listener instead of scrolling), and it arms only after the finger moved past the
 * touch slop, so lifting the finger right where the menu appeared doesn't pick the item under it.
 * <p>
 * Ported from exteraGram ({@code ItemOptions.installHoverReleaseListener}, {@code handleHoverEvent}).
 */
public final class RawMenuHover {

    /** Whether the menu is still on screen; the touch listeners drop themselves once it isn't. */
    public interface Shown {
        boolean isShown();
    }

    private View anchor;
    private View menu;
    private Shown shown;

    private View.OnTouchListener anchorListener;
    private RecyclerView listView;
    private RecyclerView.OnItemTouchListener listListener;

    private View hoveredItem;
    private final int[] loc = new int[2];
    private boolean armed;
    private float startX = Float.NaN, startY = Float.NaN;

    /** Starts following the current gesture from {@code anchor} into {@code menu}. */
    @SuppressLint("ClickableViewAccessibility")
    public void install(View anchor, View menu, @NonNull Shown shown) {
        clear();
        if (anchor == null || menu == null) {
            return;
        }
        this.anchor = anchor;
        this.menu = menu;
        this.shown = shown;
        resetArming();

        ViewParent list = anchor.getParent();
        while (list != null && !(list instanceof RecyclerView)) {
            list = list.getParent();
        }
        // the list may intercept (our item listener does), everything above it must not
        final ViewParent outer = list != null ? list.getParent() : anchor.getParent();
        if (list != null) {
            list.requestDisallowInterceptTouchEvent(false);
        }
        if (outer != null) {
            outer.requestDisallowInterceptTouchEvent(true);
        }

        final WeakReference<RawMenuHover> weakSelf = new WeakReference<>(this);
        anchor.setOnTouchListener(anchorListener = (v, event) -> {
            RawMenuHover self = weakSelf.get();
            if (self == null || !self.canHandle()) {
                v.setOnTouchListener(null);
                return false;
            }
            if (outer != null) {
                outer.requestDisallowInterceptTouchEvent(true);
            }
            if (self.handle(event)) {
                v.setOnTouchListener(null);
                if (self.anchorListener != null && self.anchor == v) {
                    self.anchorListener = null;
                }
            }
            return true;
        });

        if (list instanceof RecyclerView) {
            listView = (RecyclerView) list;
            listListener = new RecyclerView.SimpleOnItemTouchListener() {
                @Override
                public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                    RawMenuHover self = weakSelf.get();
                    if (self == null || !self.canHandle() || e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        removeLater(rv, this);
                        return false;
                    }
                    if (self.handle(e)) {
                        self.removeListListener();
                    }
                    return true;
                }

                @Override
                public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                    RawMenuHover self = weakSelf.get();
                    if (self == null) {
                        removeLater(rv, this);
                    } else if (self.handle(e)) {
                        self.removeListListener();
                    }
                }
            };
            listView.addOnItemTouchListener(listListener);
        }
    }

    /** Drops the listeners and the highlight (the menu was dismissed). */
    public void clear() {
        cancel();
        if (anchorListener != null && anchor != null) {
            anchor.setOnTouchListener(null);
        }
        anchorListener = null;
        removeListListener();
        anchor = null;
        menu = null;
        shown = null;
    }

    private boolean canHandle() {
        return menu != null && shown != null && shown.isShown();
    }

    /** @return true when the gesture is over (up or cancel) and the listener can go. */
    private boolean handle(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (isArmed(event)) {
                    update((int) event.getRawX(), (int) event.getRawY());
                }
                return false;
            case MotionEvent.ACTION_UP:
                if (isArmed(event)) {
                    release((int) event.getRawX(), (int) event.getRawY());
                } else {
                    cancel();
                }
                resetArming();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancel();
                resetArming();
                return true;
            default:
                return false;
        }
    }

    private boolean isArmed(MotionEvent event) {
        if (!armed && menu != null) {
            if (Float.isNaN(startX)) {
                startX = event.getRawX();
                startY = event.getRawY();
            } else {
                float slop = ViewConfiguration.get(menu.getContext()).getScaledTouchSlop();
                armed = Math.hypot(event.getRawX() - startX, event.getRawY() - startY) > slop;
            }
        }
        return armed;
    }

    private void resetArming() {
        startX = startY = Float.NaN;
        armed = false;
    }

    private void update(int rawX, int rawY) {
        View hit = findItemAt(menu, rawX, rawY);
        if (hit != hoveredItem) {
            if (hoveredItem != null) {
                hoveredItem.setPressed(false);
            }
            hoveredItem = hit;
            if (hit != null) {
                if (!RawHaptics.off()) {
                    hit.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
                }
                hit.setPressed(true);
            }
        }
        if (hoveredItem != null) {
            hoveredItem.getLocationOnScreen(loc);
            hoveredItem.drawableHotspotChanged(rawX - loc[0], rawY - loc[1]);
        }
    }

    private void release(int rawX, int rawY) {
        update(rawX, rawY);
        View target = hoveredItem;
        if (target != null) {
            hoveredItem = null;
            target.setPressed(false);
            if (target.isAttachedToWindow()) {
                target.performClick();
            }
        }
    }

    private void cancel() {
        if (hoveredItem != null) {
            hoveredItem.setPressed(false);
            hoveredItem = null;
        }
    }

    private void removeListListener() {
        if (listView != null && listListener != null) {
            removeLater(listView, listListener);
        }
        listView = null;
        listListener = null;
    }

    /** Not during the list's own dispatch, which iterates its listeners. */
    private static void removeLater(RecyclerView rv, RecyclerView.OnItemTouchListener listener) {
        AndroidUtilities.runOnUIThread(() -> rv.removeOnItemTouchListener(listener));
    }

    private static View findItemAt(View root, int rawX, int rawY) {
        if (root == null || root.getVisibility() != View.VISIBLE || !root.isAttachedToWindow()) {
            return null;
        }
        int[] pos = new int[2];
        root.getLocationOnScreen(pos);
        if (rawX < pos[0] || rawX >= pos[0] + root.getWidth() || rawY < pos[1] || rawY >= pos[1] + root.getHeight()) {
            return null;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View deeper = findItemAt(group.getChildAt(i), rawX, rawY);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        if (root.isClickable() && root.isEnabled() && !(root instanceof ActionBarPopupWindow.GapView)) {
            return root;
        }
        return null;
    }
}
