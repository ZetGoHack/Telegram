package org.telegram.rawgram.drawer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

/**
 * One row of the side menu: an outline icon, a bold title and an optional unread counter, on a rounded ripple that
 * shrinks a little while pressed. Ported from exteraGram's drawer (exteraSquad, GPL).
 */
final class RawDrawerMenuItemView extends FrameLayout {

    static final int HEIGHT_DP = 48;

    private static final int COLOR_KEY_SELECTOR = Theme.key_listSelector;
    private static final int COLOR_KEY_ICON = Theme.key_windowBackgroundWhiteGrayIcon;
    private static final int COLOR_KEY_TEXT = Theme.key_windowBackgroundWhiteBlackText;

    private final ImageView iconView;
    private final TextView textView;
    private final RawDrawerUnreadBadge unreadBadge = new RawDrawerUnreadBadge();
    private RawDrawerItems.Counter counter;

    RawDrawerMenuItemView(Context context) {
        super(context);
        setWillNotDraw(false);
        setBackground(createSelectorDrawable());
        ScaleStateListAnimator.apply(this, 0.02f, 1.5f);

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER);
        iconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(COLOR_KEY_ICON), PorterDuff.Mode.SRC_IN));
        addView(iconView, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL, 20, 0, 0, 0));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setTextColor(Theme.getColor(COLOR_KEY_TEXT));
        textView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        textView.setSingleLine(true);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 68, 0, 16, 0));
    }

    void setItem(RawDrawerItems.Item item, int account) {
        counter = item.counter;
        iconView.setImageResource(item.icon);
        textView.setText(item.text);
        updateUnreadCounter(account);
    }

    void updateUnreadCounter(int account) {
        unreadBadge.bind(counter != null ? counter.get(account) : 0, textView);
        invalidate();
    }

    void updateColors() {
        setBackground(createSelectorDrawable());
        iconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(COLOR_KEY_ICON), PorterDuff.Mode.SRC_IN));
        textView.setTextColor(Theme.getColor(COLOR_KEY_TEXT));
        invalidate();
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);
        unreadBadge.draw(this, canvas);
    }

    private static Drawable createSelectorDrawable() {
        return new InsetDrawable(Theme.createRadSelectorDrawable(Theme.getColor(COLOR_KEY_SELECTOR), 12, 12), dp(8), dp(2), dp(8), dp(2));
    }
}
