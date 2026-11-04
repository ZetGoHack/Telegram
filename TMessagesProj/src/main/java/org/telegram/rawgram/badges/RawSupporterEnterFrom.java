package org.telegram.rawgram.badges;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.Cells.ChatMessageCell;

/**
 * Where a supporter sheet's header flies in from: the tapped badge drawable and its centre on screen.
 * <p>
 * Ported from exteraGram's {@code SupporterEnterFrom} (exteraGram, GPLv3). exteraGram reads the centre from
 * coordinates it added to {@code SimpleTextView} / {@code ChatMessageCell}; here it comes from the drawable bounds
 * those views set while drawing, which is the same point.
 */
public final class RawSupporterEnterFrom {

    public final View view;
    public final Drawable drawable;
    public final float screenX;
    public final float screenY;
    public final float scale;

    private RawSupporterEnterFrom(View view, Drawable drawable, float x, float y) {
        this.view = view;
        this.drawable = drawable;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        scale = view.getScaleX();
        screenX = location[0] + x * scale;
        screenY = location[1] + y * view.getScaleY();
    }

    private static RawSupporterEnterFrom ofBounds(View view, Drawable drawable) {
        if (view == null || drawable == null) {
            return null;
        }
        Rect bounds = drawable.getBounds();
        if (bounds.isEmpty()) {
            return null;
        }
        return new RawSupporterEnterFrom(view, drawable, bounds.centerX(), bounds.centerY());
    }

    /** The emoji-status slot after the sender name of a bubble. */
    public static RawSupporterEnterFrom of(ChatMessageCell cell) {
        return cell == null ? null : ofBounds(cell, cell.currentNameStatusDrawable);
    }

    /** The slot left of the sender name of a bubble (badge next to an emoji status). */
    public static RawSupporterEnterFrom ofSecondary(ChatMessageCell cell) {
        return cell == null ? null : ofBounds(cell, cell.currentNameEmojiStatusDrawable);
    }

    /** A badge in one of the right slots of a name. */
    public static RawSupporterEnterFrom of(SimpleTextView view, Drawable drawable) {
        if (view == null || drawable == null) {
            return null;
        }
        if (view.getRightDrawable() == drawable) {
            return new RawSupporterEnterFrom(view, drawable, view.rightDrawableX, view.rightDrawableY);
        }
        if (view.getRightDrawable2() == drawable) {
            return ofBounds(view, drawable);
        }
        return null;
    }

    public void invalidate() {
        if (view instanceof ChatMessageCell) {
            ((ChatMessageCell) view).invalidateOutbounds();
        } else {
            view.invalidate();
        }
    }
}
