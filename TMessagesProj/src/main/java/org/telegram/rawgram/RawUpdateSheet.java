package org.telegram.rawgram;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.spoilers.SpoilersTextView;

/**
 * The update sheet for a rawGram release: title, «build N • size», the release post's text (with its formatting,
 * spoilers, links and custom emoji) and «Скачать» / «Напомнить позже». Ported from Telegram's standalone flavour
 * ({@code TMessagesProj_AppStandalone/.../ui/Components/UpdateAppAlertDialog.java}); the download goes through
 * {@link RawUpdates}, which shows its progress in {@link RawUpdateLayout}.
 */
public class RawUpdateSheet extends BottomSheet {

    private static final int BUTTONS_HEIGHT = 130;

    private final Activity activity;
    private final TLRPC.TL_help_appUpdate appUpdate;

    private final Drawable shadowDrawable;
    private final NestedScrollView scrollView;
    private final LinearLayout linearLayout;
    private final View shadow;
    private AnimatorSet shadowAnimation;

    private int scrollOffsetY;
    private final int[] location = new int[2];

    /** A full-width button: filled (the main action) or a plain text one under it. */
    private static class ButtonCell extends FrameLayout {

        final View background;
        private final TextView textView;
        private final boolean hasBackground;

        ButtonCell(Context context, boolean withoutBackground) {
            super(context);
            hasBackground = !withoutBackground;
            setBackground(null);

            background = new View(context);
            if (hasBackground) {
                background.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 4));
            }
            addView(background, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0, 16, withoutBackground ? 0 : 16, 16, 16));

            textView = new TextView(context);
            textView.setLines(1);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity(Gravity.CENTER);
            if (hasBackground) {
                textView.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
                textView.setTypeface(AndroidUtilities.bold());
            } else {
                textView.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            }
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setPadding(0, 0, 0, hasBackground ? 0 : AndroidUtilities.dp(13));
            addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(hasBackground ? 80 : 50), MeasureSpec.EXACTLY));
        }

        void setText(CharSequence text) {
            textView.setText(text);
        }
    }

    public RawUpdateSheet(Activity activity, TLRPC.TL_help_appUpdate update) {
        super(activity, false);
        this.activity = activity;
        appUpdate = update;
        setCanceledOnTouchOutside(false);
        setApplyTopPadding(false);
        setApplyBottomPadding(false);

        Context context = activity;
        shadowDrawable = context.getResources().getDrawable(R.drawable.sheet_shadow_round).mutate();
        shadowDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogBackground), PorterDuff.Mode.MULTIPLY));

        FrameLayout root = new FrameLayout(context) {
            @Override
            public void setTranslationY(float translationY) {
                super.setTranslationY(translationY);
                updateLayout();
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_DOWN && scrollOffsetY != 0 && ev.getY() < scrollOffsetY) {
                    dismiss();
                    return true;
                }
                return super.onInterceptTouchEvent(ev);
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                return !isDismissed() && super.onTouchEvent(e);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                int top = (int) (scrollOffsetY - backgroundPaddingTop - getTranslationY());
                shadowDrawable.setBounds(0, top, getMeasuredWidth(), getMeasuredHeight());
                shadowDrawable.draw(canvas);
            }
        };
        root.setWillNotDraw(false);
        containerView = root;

        linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        scrollView = new NestedScrollView(context) {

            private boolean ignoreLayout;

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int height = MeasureSpec.getSize(heightMeasureSpec);
                measureChildWithMargins(linearLayout, widthMeasureSpec, 0, heightMeasureSpec, 0);
                int contentHeight = linearLayout.getMeasuredHeight();
                int padding = (height / 5 * 2);
                int visiblePart = height - padding;
                if (contentHeight - visiblePart < AndroidUtilities.dp(90) || contentHeight < height / 2 + AndroidUtilities.dp(90)) {
                    padding = height - contentHeight;
                }
                if (padding < 0) {
                    padding = 0;
                }
                if (getPaddingTop() != padding) {
                    ignoreLayout = true;
                    setPadding(0, padding, 0, 0);
                    ignoreLayout = false;
                }
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                updateLayout();
            }

            @Override
            public void requestLayout() {
                if (ignoreLayout) {
                    return;
                }
                super.requestLayout();
            }

            @Override
            protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                updateLayout();
            }
        };
        scrollView.setFillViewport(true);
        scrollView.setWillNotDraw(false);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 0, 0, 0, BUTTONS_HEIGHT));
        scrollView.addView(linearLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));

        TextView titleView = new TextView(context);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText("Обновить rawGram");
        linearLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 23, 16, 23, 0));

        TextView versionView = new TextView(context);
        versionView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        versionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        versionView.setText(appUpdate.version + " • " + AndroidUtilities.formatFileSize(appUpdate.document.size));
        versionView.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        linearLayout.addView(versionView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 23, 0, 23, 5));

        SpoilersTextView changelogView = new SpoilersTextView(context);
        changelogView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        changelogView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        changelogView.setLinkTextColor(Theme.getColor(Theme.key_dialogTextLink));
        changelogView.setGravity(Gravity.LEFT | Gravity.TOP);
        changelogView.setText(changelog(changelogView));
        linearLayout.addView(changelogView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 23, 15, 23, 0));

        FrameLayout.LayoutParams shadowParams = new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, AndroidUtilities.getShadowHeight(), Gravity.BOTTOM | Gravity.LEFT);
        shadowParams.bottomMargin = AndroidUtilities.dp(BUTTONS_HEIGHT);
        shadow = new View(context);
        shadow.setBackgroundColor(Theme.getColor(Theme.key_dialogShadowLine));
        shadow.setAlpha(0.0f);
        shadow.setTag(1);
        root.addView(shadow, shadowParams);

        boolean downloaded = RawUpdates.isDownloaded();
        ButtonCell doneButton = new ButtonCell(context, false);
        doneButton.setText(LocaleController.getString(downloaded ? R.string.AppUpdateNow : R.string.AppUpdateDownloadNow));
        doneButton.background.setOnClickListener(v -> {
            if (RawUpdates.isDownloaded()) {
                RawUpdates.install(this.activity);
            } else {
                RawUpdates.download();
            }
            dismiss();
        });
        root.addView(doneButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.LEFT | Gravity.BOTTOM, 0, 0, 0, 50));

        ButtonCell laterButton = new ButtonCell(context, true);
        laterButton.setText(LocaleController.getString(R.string.AppUpdateRemindMeLater));
        laterButton.background.setOnClickListener(v -> dismiss());
        root.addView(laterButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.LEFT | Gravity.BOTTOM, 0, 0, 0, 0));
    }

    /** The post's text with its entities (custom emoji as animated spans), without the release tag. */
    private CharSequence changelog(TextView view) {
        if (TextUtils.isEmpty(appUpdate.text)) {
            return AndroidUtilities.replaceTags(LocaleController.getString(R.string.AppUpdateChangelogEmpty));
        }
        CharSequence text = Emoji.replaceEmoji(new SpannableStringBuilder(appUpdate.text), view.getPaint().getFontMetricsInt(), false);
        SpannableStringBuilder builder = text instanceof SpannableStringBuilder ? (SpannableStringBuilder) text : new SpannableStringBuilder(text);
        MessageObject.addEntitiesToText(builder, appUpdate.entities, false, false, false, false);
        MessageObject.replaceAnimatedEmoji(builder, appUpdate.entities, view.getPaint().getFontMetricsInt());
        RawUpdates.stripTag(builder);
        return builder;
    }

    private void runShadowAnimation(boolean show) {
        if (show && shadow.getTag() != null || !show && shadow.getTag() == null) {
            shadow.setTag(show ? null : 1);
            if (show) {
                shadow.setVisibility(View.VISIBLE);
            }
            if (shadowAnimation != null) {
                shadowAnimation.cancel();
            }
            shadowAnimation = new AnimatorSet();
            shadowAnimation.playTogether(ObjectAnimator.ofFloat(shadow, View.ALPHA, show ? 1.0f : 0.0f));
            shadowAnimation.setDuration(150);
            shadowAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (shadowAnimation != null && shadowAnimation.equals(animation)) {
                        if (!show) {
                            shadow.setVisibility(View.INVISIBLE);
                        }
                        shadowAnimation = null;
                    }
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    if (shadowAnimation != null && shadowAnimation.equals(animation)) {
                        shadowAnimation = null;
                    }
                }
            });
            shadowAnimation.start();
        }
    }

    private void updateLayout() {
        if (linearLayout == null || shadow == null) {
            return; // the container's translation is set before the fields
        }
        View child = linearLayout.getChildAt(0);
        child.getLocationInWindow(location);
        int top = location[1] - AndroidUtilities.dp(24);
        int newOffset = Math.max(top, 0);
        runShadowAnimation(location[1] + linearLayout.getMeasuredHeight() > container.getMeasuredHeight() - AndroidUtilities.dp(113) + containerView.getTranslationY());
        if (scrollOffsetY != newOffset) {
            scrollOffsetY = newOffset;
            scrollView.invalidate();
        }
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return false;
    }
}
