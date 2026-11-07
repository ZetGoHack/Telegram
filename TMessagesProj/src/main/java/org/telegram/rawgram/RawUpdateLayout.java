package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MediaActionDrawable;
import org.telegram.ui.Components.RadialProgress2;
import org.telegram.ui.IUpdateLayout;

/**
 * The update bar over the bottom tabs for a pending rawGram release ({@link RawUpdates#pendingUpdate()}): «Обновить
 * rawGram» with the size, the download progress with cancel, then «Update now» that opens the installer. Ported from
 * Telegram's standalone flavour ({@code TMessagesProj_AppStandalone/.../ui/Components/UpdateLayout.java}); it follows
 * the download itself, since MainTabsActivity forwards file events only for Telegram's own update.
 */
public class RawUpdateLayout extends IUpdateLayout implements NotificationCenter.NotificationCenterDelegate {

    private FrameLayout updateLayout;
    private RadialProgress2 updateLayoutIcon;
    private AnimatedTextView updateTextView;
    private AnimatedTextView.AnimatedTextDrawable updateSizeTextView;

    private final Activity activity;
    private final ViewGroup sideMenuContainer;
    private int observedAccount = -1;

    public RawUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        super(activity, sideMenuContainer);
        this.activity = activity;
        this.sideMenuContainer = sideMenuContainer;
        if (sideMenuContainer != null) {
            sideMenuContainer.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(@NonNull View v) {
                    observe();
                }

                @Override
                public void onViewDetachedFromWindow(@NonNull View v) {
                    stopObserving();
                }
            });
            if (sideMenuContainer.isAttachedToWindow()) {
                observe();
            }
        }
    }

    private void observe() {
        int account = RawUpdates.pendingAccount();
        if (observedAccount == account) {
            return;
        }
        stopObserving();
        observedAccount = account;
        NotificationCenter center = NotificationCenter.getInstance(account);
        center.addObserver(this, NotificationCenter.fileLoaded);
        center.addObserver(this, NotificationCenter.fileLoadFailed);
        center.addObserver(this, NotificationCenter.fileLoadProgressChanged);
    }

    private void stopObserving() {
        if (observedAccount < 0) {
            return;
        }
        NotificationCenter center = NotificationCenter.getInstance(observedAccount);
        center.removeObserver(this, NotificationCenter.fileLoaded);
        center.removeObserver(this, NotificationCenter.fileLoadFailed);
        center.removeObserver(this, NotificationCenter.fileLoadProgressChanged);
        observedAccount = -1;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.fileLoadProgressChanged) {
            onProgress(args);
        } else if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadFailed) {
            if (isUpdateFile(args)) {
                updateAppUpdateViews(account, true);
            }
        }
    }

    private static boolean isUpdateFile(Object[] args) {
        TLRPC.TL_help_appUpdate update = RawUpdates.pendingUpdate();
        if (update == null || args == null || args.length == 0 || !(args[0] instanceof String)) {
            return false;
        }
        return args[0].equals(FileLoader.getAttachFileName(update.document));
    }

    /** MainTabsActivity forwards its account's progress here; the bar follows the download account itself. */
    @Override
    public void updateFileProgress(Object[] args) {
    }

    private void onProgress(Object[] args) {
        if (updateTextView == null || !isUpdateFile(args) || args.length < 3) {
            return;
        }
        long loadedSize = (Long) args[1];
        long totalSize = (Long) args[2];
        float loadProgress = totalSize > 0 ? loadedSize / (float) totalSize : 0f;
        updateLayoutIcon.setProgress(loadProgress, true);
        updateTextView.setText(LocaleController.formatString(R.string.AppUpdateDownloading, (int) (loadProgress * 100)));
    }

    @Override
    public void createUpdateUI(int currentAccount) {
        if (sideMenuContainer == null || updateLayout != null) {
            return;
        }
        updateLayout = new FrameLayout(activity);
        updateLayout.setVisibility(View.INVISIBLE);
        updateLayout.setTranslationY(dp(44));
        updateLayout.setBackground(Theme.getSelectorDrawable(0x40ffffff, false));
        sideMenuContainer.addView(updateLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.LEFT | Gravity.BOTTOM));
        updateLayout.setOnClickListener(v -> {
            if (RawUpdates.pendingUpdate() == null) {
                return;
            }
            int icon = updateLayoutIcon.getIcon();
            if (icon == MediaActionDrawable.ICON_DOWNLOAD) {
                RawUpdates.download();
            } else if (icon == MediaActionDrawable.ICON_CANCEL) {
                RawUpdates.cancelDownload();
            } else {
                RawUpdates.install(activity);
            }
            updateAppUpdateViews(currentAccount, true);
        });
        updateLayout.setOnLongClickListener(v -> {
            RawUpdates.showSheet(activity);
            return true;
        });

        updateTextView = new AnimatedTextView(activity, true, true, true) {
            @Override
            protected void onDraw(Canvas canvas) {
                updateSizeTextView.setBounds(0, 0, getMeasuredWidth() - dp(20), getMeasuredHeight());
                updateSizeTextView.draw(canvas);

                canvas.save();
                canvas.translate(dp(15), 0);
                super.onDraw(canvas);
                canvas.translate((getMeasuredWidth() - width()) / 2f - dp(30), dp(11));
                updateLayoutIcon.draw(canvas);
                canvas.restore();
            }

            @Override
            protected boolean verifyDrawable(@NonNull Drawable who) {
                return super.verifyDrawable(who) || who == updateSizeTextView;
            }
        };
        updateTextView.setTextSize(dp(15));
        updateTextView.setTypeface(AndroidUtilities.bold());
        updateTextView.setTextColor(0xffffffff);
        updateTextView.setGravity(Gravity.CENTER);
        updateLayout.addView(updateTextView, LayoutHelper.createFrameMatchParent());
        updateTextView.setText("Обновить rawGram", false);

        updateLayoutIcon = new RadialProgress2(updateTextView);
        updateLayoutIcon.setColors(0xffffffff, 0xffffffff, Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButton));
        updateLayoutIcon.setProgressRect(0, 0, dp(22), dp(22));
        updateLayoutIcon.setCircleRadius(dp(11));
        updateLayoutIcon.setAsMini();

        updateSizeTextView = new AnimatedTextView.AnimatedTextDrawable(true, true, true);
        updateSizeTextView.setCallback(updateTextView);
        updateSizeTextView.setTextSize(dp(14));
        updateSizeTextView.setTypeface(AndroidUtilities.bold());
        updateSizeTextView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        updateSizeTextView.setTextColor(0xccffffff);
    }

    @Override
    public void updateAppUpdateViews(int currentAccount, boolean animated) {
        if (sideMenuContainer == null) {
            return;
        }
        TLRPC.TL_help_appUpdate update = RawUpdates.pendingUpdate();
        if (update != null) {
            if (sideMenuContainer.isAttachedToWindow()) {
                observe(); // the pending account may have changed
            }
            createUpdateUI(currentAccount);

            String fileName = FileLoader.getAttachFileName(update.document);
            boolean showSize;
            if (RawUpdates.isDownloaded()) {
                updateLayoutIcon.setIcon(MediaActionDrawable.ICON_UPDATE, true, animated);
                updateTextView.setText(LocaleController.getString(R.string.AppUpdateNow), animated);
                showSize = false;
            } else if (RawUpdates.isDownloading()) {
                updateLayoutIcon.setIcon(MediaActionDrawable.ICON_CANCEL, true, animated);
                Float p = ImageLoader.getInstance().getFileProgress(fileName);
                updateLayoutIcon.setProgress(p != null ? p : 0.0f, false);
                updateTextView.setText(LocaleController.formatString(R.string.AppUpdateDownloading, (int) ((p != null ? p : 0.0f) * 100)), animated);
                showSize = false;
            } else {
                updateLayoutIcon.setIcon(MediaActionDrawable.ICON_DOWNLOAD, true, animated);
                updateTextView.setText("Обновить rawGram", animated);
                showSize = true;
            }
            updateSizeTextView.setText(showSize ? AndroidUtilities.formatFileSize(update.document.size) : null, animated);
            if (updateLayout.getTag() != null) {
                return;
            }
            updateLayout.setVisibility(View.VISIBLE);
            updateLayout.setTag(1);
            if (animated) {
                updateLayout.animate().translationY(0).setInterpolator(CubicBezierInterpolator.EASE_OUT).setListener(null).setDuration(180).start();
            } else {
                updateLayout.setTranslationY(0);
            }
        } else {
            if (updateLayout == null || updateLayout.getTag() == null) {
                return;
            }
            updateLayout.setTag(null);
            if (animated) {
                updateLayout.animate().translationY(dp(44)).setInterpolator(CubicBezierInterpolator.EASE_OUT).setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (updateLayout.getTag() == null) {
                            updateLayout.setVisibility(View.INVISIBLE);
                        }
                    }
                }).setDuration(180).start();
            } else {
                updateLayout.setTranslationY(dp(44));
                updateLayout.setVisibility(View.INVISIBLE);
            }
        }
    }
}
