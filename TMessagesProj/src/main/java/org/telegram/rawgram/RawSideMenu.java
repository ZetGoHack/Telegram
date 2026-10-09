package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Point;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.rawgram.drawer.RawDrawerActionCell;
import org.telegram.rawgram.drawer.RawDrawerAddCell;
import org.telegram.rawgram.drawer.RawDrawerProfileCell;
import org.telegram.rawgram.drawer.RawDrawerUserCell;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.ChannelCreateActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.GroupCreateActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.LoginActivity;

import java.util.ArrayList;

/**
 * «Боковое меню» (Nagram's navigationDrawerEnabled): the pre-12.4 Telegram side menu — profile header with the
 * night-mode switch, the accounts list, «Новая группа», «Контакты», «Звонки», «Избранное», «Архив», «Настройки» —
 * as a panel over the app, opened by the ☰ button of the chats list or a swipe from the left edge of it.
 * Telegram 12.4 removed the drawer from DrawerLayoutContainer, so this is its own overlay (one per LaunchActivity).
 */
public final class RawSideMenu extends FrameLayout {

    private static final int ITEM_NEW_GROUP = 1, ITEM_NEW_CHANNEL = 2, ITEM_CONTACTS = 3, ITEM_CALLS = 4,
            ITEM_SAVED = 5, ITEM_ARCHIVE = 6, ITEM_SETTINGS = 7, ITEM_RAWGRAM = 8;

    private static RawSideMenu instance;

    private final LaunchActivity activity;
    private final View dim;
    private final FrameLayout panel;
    private final RecyclerListView list;
    private final Adapter adapter;
    private final int panelWidth;
    private float progress;
    private ValueAnimator animator;
    private boolean accountsShown;

    public static boolean enabled() {
        return RawUiConfig.sideMenu();
    }

