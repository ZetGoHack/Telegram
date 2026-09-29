package org.telegram.rawgram;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SeekBarView;
import org.telegram.ui.Stories.recorder.StoryEntry;

import java.util.ArrayList;

/** "Настройки rawGram": long-press delay and sticker size with a live chat preview. */
public class RawgramSettingsActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SLIDER = 1;
    private static final int TYPE_INFO = 2;
    private static final int TYPE_PREVIEW = 3;

    private static final int ROW_PRESS_HEADER = 0;
    private static final int ROW_PRESS_SLIDER = 1;
    private static final int ROW_PRESS_INFO = 2;
    private static final int ROW_STICKER_HEADER = 3;
    private static final int ROW_STICKER_SLIDER = 4;
    private static final int ROW_STICKER_PREVIEW = 5;
    private static final int ROW_STICKER_INFO = 6;
    private static final int ROW_COUNT = 7;

    private RecyclerListView listView;
    private StickerPreviewCell previewCell;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Настройки rawGram");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemViewType(int position) {
            switch (position) {
                case ROW_PRESS_HEADER:
                case ROW_STICKER_HEADER:
                    return TYPE_HEADER;
                case ROW_PRESS_SLIDER:
                case ROW_STICKER_SLIDER:
                    return TYPE_SLIDER;
                case ROW_STICKER_PREVIEW:
                    return TYPE_PREVIEW;
                default:
                    return TYPE_INFO;
            }
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_HEADER) {
                view = new HeaderCell(context);
            } else if (viewType == TYPE_SLIDER) {
                view = new SliderCell(context);
            } else if (viewType == TYPE_PREVIEW) {
                view = previewCell = new StickerPreviewCell(context);
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(position == ROW_PRESS_HEADER ? "Задержка зажатия" : "Размер стикеров");
                    break;
                case TYPE_SLIDER: {
                    SliderCell cell = (SliderCell) holder.itemView;
                    if (position == ROW_PRESS_SLIDER) {
                        cell.bind(RawgramConfig.LONG_PRESS_MIN, RawgramConfig.LONG_PRESS_MAX, RawgramConfig.LONG_PRESS_STEP,
                                RawgramConfig.getLongPressDelay(), " мс", RawgramConfig::setLongPressDelay);
                    } else {
                        cell.bind(RawgramConfig.STICKER_SCALE_MIN, RawgramConfig.STICKER_SCALE_MAX, RawgramConfig.STICKER_SCALE_STEP,
                                RawgramConfig.getStickerScalePercent(), "%", value -> {
                                    RawgramConfig.setStickerScalePercent(value);
                                    if (previewCell != null) {
                                        previewCell.updateSticker();
                                    }
                                });
                    }
                    break;
                }
                case TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == ROW_PRESS_INFO) {
                        cell.setText("Сколько держать палец, чтобы открыть raw-просмотр инлайн-результата или превью стикера / GIF. "
                                + "Системная задержка на этом устройстве: " + ViewConfiguration.getLongPressTimeout() + " мс.");
                    } else {
                        cell.setText("Размер стикеров в чатах относительно стандартного размера Telegram.");
                    }
                    break;
                }
            }
        }
    }

    /** Seek bar with the current value drawn on the right. */
    private static class SliderCell extends FrameLayout {
        private final SeekBarView seekBar;
        private final TextView valueView;
        private int min, max, step;
        private String suffix;
        private Utilities.Callback<Integer> onChange;

        SliderCell(Context context) {
            super(context);
            seekBar = new SeekBarView(context);
            seekBar.setReportChanges(true);
            seekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
                @Override
                public void onSeekBarDrag(boolean stop, float progress) {
                    int value = valueFor(progress);
                    valueView.setText(value + suffix);
                    if (onChange != null) {
                        onChange.run(value);
                    }
                }

                @Override
                public int getStepsCount() {
                    return (max - min) / step;
                }
            });
            addView(seekBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.LEFT | Gravity.CENTER_VERTICAL, 5, 5, 72, 5));

            valueView = new TextView(context);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
            valueView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            addView(valueView, LayoutHelper.createFrame(72, LayoutHelper.MATCH_PARENT, Gravity.RIGHT, 0, 0, 16, 0));
        }

        private int valueFor(float progress) {
            int steps = (max - min) / step;
            return min + Math.round(progress * steps) * step;
        }

        void bind(int min, int max, int step, int value, String suffix, Utilities.Callback<Integer> onChange) {
            this.min = min;
            this.max = max;
            this.step = step;
            this.suffix = suffix;
            this.onChange = onChange;
            seekBar.setSeparatorsCount((max - min) / step + 1);
            seekBar.setProgress((value - min) / (float) (max - min));
            valueView.setText(value + suffix);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(48), MeasureSpec.EXACTLY));
        }
    }

    /** Chat wallpaper with an incoming text message and an outgoing sticker, laid out by ChatMessageCell. */
    private class StickerPreviewCell extends FrameLayout {
        private final LinearLayout messagesLayout;
        private ChatMessageCell stickerCell;
        private MessageObject stickerMessage;

        StickerPreviewCell(Context context) {
            super(context);
            setWillNotDraw(false);
            messagesLayout = new LinearLayout(context);
            messagesLayout.setOrientation(LinearLayout.VERTICAL);
            messagesLayout.setPadding(0, AndroidUtilities.dp(11), 0, AndroidUtilities.dp(11));
            addView(messagesLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            int account = UserConfig.selectedAccount;
            TLRPC.Document sticker = pickSticker(account);
            messagesLayout.addView(createCell(context, buildMessage(account, sticker != null ? "Покажи стикер" : "Нет недавних стикеров: отправь любой стикер, и он появится здесь", null, false)));
            if (sticker != null) {
                stickerMessage = buildMessage(account, "", sticker, true);
                stickerCell = createCell(context, stickerMessage);
                messagesLayout.addView(stickerCell);
            }
        }

        private ChatMessageCell createCell(Context context, MessageObject messageObject) {
            ChatMessageCell cell = new ChatMessageCell(context, UserConfig.selectedAccount);
            cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {});
            cell.setFullyDraw(true);
            cell.setMessageObject(messageObject, null, false, false, false);
            cell.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return cell;
        }

        void updateSticker() {
            if (stickerCell == null || stickerMessage == null) {
                return;
            }
            stickerMessage.forceUpdate = true;
            stickerCell.setMessageObject(stickerMessage, null, false, false, false);
            stickerCell.requestLayout();
            requestLayout();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Drawable wallpaper = Theme.getCachedWallpaperNonBlocking();
            if (wallpaper != null) {
                canvas.save();
                canvas.clipRect(0, 0, getWidth(), getHeight());
                StoryEntry.drawBackgroundDrawable(canvas, wallpaper, getWidth(), getHeight());
                canvas.restore();
            } else {
                canvas.drawColor(Theme.getColor(Theme.key_chat_wallpaper));
            }
            if (Theme.wallpaperLoadTask != null) {
                invalidate();
            }
        }
    }

    private static TLRPC.Document pickSticker(int account) {
        MediaDataController controller = MediaDataController.getInstance(account);
        ArrayList<TLRPC.Document> recent = controller.getRecentStickers(MediaDataController.TYPE_IMAGE);
        if (recent != null && !recent.isEmpty()) {
            return recent.get(0);
        }
        ArrayList<TLRPC.Document> faves = controller.getRecentStickers(MediaDataController.TYPE_FAVE);
        if (faves != null && !faves.isEmpty()) {
            return faves.get(0);
        }
        return null;
    }

    private static MessageObject buildMessage(int account, String text, TLRPC.Document sticker, boolean out) {
        long selfId = UserConfig.getInstance(account).getClientUserId();
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = out ? 2 : 1;
        message.date = (int) (System.currentTimeMillis() / 1000) - 60;
        message.dialog_id = 1;
        message.out = out;
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = out ? selfId : 0;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = out ? 0 : selfId;
        message.message = text;
        message.flags |= 256;
        if (sticker != null) {
            TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
            media.document = sticker;
            media.flags |= 1;
            message.media = media;
            message.flags |= 512;
        } else {
            message.media = new TLRPC.TL_messageMediaEmpty();
        }
        MessageObject messageObject = new MessageObject(account, message, true, false);
        messageObject.resetLayout();
        messageObject.eventId = 1;
        return messageObject;
    }
}
