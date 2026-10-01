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
import org.telegram.messenger.MessagesController;
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
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
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

    private static final int TYPE_CHECK = 4;
    private static final int TYPE_VALUE = 5;

    private static final int ROW_LOOK_HEADER = 0;
    private static final int ROW_LOOK_UI = 1;
    private static final int ROW_LOOK_CHAT = 2;
    private static final int ROW_LOOK_CLASSIC = 3;
    private static final int ROW_LOOK_INFO = 4;
    private static final int ROW_DATA_HEADER = 5;
    private static final int ROW_FULL_NUMBERS = 6;
    private static final int ROW_ID_FORMAT = 7;
    private static final int ROW_DATA_INFO = 8;
    private static final int ROW_FEAT_HEADER = 9;
    private static final int ROW_FEAT_DETAILS = 10;
    private static final int ROW_FEAT_INLINE_RAW = 11;
    private static final int ROW_FEAT_TRAY = 12;
    private static final int ROW_FEAT_BOT_BUTTONS = 13;
    private static final int ROW_FEAT_PREVIEW_RAW = 14;
    private static final int ROW_FEAT_OBJECT_RAW = 15;
    private static final int ROW_FEAT_WEBAPP = 16;
    private static final int ROW_FEAT_INFO = 17;
    private static final int ROW_CHAT_HEADER = 18;
    private static final int ROW_HIDE_KEYBOARD = 19;
    private static final int ROW_HIDE_CAMERA = 20;
    private static final int ROW_CHAT_INFO = 21;
    private static final int ROW_PRESS_HEADER = 22;
    private static final int ROW_PRESS_SLIDER = 23;
    private static final int ROW_PRESS_INFO = 24;
    private static final int ROW_STICKER_HEADER = 25;
    private static final int ROW_STICKER_SLIDER = 26;
    private static final int ROW_STICKER_PREVIEW = 27;
    private static final int ROW_STICKER_INFO = 28;
    private static final int ROW_RECENT_HEADER = 29;
    private static final int ROW_RECENT_SLIDER = 30;
    private static final int ROW_RECENT_INFO = 31;
    private static final int ROW_TOOLS_HEADER = 32;
    private static final int ROW_REQUEST_LOG = 33;
    private static final int ROW_REQUEST_LOG_OPEN = 34;
    private static final int ROW_CRASH_LOG = 35;
    private static final int ROW_SERVER_CONFIG = 36;
    private static final int ROW_TOOLS_INFO = 37;
    private static final int ROW_COUNT = 38;

    private RecyclerListView listView;
    private ListAdapter adapter;
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
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position == ROW_LOOK_UI) {
                presentFragment(new RawUiSettingsActivity());
            } else if (position == ROW_LOOK_CHAT) {
                presentFragment(new RawChatUiSettingsActivity());
            } else if (position == ROW_LOOK_CLASSIC) {
                boolean value = !RawClassicUi.isEnabled();
                RawClassicUi.setEnabled(value);
                ((TextCheckCell) view).setChecked(value);
            } else if (position == ROW_FULL_NUMBERS) {
                boolean value = !RawgramConfig.isFullNumbers();
                RawgramConfig.setFullNumbers(value);
                ((TextCheckCell) view).setChecked(value);
            } else if (position == ROW_HIDE_KEYBOARD) {
                boolean value = !RawgramConfig.isHideKeyboardOnScroll();
                RawgramConfig.setHideKeyboardOnScroll(value);
                ((TextCheckCell) view).setChecked(value);
            } else if (position == ROW_HIDE_CAMERA) {
                boolean value = !RawgramConfig.isHideAttachCamera();
                RawgramConfig.setHideAttachCamera(value);
                ((TextCheckCell) view).setChecked(value);
            } else if (position >= ROW_FEAT_DETAILS && position <= ROW_FEAT_WEBAPP) {
                boolean value = !isFeatureOn(position);
                setFeature(position, value);
                ((TextCheckCell) view).setChecked(value);
            } else if (position == ROW_ID_FORMAT) {
                CharSequence[] names = {"Не показывать", "MTProto (как в API)", "Bot API (-100… для каналов)"};
                new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity())
                        .setTitle("ID в профилях")
                        .setItems(names, (d, which) -> {
                            RawgramConfig.setIdFormat(which);
                            adapter.notifyItemChanged(ROW_ID_FORMAT);
                        })
                        .show();
            } else if (position == ROW_REQUEST_LOG) {
                boolean value = !RawRequestLog.enabled;
                RawRequestLog.setEnabled(value);
                ((TextCheckCell) view).setChecked(value);
            } else if (position == ROW_REQUEST_LOG_OPEN) {
                presentFragment(new RawRequestLogActivity());
            } else if (position == ROW_CRASH_LOG) {
                RawCrashLog.openViewer(this);
            } else if (position == ROW_SERVER_CONFIG) {
                RawServerConfig.show(getParentActivity(), currentAccount, getResourceProvider());
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private static String featureTitle(int position) {
        switch (position) {
            case ROW_FEAT_DETAILS: return "Подробности в меню сообщения";
            case ROW_FEAT_INLINE_RAW: return "Raw инлайн-результатов по долгому нажатию";
            case ROW_FEAT_TRAY: return "Лоток инлайн-выдачи";
            case ROW_FEAT_BOT_BUTTONS: return "Отладка кнопок ботов";
            case ROW_FEAT_PREVIEW_RAW: return "Raw в предпросмотре стикеров и эмодзи";
            case ROW_FEAT_OBJECT_RAW: return "Raw в профилях, наборах и диалогах";
            default: return "rawGram-данные веб-приложений";
        }
    }

    private static boolean isFeatureOn(int position) {
        switch (position) {
            case ROW_FEAT_DETAILS: return RawgramConfig.isMessageDetails();
            case ROW_FEAT_INLINE_RAW: return RawgramConfig.isInlineRaw();
            case ROW_FEAT_TRAY: return RawgramConfig.isInlineTray();
            case ROW_FEAT_BOT_BUTTONS: return RawgramConfig.isBotButtonDebug();
            case ROW_FEAT_PREVIEW_RAW: return RawgramConfig.isPreviewRaw();
            case ROW_FEAT_OBJECT_RAW: return RawgramConfig.isObjectRaw();
            default: return RawgramConfig.isWebAppData();
        }
    }

    private static void setFeature(int position, boolean value) {
        switch (position) {
            case ROW_FEAT_DETAILS: RawgramConfig.setMessageDetails(value); break;
            case ROW_FEAT_INLINE_RAW: RawgramConfig.setInlineRaw(value); break;
            case ROW_FEAT_TRAY: RawgramConfig.setInlineTray(value); break;
            case ROW_FEAT_BOT_BUTTONS: RawgramConfig.setBotButtonDebug(value); break;
            case ROW_FEAT_PREVIEW_RAW: RawgramConfig.setPreviewRaw(value); break;
            case ROW_FEAT_OBJECT_RAW: RawgramConfig.setObjectRaw(value); break;
            default: RawgramConfig.setWebAppData(value); break;
        }
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
            int type = holder.getItemViewType();
            return type == TYPE_CHECK || type == TYPE_VALUE;
        }

        @Override
        public int getItemViewType(int position) {
            switch (position) {
                case ROW_LOOK_CLASSIC:
                case ROW_FULL_NUMBERS:
                case ROW_HIDE_KEYBOARD:
                case ROW_HIDE_CAMERA:
                case ROW_REQUEST_LOG:
                case ROW_FEAT_DETAILS:
                case ROW_FEAT_INLINE_RAW:
                case ROW_FEAT_TRAY:
                case ROW_FEAT_BOT_BUTTONS:
                case ROW_FEAT_PREVIEW_RAW:
                case ROW_FEAT_OBJECT_RAW:
                case ROW_FEAT_WEBAPP:
                    return TYPE_CHECK;
                case ROW_LOOK_UI:
                case ROW_LOOK_CHAT:
                case ROW_ID_FORMAT:
                case ROW_REQUEST_LOG_OPEN:
                case ROW_CRASH_LOG:
                case ROW_SERVER_CONFIG:
                    return TYPE_VALUE;
                case ROW_LOOK_HEADER:
                case ROW_DATA_HEADER:
                case ROW_FEAT_HEADER:
                case ROW_CHAT_HEADER:
                case ROW_PRESS_HEADER:
                case ROW_STICKER_HEADER:
                case ROW_RECENT_HEADER:
                case ROW_TOOLS_HEADER:
                    return TYPE_HEADER;
                case ROW_PRESS_SLIDER:
                case ROW_STICKER_SLIDER:
                case ROW_RECENT_SLIDER:
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
            } else if (viewType == TYPE_CHECK) {
                view = new TextCheckCell(context);
            } else if (viewType == TYPE_VALUE) {
                view = new TextSettingsCell(context);
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
                    ((HeaderCell) holder.itemView).setText(position == ROW_LOOK_HEADER ? "Внешний вид"
                            : position == ROW_DATA_HEADER ? "Данные"
                            : position == ROW_FEAT_HEADER ? "Функции rawGram"
                            : position == ROW_CHAT_HEADER ? "Чат"
                            : position == ROW_PRESS_HEADER ? "Задержка зажатия"
                            : position == ROW_RECENT_HEADER ? "Недавние стикеры"
                            : position == ROW_TOOLS_HEADER ? "Инструменты" : "Размер стикеров");
                    break;
                case TYPE_CHECK:
                    if (position == ROW_LOOK_CLASSIC) {
                        ((TextCheckCell) holder.itemView).setTextAndCheck("Классический вид чата", RawClassicUi.isEnabled(), false);
                    } else if (position >= ROW_FEAT_DETAILS && position <= ROW_FEAT_WEBAPP) {
                        ((TextCheckCell) holder.itemView).setTextAndCheck(featureTitle(position), isFeatureOn(position), position != ROW_FEAT_WEBAPP);
                    } else if (position == ROW_REQUEST_LOG) {
                        ((TextCheckCell) holder.itemView).setTextAndCheck("Журнал запросов MTProto", RawRequestLog.enabled, true);
                    } else if (position == ROW_HIDE_KEYBOARD) {
                        ((TextCheckCell) holder.itemView).setTextAndCheck("Сворачивать клавиатуру при прокрутке чата", RawgramConfig.isHideKeyboardOnScroll(), true);
                    } else if (position == ROW_HIDE_CAMERA) {
                        ((TextCheckCell) holder.itemView).setTextAndCheck("Камера во вложениях — кнопкой", RawgramConfig.isHideAttachCamera(), false);
                    } else {
                        ((TextCheckCell) holder.itemView).setTextAndCheck("Не сокращать числа (100 000 вместо 100K)", RawgramConfig.isFullNumbers(), true);
                    }
                    break;
                case TYPE_VALUE: {
                    if (position == ROW_LOOK_UI) {
                        ((TextSettingsCell) holder.itemView).setText("Интерфейс", true);
                        break;
                    }
                    if (position == ROW_LOOK_CHAT) {
                        ((TextSettingsCell) holder.itemView).setText("Чаты: вид и поведение", true);
                        break;
                    }
                    if (position == ROW_SERVER_CONFIG) {
                        ((TextSettingsCell) holder.itemView).setText("Конфиг сервера (raw)", false);
                        break;
                    }
                    if (position == ROW_REQUEST_LOG_OPEN) {
                        ((TextSettingsCell) holder.itemView).setText("Открыть журнал", true);
                        break;
                    }
                    if (position == ROW_CRASH_LOG) {
                        ((TextSettingsCell) holder.itemView).setText("Журнал крашей", true);
                        break;
                    }
                    int format = RawgramConfig.getIdFormat();
                    String value = format == RawgramConfig.ID_OFF ? "Выкл" : format == RawgramConfig.ID_MTPROTO ? "MTProto" : "Bot API";
                    ((TextSettingsCell) holder.itemView).setTextAndValue("ID в профилях", value, false);
                    break;
                }
                case TYPE_SLIDER: {
                    SliderCell cell = (SliderCell) holder.itemView;
                    if (position == ROW_PRESS_SLIDER) {
                        cell.bind(RawgramConfig.LONG_PRESS_MIN, RawgramConfig.LONG_PRESS_MAX, RawgramConfig.LONG_PRESS_STEP,
                                RawgramConfig.getLongPressDelay(), " мс", RawgramConfig::setLongPressDelay);
                    } else if (position == ROW_RECENT_SLIDER) {
                        cell.bind(RawgramConfig.RECENT_STICKERS_MIN, RawgramConfig.RECENT_STICKERS_MAX, RawgramConfig.RECENT_STICKERS_STEP,
                                RawgramConfig.getRecentStickersShown(), "", RawgramConfig::setRecentStickersShown);
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
                    if (position == ROW_LOOK_INFO) {
                        cell.setText("Классический вид: сплошная шапка, закреп под ней и поле ввода во всю ширину вместо плавающих «пилюль». "
                                + "Применяется к чатам, открытым после переключения.");
                    } else if (position == ROW_DATA_INFO) {
                        cell.setText("Числа: просмотры, реакции, подписчики, рейтинг — полностью. Изменения видны при следующем открытии экрана. "
                                + "ID в профилях: Bot API — пользователи как есть, группы -id, каналы и супергруппы -100id; нажатие копирует.");
                    } else if (position == ROW_FEAT_INFO) {
                        cell.setText("Выключенная функция не показывается, и Telegram там ведёт себя как обычно: например, без отладки кнопок "
                                + "долгое нажатие на кнопку бота работает как в Telegram. Отложенные в лоток результаты сохраняются, пока он выключен.");
                    } else if (position == ROW_CHAT_INFO) {
                        cell.setText("Клавиатура прячется, как только начинаешь листать сообщения; поле ввода и набранный текст остаются. "
                                + "Камера во вложениях — кнопкой: вместо большой плитки камеры в галерее круглая кнопка справа снизу.");
                    } else if (position == ROW_PRESS_INFO) {
                        cell.setText("Сколько держать палец, чтобы открыть raw-просмотр инлайн-результата или превью стикера / GIF. "
                                + "Системная задержка на этом устройстве: " + ViewConfiguration.getLongPressTimeout() + " мс.");
                    } else if (position == ROW_TOOLS_INFO) {
                        cell.setText("Журнал запросов: последние " + RawRequestLog.CAPACITY + " RPC-вызовов всех аккаунтов — метод, время, "
                                + "ответ или ошибка (FLOOD_WAIT_…); полные объекты запроса и ответа — для последних " + RawRequestLog.KEEP_OBJECTS
                                + ". Выключенный журнал ничего не стоит.\n\n"
                                + "Журнал крашей: сохранённые падения приложения со стеком.\n\n"
                                + "Конфиг сервера: свежие help.getConfig и help.getAppConfig этого аккаунта — все лимиты "
                                + "(обычные и премиум), DC и флаги клиента.");
                    } else if (position == ROW_RECENT_INFO) {
                        cell.setText("Сколько недавних стикеров показывать в панели. Telegram показывает 20, хотя сервер хранит до "
                                + MessagesController.getInstance(currentAccount).maxRecentStickersCount
                                + " (stickers_recent_limit) — остальные просто не выводились.");
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
                // lay the wallpaper out as in a full-screen chat and show its middle band
                int fullHeight = Math.max(getHeight(), AndroidUtilities.displaySize.y);
                canvas.translate(0, -(fullHeight - getHeight()) / 2f);
                StoryEntry.drawBackgroundDrawable(canvas, wallpaper, getWidth(), fullHeight);
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
