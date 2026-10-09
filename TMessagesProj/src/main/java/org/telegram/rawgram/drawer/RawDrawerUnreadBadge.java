package org.telegram.rawgram.drawer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;
import android.widget.TextView;

import org.telegram.ui.ActionBar.Theme;

/**
 * The grey unread counter at the end of a menu row (the archive). The row's text gets an end padding while it shows.
 * Ported from exteraGram's drawer (exteraSquad, GPL).
 */
final class RawDrawerUnreadBadge {

    private final RectF rect = new RectF();
    private int counter;
    private boolean visible;
    private String text;
    private int textWidth;
    private int badgeWidth;
    private int defaultTextPaddingEnd = Integer.MIN_VALUE;

    void bind(int counter, TextView textView) {
        this.counter = counter;
        update(textView);
    }

    void update(TextView textView) {
        if (defaultTextPaddingEnd == Integer.MIN_VALUE) {
            defaultTextPaddingEnd = textView.getPaddingEnd();
        }
        visible = false;
        text = null;
        textWidth = 0;
        badgeWidth = 0;
        if (counter <= 0) {
            applyTextPadding(textView, defaultTextPaddingEnd);
            return;
        }
        visible = true;
        text = Integer.toString(counter);
        textWidth = (int) Math.ceil(Theme.dialogs_countTextPaint.measureText(text));
        badgeWidth = Math.max(dp(10), textWidth) + dp(14);
        applyTextPadding(textView, defaultTextPaddingEnd + badgeWidth + dp(12));
    }

    void draw(View view, Canvas canvas) {
        if (!visible) {
            return;
        }
        float top = dp(12.5f);
        float left = view.getMeasuredWidth() - dp(16.5f) - badgeWidth;
        rect.set(left, top, left + badgeWidth, top + dp(23));
        canvas.drawRoundRect(rect, dp(11.5f), dp(11.5f), Theme.dialogs_countGrayPaint);
        canvas.drawText(text, rect.left + (rect.width() - textWidth) / 2f, top + dp(16), Theme.dialogs_countTextPaint);
    }

    private static void applyTextPadding(TextView textView, int paddingEnd) {
        if (textView.getPaddingEnd() != paddingEnd) {
            textView.setPaddingRelative(textView.getPaddingStart(), textView.getPaddingTop(), paddingEnd, textView.getPaddingBottom());
        }
    }
}
