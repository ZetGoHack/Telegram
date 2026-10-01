package org.telegram.rawgram;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Stories.recorder.StoryEntry;

import java.util.ArrayList;

/**
 * "Чаты: вид и поведение": chat look and behaviour switches (RawChatUiConfig). Every option is off by default,
 * which is stock Telegram. Option set ported from Nagram / NekoX / exteraGram settings.
 */
public class RawChatUiSettingsActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_VALUE = 2;
    private static final int TYPE_INFO = 3;
    private static final int TYPE_PREVIEW = 4;

    /** One list row: a header, a switch bound to a flag, a value (picker) or an info line. */
    private static class Row {
        final int type;
        final String text;
        final RawChatUiConfig.Flag flag;
        final int id;

        Row(int type, String text, RawChatUiConfig.Flag flag, int id) {
            this.type = type;
            this.text = text;
            this.flag = flag;
            this.id = id;
        }
    }

    private static final int VALUE_EDITED_MODE = 1;
    private static final int VALUE_EDITED_TEXT = 2;
    private static final int VALUE_SNOW = 3;
    private static final int VALUE_TAP_IN = 4;
    private static final int VALUE_TAP_OUT = 5;

    /** Switches not stored in RawChatUiConfig (Row.flag == null, Row.id says which). */
    private static final int CHECK_CLASSIC = 100;

    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;
    private ListAdapter adapter;
    private ChatPreviewCell previewCell;

    private void header(String text) {
        rows.add(new Row(TYPE_HEADER, text, null, 0));
    }

    private void check(String text, RawChatUiConfig.Flag flag) {
        rows.add(new Row(TYPE_CHECK, text, flag, 0));
    }

    private void check(String text, int id) {
        rows.add(new Row(TYPE_CHECK, text, null, id));
    }

    private static boolean customCheck(int id) {
        return id == CHECK_CLASSIC && RawClassicUi.isEnabled();
    }

    private static void toggleCustomCheck(int id) {
        if (id == CHECK_CLASSIC) {
            RawClassicUi.setEnabled(!RawClassicUi.isEnabled());
        }
    }

    private void updatePreview() {
        if (previewCell != null) {
            previewCell.bind();
        }
    }

    private void value(String text, int id) {
        rows.add(new Row(TYPE_VALUE, text, null, id));
    }

    private void info(String text) {
        rows.add(new Row(TYPE_INFO, text, null, 0));
    }

    private void buildRows() {
        rows.clear();
        rows.add(new Row(TYPE_PREVIEW, null, null, 0));
        info("Пример обновляется сразу: вид шапки и поля ввода, заголовок по центру, время у стикера, ID сообщения "
                + "и метка «изменено» (у первого сообщения). Стикер — из недавних.");

        header("Вид чата");
        check("Классический вид чата", CHECK_CLASSIC);
        info("Сплошная шапка, закреп под ней и поле ввода во всю ширину вместо плавающих «пилюль». "
                + "Применяется к чатам, открытым после переключения.");

        header("Заголовок и сообщения");
        check("Заголовок чата по центру", RawChatUiConfig.centerTitle);
        check("Скрыть время у стикеров", RawChatUiConfig.hideStickerTime);
        check("ID сообщения в пузыре", RawChatUiConfig.showMessageId);
        value("Метка «изменено»", VALUE_EDITED_MODE);
        if (RawChatUiConfig.editedMode.get() == RawChatUiConfig.EDITED_CUSTOM) {
            value("Текст метки", VALUE_EDITED_TEXT);
        }
        check("Скрыть «Поделиться» у постов каналов", RawChatUiConfig.hideChannelShare);
        value("Снег в чате", VALUE_SNOW);
        info("Время стикера видно, пока сообщение выделено. ID сообщения добавляется к времени: «12:30 | 4821». "
                + "Изменения видны при следующем открытии чата.");

        header("Поведение");
        check("Без свайпа к следующему каналу", RawChatUiConfig.noSwipeNextChannel);
        check("Без свайпа к следующей теме", RawChatUiConfig.noSwipeNextTopic);
        value("Двойное нажатие: входящие", VALUE_TAP_IN);
        value("Двойное нажатие: свои", VALUE_TAP_OUT);
        info("«Реакция» — как в Telegram (быстрая реакция). «Удалить» открывает обычное окно удаления Telegram "
                + "(с выбором «удалить у всех», где это можно). Действие, которое к сообщению неприменимо "
                + "(например, копирование в чате с запретом копирования или удаление чужого сообщения без прав), просто не срабатывает.");

        header("Ярлыки администратора в меню чата");
        check("Разрешения / чёрный список", RawChatUiConfig.shortcutPermissions);
        check("Администраторы", RawChatUiConfig.shortcutAdmins);
        check("Участники / подписчики", RawChatUiConfig.shortcutMembers);
        check("Недавние действия", RawChatUiConfig.shortcutRecentActions);
        info("Пункты появляются в меню «⋮» групп и каналов, где у тебя есть права администратора.");

        header("Вид меню сообщения");
        check("Компактное меню сообщения", RawChatUiConfig.menuCompact);
        info("Ответить, Удалить, Копировать и Изменить — строкой иконок внизу меню (удержание показывает подпись), "
                + "остальные пункты — списком над ней. Если из них доступно меньше двух, меню остаётся обычным.");

        header("Скрыть из меню сообщения");
        check("Перевести", RawChatUiConfig.menuHideTranslate);
        check("Пожаловаться", RawChatUiConfig.menuHideReport);
        check("Закрепить / открепить", RawChatUiConfig.menuHidePin);
        check("Сохранить (галерея, загрузки, музыка, GIF)", RawChatUiConfig.menuHideSave);
        check("Поделиться файлом", RawChatUiConfig.menuHideShare);
        check("Копировать ссылку", RawChatUiConfig.menuHideCopyLink);
        check("Статистика", RawChatUiConfig.menuHideStatistics);
        check("Факт-чек", RawChatUiConfig.menuHideFactCheck);

        header("Добавить в меню сообщения");
        check("Повторить (отправить копию сюда же)", RawChatUiConfig.menuRepeat);
        check("В Избранное", RawChatUiConfig.menuSaveToSaved);
        info("«Подробности» rawGram настраиваются в основных настройках.\n\n"
                + "Идеи и часть кода — из Nagram, NekoX и exteraGram (GPLv3).");
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Чаты: вид и поведение");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        buildRows();

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
            if (position < 0 || position >= rows.size()) {
                return;
            }
            Row row = rows.get(position);
            if (row.type == TYPE_CHECK && row.flag != null) {
                row.flag.toggle();
                ((TextCheckCell) view).setChecked(row.flag.get());
                updatePreview();
            } else if (row.type == TYPE_CHECK) {
                toggleCustomCheck(row.id);
                ((TextCheckCell) view).setChecked(customCheck(row.id));
                updatePreview();
            } else if (row.type == TYPE_VALUE) {
                onValueClick(row.id, position);
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private void pick(String title, CharSequence[] names, Runnable done, PickCallback callback) {
        pick(title, names, null, done, callback);
    }

    private void pick(String title, CharSequence[] names, int[] icons, Runnable done, PickCallback callback) {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setItems(names, icons, (d, which) -> {
                    callback.picked(which);
                    done.run();
                })
                .show();
    }

    private static int doubleTapIcon(int action) {
        switch (action) {
            case RawChatUiConfig.TAP_REACTION: return R.drawable.msg_reactions;
            case RawChatUiConfig.TAP_REPLY: return R.drawable.menu_reply;
            case RawChatUiConfig.TAP_COPY: return R.drawable.msg_copy;
            case RawChatUiConfig.TAP_FORWARD: return R.drawable.msg_forward;
            case RawChatUiConfig.TAP_EDIT: return R.drawable.msg_edit;
            case RawChatUiConfig.TAP_SAVE: return R.drawable.msg_saved;
            case RawChatUiConfig.TAP_DELETE: return R.drawable.msg_delete;
            case RawChatUiConfig.TAP_NONE: return R.drawable.msg_cancel;
            default: return 0;
        }
    }

    private interface PickCallback {
        void picked(int which);
    }

    private void onValueClick(int id, int position) {
        Runnable refresh = () -> adapter.notifyItemChanged(position);
        switch (id) {
            case VALUE_EDITED_MODE:
                pick("Метка «изменено»", new CharSequence[]{"Стандартная", "Карандаш ✎", "Свой текст"}, () -> {
                    buildRows();
                    adapter.notifyDataSetChanged();
                    updatePreview();
                }, which -> {
                    RawChatUiConfig.editedMode.set(which);
                    if (which == RawChatUiConfig.EDITED_CUSTOM && RawChatUiConfig.getEditedText().isEmpty()) {
                        AndroidUtilities.runOnUIThread(this::editCustomText);
                    }
                });
                break;
            case VALUE_EDITED_TEXT:
                editCustomText();
                break;
            case VALUE_SNOW:
                pick("Снег в чате", new CharSequence[]{"По дате (как в Telegram)", "Всегда", "Никогда"}, refresh,
                        RawChatUiConfig.chatSnow::set);
                break;
            case VALUE_TAP_IN:
            case VALUE_TAP_OUT: {
                boolean out = id == VALUE_TAP_OUT;
                // editing applies to own messages only
                int[] actions = out
                        ? new int[]{RawChatUiConfig.TAP_REACTION, RawChatUiConfig.TAP_REPLY, RawChatUiConfig.TAP_COPY, RawChatUiConfig.TAP_FORWARD, RawChatUiConfig.TAP_EDIT, RawChatUiConfig.TAP_SAVE, RawChatUiConfig.TAP_DELETE, RawChatUiConfig.TAP_NONE}
                        : new int[]{RawChatUiConfig.TAP_REACTION, RawChatUiConfig.TAP_REPLY, RawChatUiConfig.TAP_COPY, RawChatUiConfig.TAP_FORWARD, RawChatUiConfig.TAP_SAVE, RawChatUiConfig.TAP_DELETE, RawChatUiConfig.TAP_NONE};
                int current = (out ? RawChatUiConfig.doubleTapOut : RawChatUiConfig.doubleTapIn).get();
                CharSequence[] names = new CharSequence[actions.length];
                int[] icons = new int[actions.length];
                for (int i = 0; i < actions.length; i++) {
                    String name = RawChatUiConfig.doubleTapName(actions[i]);
                    if (actions[i] == current) {
                        // mark the current choice: accent colour, bold, trailing check
                        android.text.SpannableString s = new android.text.SpannableString(name + "  ✓");
                        s.setSpan(new android.text.style.ForegroundColorSpan(Theme.getColor(Theme.key_dialogTextBlue2)), 0, s.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, s.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        names[i] = s;
                    } else {
                        names[i] = name;
                    }
                    icons[i] = doubleTapIcon(actions[i]);
                }
                pick(out ? "Двойное нажатие: свои" : "Двойное нажатие: входящие", names, icons, refresh,
                        which -> (out ? RawChatUiConfig.doubleTapOut : RawChatUiConfig.doubleTapIn).set(actions[which]));
                break;
            }
        }
    }

    private void editCustomText() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setBackground(null);
        editText.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setHint(LocaleController.getString(R.string.EditedMessage));
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        editText.setText(RawChatUiConfig.getEditedText());
        editText.setSelection(editText.length());
        editText.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setCursorWidth(1.5f);
        editText.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        FrameLayout container = new FrameLayout(context);
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 4, 24, 0));
        new AlertDialog.Builder(context)
                .setTitle("Текст метки «изменено»")
                .setView(container)
                .setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
                    RawChatUiConfig.setEditedText(editText.getText() == null ? "" : editText.getText().toString());
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    updatePreview();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
        AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }, 200);
    }

    private String valueText(int id) {
        switch (id) {
            case VALUE_EDITED_MODE: {
                int mode = RawChatUiConfig.editedMode.get();
                return mode == RawChatUiConfig.EDITED_PENCIL ? "Карандаш ✎" : mode == RawChatUiConfig.EDITED_CUSTOM ? "Свой текст" : "Стандартная";
            }
            case VALUE_EDITED_TEXT: {
                String text = RawChatUiConfig.getEditedText();
                return text.isEmpty() ? LocaleController.getString(R.string.EditedMessage) : text;
            }
            case VALUE_SNOW: {
                int snow = RawChatUiConfig.chatSnow.get();
                return snow == RawChatUiConfig.SNOW_ALWAYS ? "Всегда" : snow == RawChatUiConfig.SNOW_NEVER ? "Никогда" : "По дате";
            }
            case VALUE_TAP_IN:
                return RawChatUiConfig.doubleTapName(RawChatUiConfig.doubleTapIn.get());
            case VALUE_TAP_OUT:
                return RawChatUiConfig.doubleTapName(RawChatUiConfig.doubleTapOut.get());
            default:
                return "";
        }
    }

    private boolean needDivider(int position) {
        return position + 1 < rows.size() && rows.get(position + 1).type != TYPE_INFO && rows.get(position + 1).type != TYPE_HEADER
                && rows.get(position + 1).type != TYPE_PREVIEW;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == TYPE_CHECK || type == TYPE_VALUE;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_HEADER) {
                view = new HeaderCell(context);
            } else if (viewType == TYPE_CHECK) {
                view = new TextCheckCell(context);
            } else if (viewType == TYPE_VALUE) {
                view = new TextSettingsCell(context);
            } else if (viewType == TYPE_PREVIEW) {
                view = previewCell = new ChatPreviewCell(context);
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            switch (row.type) {
                case TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(row.text);
                    break;
                case TYPE_PREVIEW:
                    ((ChatPreviewCell) holder.itemView).bind();
                    break;
                case TYPE_CHECK:
                    ((TextCheckCell) holder.itemView).setTextAndCheck(row.text, row.flag != null ? row.flag.get() : customCheck(row.id), needDivider(position));
                    break;
                case TYPE_VALUE:
                    ((TextSettingsCell) holder.itemView).setTextAndValue(row.text, valueText(row.id), needDivider(position));
                    break;
                default:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.text);
                    break;
            }
        }
    }

    // ---- live preview ----

    /**
     * A small chat over the current wallpaper: header (classic solid bar or floating pills, title centered or not),
     * an incoming edited message, an outgoing message and an outgoing sticker (from recent stickers) laid out by real
     * {@link ChatMessageCell}s, and the input panel. Built from fake MessageObjects like the sticker-size preview in
     * RawgramSettingsActivity; nothing is loaded from the network.
     */
    private class ChatPreviewCell extends FrameLayout {
        private static final int HEADER_H = 56;
        private static final int INPUT_H = 54;

        private final LinearLayout messagesLayout;
        private final ArrayList<ChatMessageCell> messageCells = new ArrayList<>();
        private final ArrayList<MessageObject> messages = new ArrayList<>();

        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint subtitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Drawable backIcon, smileIcon, attachIcon, micIcon;

        ChatPreviewCell(Context context) {
            super(context);
            setWillNotDraw(false);
            titlePaint.setTypeface(AndroidUtilities.bold());
            titlePaint.setTextSize(AndroidUtilities.dp(17));
            subtitlePaint.setTextSize(AndroidUtilities.dp(13));
            hintPaint.setTextSize(AndroidUtilities.dp(17));
            shadowPaint.setColor(0x14000000);
            avatarDrawable.setInfo(5, "Анна", null);
            backIcon = icon(context, R.drawable.ic_ab_back);
            smileIcon = icon(context, R.drawable.input_smile);
            attachIcon = icon(context, R.drawable.input_attach);
            micIcon = icon(context, R.drawable.input_mic);

            messagesLayout = new LinearLayout(context);
            messagesLayout.setOrientation(LinearLayout.VERTICAL);
            messagesLayout.setPadding(0, AndroidUtilities.dp(HEADER_H + 10), 0, AndroidUtilities.dp(INPUT_H + 8));
            addView(messagesLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            int account = currentAccount;
            int now = (int) (System.currentTimeMillis() / 1000);
            addMessage(context, buildMessage(account, 4820, "Привет! Я поправила сообщение — видишь метку?", null, false, true, now - 300));
            addMessage(context, buildMessage(account, 4821, "Вижу, и номер сообщения тоже 👍", null, true, false, now - 240));
            TLRPC.Document sticker = pickSticker(account);
            if (sticker != null) {
                addMessage(context, buildMessage(account, 4822, "", sticker, true, false, now - 200));
            }
        }

        private Drawable icon(Context context, int res) {
            Drawable d = ContextCompat.getDrawable(context, res);
            return d == null ? null : d.mutate();
        }

        private void addMessage(Context context, MessageObject messageObject) {
            ChatMessageCell cell = new ChatMessageCell(context, currentAccount);
            cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {});
            cell.setFullyDraw(true);
            cell.setMessageObject(messageObject, null, false, false, false);
            messagesLayout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            messageCells.add(cell);
            messages.add(messageObject);
        }

        /** Re-lays out the messages (time string, edited mark, ID, sticker time) and redraws header / input. */
        void bind() {
            for (int i = 0; i < messageCells.size(); i++) {
                MessageObject messageObject = messages.get(i);
                messageObject.forceUpdate = true;
                messageCells.get(i).setMessageObject(messageObject, null, false, false, false);
                messageCells.get(i).requestLayout();
                messageCells.get(i).invalidate();
            }
            messagesLayout.requestLayout();
            requestLayout();
            invalidate();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            return true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Drawable wallpaper = Theme.getCachedWallpaperNonBlocking();
            if (wallpaper != null) {
                canvas.save();
                canvas.clipRect(0, 0, getWidth(), getHeight());
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

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            boolean classic = RawClassicUi.isEnabled();
            drawHeader(canvas, classic);
            drawInput(canvas, classic);
        }

        private void drawIcon(Canvas canvas, Drawable d, float cx, float cy, int color) {
            if (d == null) {
                return;
            }
            d.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
            d.setBounds((int) (cx - w / 2f), (int) (cy - h / 2f), (int) (cx + w / 2f), (int) (cy + h / 2f));
            d.draw(canvas);
        }

        private void drawHeader(Canvas canvas, boolean classic) {
            int w = getWidth();
            int titleColor, subtitleColor, iconColor;
            float top, bottom, contentLeft, contentRight;
            if (classic) {
                // solid bar across the whole width with a shadow under it
                top = 0;
                bottom = AndroidUtilities.dp(HEADER_H);
                paint.setColor(Theme.getColor(Theme.key_actionBarDefault));
                canvas.drawRect(0, top, w, bottom, paint);
                canvas.drawRect(0, bottom, w, bottom + AndroidUtilities.dp(1), shadowPaint);
                titleColor = Theme.getColor(Theme.key_actionBarDefaultTitle);
                subtitleColor = Theme.getColor(Theme.key_actionBarDefaultSubtitle);
                iconColor = Theme.getColor(Theme.key_actionBarDefaultIcon);
                drawIcon(canvas, backIcon, AndroidUtilities.dp(28), (top + bottom) / 2f, iconColor);
                contentLeft = AndroidUtilities.dp(56);
                contentRight = w - AndroidUtilities.dp(16);
            } else {
                // floating pills: a round back button and the title pill
                top = AndroidUtilities.dp(6);
                bottom = top + AndroidUtilities.dp(44);
                int pill = pillColor();
                paint.setColor(pill);
                titleColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
                subtitleColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
                iconColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon);
                float cy = (top + bottom) / 2f;
                canvas.drawCircle(AndroidUtilities.dp(8 + 22), cy, AndroidUtilities.dp(22), paint);
                drawIcon(canvas, backIcon, AndroidUtilities.dp(8 + 22), cy, iconColor);
                rect.set(AndroidUtilities.dp(8 + 44 + 6), top, w - AndroidUtilities.dp(8), bottom);
                canvas.drawRoundRect(rect, AndroidUtilities.dp(22), AndroidUtilities.dp(22), paint);
                contentLeft = rect.left + AndroidUtilities.dp(4);
                contentRight = rect.right - AndroidUtilities.dp(16);
            }

            float cy = (top + bottom) / 2f;
            int avatarSize = AndroidUtilities.dp(classic ? 42 : 36);
            float avatarLeft = contentLeft;
            avatarDrawable.setRoundRadius(RawUi.avatarR(avatarSize / 2));
            avatarDrawable.setBounds((int) avatarLeft, (int) (cy - avatarSize / 2f), (int) avatarLeft + avatarSize, (int) (cy + avatarSize / 2f));
            avatarDrawable.draw(canvas);

            float textLeft = avatarLeft + avatarSize + AndroidUtilities.dp(10);
            titlePaint.setColor(titleColor);
            subtitlePaint.setColor(subtitleColor);
            String title = "Анна";
            String subtitle = "в сети";
            float titleY = cy - AndroidUtilities.dp(2);
            float subtitleY = cy + AndroidUtilities.dp(16);
            if (RawChatUiConfig.centerTitle.get()) {
                // centered on the whole bar, like the real option (RawChatUiActions.centerTitle)
                float center = w / 2f;
                canvas.drawText(title, Math.max(textLeft, center - titlePaint.measureText(title) / 2f), titleY, titlePaint);
                canvas.drawText(subtitle, Math.max(textLeft, center - subtitlePaint.measureText(subtitle) / 2f), subtitleY, subtitlePaint);
            } else {
                canvas.drawText(title, textLeft, titleY, titlePaint);
                canvas.drawText(subtitle, textLeft, subtitleY, subtitlePaint);
            }
        }

        private void drawInput(Canvas canvas, boolean classic) {
            int w = getWidth(), h = getHeight();
            int hintColor = Theme.getColor(Theme.key_chat_messagePanelHint);
            int iconColor = Theme.getColor(Theme.key_chat_messagePanelIcons);
            hintPaint.setColor(hintColor);
            float top, bottom;
            if (classic) {
                // full-width panel glued to the bottom
                top = h - AndroidUtilities.dp(48);
                bottom = h;
                paint.setColor(Theme.getColor(Theme.key_chat_messagePanelBackground));
                canvas.drawRect(0, top - AndroidUtilities.dp(1), w, top, shadowPaint);
                canvas.drawRect(0, top, w, bottom, paint);
                float cy = (top + bottom) / 2f;
                drawIcon(canvas, smileIcon, AndroidUtilities.dp(24), cy, iconColor);
                drawIcon(canvas, attachIcon, w - AndroidUtilities.dp(72), cy, iconColor);
                drawIcon(canvas, micIcon, w - AndroidUtilities.dp(24), cy, iconColor);
                canvas.drawText("Сообщение", AndroidUtilities.dp(52), cy + AndroidUtilities.dp(6), hintPaint);
            } else {
                // floating pill with a separate round mic button
                bottom = h - AndroidUtilities.dp(6);
                top = bottom - AndroidUtilities.dp(44);
                paint.setColor(pillColor());
                float cy = (top + bottom) / 2f;
                rect.set(AndroidUtilities.dp(8), top, w - AndroidUtilities.dp(8 + 44 + 6), bottom);
                canvas.drawRoundRect(rect, AndroidUtilities.dp(22), AndroidUtilities.dp(22), paint);
                canvas.drawCircle(w - AndroidUtilities.dp(8 + 22), cy, AndroidUtilities.dp(22), paint);
                drawIcon(canvas, smileIcon, AndroidUtilities.dp(8 + 22), cy, iconColor);
                drawIcon(canvas, attachIcon, rect.right - AndroidUtilities.dp(24), cy, iconColor);
                drawIcon(canvas, micIcon, w - AndroidUtilities.dp(8 + 22), cy, iconColor);
                canvas.drawText("Сообщение", AndroidUtilities.dp(8 + 48), cy + AndroidUtilities.dp(6), hintPaint);
            }
        }

        private int pillColor() {
            int color = Theme.getColor(Theme.key_chat_messagePanelBackground);
            return (color & 0x00ffffff) | 0xE6000000;
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

    private static MessageObject buildMessage(int account, int id, String text, TLRPC.Document sticker, boolean out, boolean edited, int date) {
        long selfId = UserConfig.getInstance(account).getClientUserId();
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = id;
        message.date = date;
        message.dialog_id = 1;
        message.out = out;
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = out ? selfId : 0;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = out ? 0 : selfId;
        message.message = text;
        message.flags |= 256;
        if (edited) {
            message.flags |= TLRPC.MESSAGE_FLAG_EDITED;
            message.edit_date = date + 30;
        }
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