    /** LaunchActivity.onCreate, after the drawer container is added to the root. */
    public static void attach(LaunchActivity activity, FrameLayout root) {
        if (!enabled()) {
            return;
        }
        instance = new RawSideMenu(activity);
        root.addView(instance, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    /** DialogsActivity: the ☰ button. */
    public static boolean open() {
        if (instance == null) {
            return false;
        }
        instance.show(true);
        return true;
    }

    /** LaunchActivity.onBackPressed. */
    public static boolean closeIfOpen() {
        if (instance == null || instance.progress <= 0) {
            return false;
        }
        instance.show(false);
        return true;
    }

    private RawSideMenu(LaunchActivity activity) {
        super(activity);
        this.activity = activity;
        Point screen = AndroidUtilities.getRealScreenSize();
        panelWidth = AndroidUtilities.isTablet() ? dp(320) : Math.min(dp(320), Math.min(screen.x, screen.y) - dp(56));

        dim = new View(activity);
        dim.setBackgroundColor(0x99000000);
        dim.setAlpha(0);
        dim.setOnClickListener(v -> show(false));
        addView(dim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        panel = new FrameLayout(activity);
        panel.setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground));
        panel.setClickable(true);
        list = new RecyclerListView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.VERTICAL, false));
        list.setAdapter(adapter = new Adapter(activity));
        list.setVerticalScrollBarEnabled(false);
        list.setOnItemClickListener((view, position, x, y) -> onClick(view, position, x, y));
        panel.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        addView(panel, new LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT));

        setProgress(0);
    }

    private void setProgress(float p) {
        progress = p;
        panel.setTranslationX(-panelWidth * (1f - p));
        dim.setAlpha(p);
        setVisibility(p > 0 ? VISIBLE : GONE);
    }

    private void show(boolean open) {
        if (open) {
            bringToFront();
            panel.setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground));
            adapter.rebuild();
            AndroidUtilities.hideKeyboard(activity.getCurrentFocus());
        }
        animateTo(open ? 1f : 0f);
    }

    private void animateTo(float target) {
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(progress, target);
        animator.addUpdateListener(a -> setProgress((float) a.getAnimatedValue()));
        animator.setDuration((long) (RawMotion.isEnabled() ? 220 * Math.abs(target - progress) + 60 : 0));
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                animator = null;
            }
        });
        animator.start();
    }

    // ---- swipe from the left edge of the chats list ----

    private float downX, downY;
    private boolean tracking, dragging;

    /** LaunchActivity.dispatchTouchEvent, before the app gets it; returns true while the menu is being dragged open. */
    public static boolean onTouch(MotionEvent ev, boolean onChatsList, org.telegram.messenger.Utilities.CallbackReturn<MotionEvent, Boolean> superDispatch) {
        if (instance == null) {
            return false;
        }
        boolean wasDragging = instance.dragging;
        boolean consumed = instance.handleEdge(ev, onChatsList);
        if (consumed && !wasDragging) {
            // the app saw the start of this gesture: cancel it there
            MotionEvent cancel = MotionEvent.obtain(ev);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            superDispatch.run(cancel);
            cancel.recycle();
        }
        return consumed;
    }

    private boolean handleEdge(MotionEvent ev, boolean onChatsList) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = progress == 0 && onChatsList && ev.getX() < dp(24);
                dragging = false;
                downX = ev.getX();
                downY = ev.getY();
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) {
                    return false;
                }
                float dx = ev.getX() - downX;
                if (!dragging) {
                    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.abs(ev.getY() - downY) > slop && Math.abs(ev.getY() - downY) > dx) {
                        tracking = false;
                        return false;
                    }
                    if (dx > slop) {
                        dragging = true;
                        bringToFront();
                        adapter.rebuild();
                    } else {
                        return false;
                    }
                }
                setProgress(Math.max(0, Math.min(1, dx / panelWidth)));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean was = dragging;
                tracking = dragging = false;
                if (was) {
                    animateTo(progress > 0.35f ? 1f : 0f);
                    return true;
                }
                return false;
        }
        return dragging;
    }

    // ---- items ----

    private void present(BaseFragment fragment) {
        show(false);
        activity.presentFragment(fragment);
    }

    private void onClick(View view, int position, float x, float y) {
        Row row = adapter.rows.get(position);
        int account = UserConfig.selectedAccount;
        if (row.type == Adapter.TYPE_PROFILE) {
            RawDrawerProfileCell cell = (RawDrawerProfileCell) view;
            if (cell.isInAvatar(x, y)) {
                Bundle args = new Bundle();
                args.putLong("user_id", UserConfig.getInstance(account).getClientUserId());
                args.putBoolean("my_profile", true);
                present(new org.telegram.ui.ProfileActivity(args, null));
            } else {
                accountsShown = !accountsShown;
                cell.setAccountsShown(accountsShown, true);
                adapter.rebuild();
            }
        } else if (row.type == Adapter.TYPE_USER) {
            show(false);
            activity.switchToAccount(((RawDrawerUserCell) view).getAccountNumber(), true);
        } else if (row.type == Adapter.TYPE_ADD) {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (!UserConfig.getInstance(a).isClientActivated()) {
                    present(new LoginActivity(a));
                    return;
                }
            }
        } else if (row.type == Adapter.TYPE_ITEM) {
            Bundle args = new Bundle();
            switch (row.id) {
                case ITEM_NEW_GROUP:
                    present(new GroupCreateActivity(args));
                    break;
                case ITEM_NEW_CHANNEL:
                    args.putInt("step", 0);
                    present(new ChannelCreateActivity(args));
                    break;
                case ITEM_CONTACTS:
                    args.putBoolean("needFinishFragment", false);
                    present(new ContactsActivity(args));
                    break;
                case ITEM_CALLS:
                    present(new CallLogActivity());
                    break;
                case ITEM_SAVED:
                    args.putLong("user_id", UserConfig.getInstance(account).getClientUserId());
                    present(new ChatActivity(args));
                    break;
                case ITEM_ARCHIVE:
                    args.putInt("folderId", 1);
                    present(new DialogsActivity(args));
                    break;
                case ITEM_SETTINGS:
                    present(new org.telegram.ui.SettingsActivity());
                    break;
                case ITEM_RAWGRAM:
                    present(new RawgramSettingsActivity());
                    break;
            }
        }
    }

    private static final class Row {
        final int type, id, icon, account;
        final String text;

        Row(int type, int id, String text, int icon, int account) {
            this.type = type;
            this.id = id;
            this.text = text;
            this.icon = icon;
            this.account = account;
        }
    }

    private final class Adapter extends RecyclerListView.SelectionAdapter {
        static final int TYPE_PROFILE = 0, TYPE_USER = 1, TYPE_ADD = 2, TYPE_DIVIDER = 3, TYPE_ITEM = 4;

        private final Context context;
        final ArrayList<Row> rows = new ArrayList<>();

        Adapter(Context context) {
            this.context = context;
            rebuild();
        }

        @SuppressLint("NotifyDataSetChanged")
        void rebuild() {
            rows.clear();
            rows.add(new Row(TYPE_PROFILE, 0, null, 0, 0));
            if (accountsShown) {
                int count = 0;
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (UserConfig.getInstance(a).isClientActivated()) {
                        rows.add(new Row(TYPE_USER, 0, null, 0, a));
                        count++;
                    }
                }
                if (count < UserConfig.MAX_ACCOUNT_COUNT) {
                    rows.add(new Row(TYPE_ADD, 0, null, 0, 0));
                }
                rows.add(new Row(TYPE_DIVIDER, 0, null, 0, 0));
            }
            rows.add(new Row(TYPE_ITEM, ITEM_NEW_GROUP, "Новая группа", R.drawable.msg_groups, 0));
            rows.add(new Row(TYPE_ITEM, ITEM_NEW_CHANNEL, "Новый канал", R.drawable.msg_channel, 0));
            rows.add(new Row(TYPE_ITEM, ITEM_CONTACTS, "Контакты", R.drawable.msg_contacts, 0));
            rows.add(new Row(TYPE_ITEM, ITEM_CALLS, "Звонки", R.drawable.msg_calls, 0));
            rows.add(new Row(TYPE_ITEM, ITEM_SAVED, "Избранное", R.drawable.msg_saved, 0));
            rows.add(new Row(TYPE_ITEM, ITEM_ARCHIVE, "Архив", R.drawable.msg_archive, 0));
            rows.add(new Row(TYPE_DIVIDER, 0, null, 0, 0));
            rows.add(new Row(TYPE_ITEM, ITEM_SETTINGS, "Настройки", R.drawable.msg_settings_old, 0));
            rows.add(new Row(TYPE_ITEM, ITEM_RAWGRAM, "rawGram", R.drawable.msg_settings, 0));
            notifyDataSetChanged();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != TYPE_DIVIDER;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_PROFILE:
                    view = new RawDrawerProfileCell(context, new RawDrawerProfileCell.Host() {
                        @Override
                        public void close() {
                            show(false);
                        }

                        @Override
                        public void present(BaseFragment fragment) {
                            RawSideMenu.this.present(fragment);
                        }

                        @Override
                        public FrameLayout bulletinLayout() {
                            return RawSideMenu.this;
                        }
                    });
                    break;
                case TYPE_USER:
                    view = new RawDrawerUserCell(context);
                    break;
                case TYPE_ADD:
                    view = new RawDrawerAddCell(context);
                    break;
                case TYPE_DIVIDER:
                    view = new View(context) {
                        @Override
                        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(9), MeasureSpec.EXACTLY));
                        }

                        @Override
                        protected void onDraw(@NonNull android.graphics.Canvas canvas) {
                            canvas.drawLine(0, dp(4), getWidth(), dp(4), Theme.dividerPaint);
                        }
                    };
                    view.setWillNotDraw(false);
                    break;
                default:
                    view = new RawDrawerActionCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            switch (row.type) {
                case TYPE_PROFILE: {
                    int account = UserConfig.selectedAccount;
                    ((RawDrawerProfileCell) holder.itemView).setUser(
                            MessagesController.getInstance(account).getUser(UserConfig.getInstance(account).getClientUserId()), accountsShown);
                    break;
                }
                case TYPE_USER:
                    ((RawDrawerUserCell) holder.itemView).setAccount(row.account);
                    break;
                case TYPE_ITEM:
                    ((RawDrawerActionCell) holder.itemView).setTextAndIcon(row.id, row.text, row.icon);
                    break;
            }
        }
    }
}
