package org.telegram.rawgram;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Long press on an inline keyboard button: what the button carries (type, text, callback data as text/hex/base64,
 * url, switch_inline query, …), each value copyable, the last callback answer for it, and actions:
 * press it normally, open the raw TL, or Telegram's own long press menu (url buttons).
 */
public class RawBotButtonSheet extends BottomSheet {

    /** Off: long press on bot buttons behaves exactly like in Telegram. */
    public static boolean enabled = true;

    private final int account;
    private final MessageObject message;
    private final TL_keyboard.KeyboardButtonProto button;
    private final Theme.ResourcesProvider resourcesProvider;
    private final LinearLayout list;
    private final LinearLayout actions;

    public static RawBotButtonSheet show(Context context, int account, MessageObject message, TL_keyboard.KeyboardButtonProto button,
                                         Theme.ResourcesProvider resourcesProvider, Runnable press, Runnable original) {
        RawBotButtonSheet sheet = new RawBotButtonSheet(context, account, message, button, resourcesProvider, press, original);
        sheet.show();
        return sheet;
    }

    private RawBotButtonSheet(Context context, int account, MessageObject message, TL_keyboard.KeyboardButtonProto button,
                              Theme.ResourcesProvider resourcesProvider, Runnable press, Runnable original) {
        super(context, false, resourcesProvider);
        this.account = account;
        this.message = message;
        this.button = button;
        this.resourcesProvider = resourcesProvider;
        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        String text = button.getText();
        title.setText(TextUtils.isEmpty(text) ? "Кнопка" : text);
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 12, 16, 0));

        TextView subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        subtitle.setText(typeName(button.getType()) + " · сообщение #" + message.getId());
        root.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 2, 16, 6));

        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = (int) (AndroidUtilities.displaySize.y * 0.6f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fillFields();
        addLastAnswer();

        HorizontalScrollView actionsScroll = new HorizontalScrollView(context);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        actionsScroll.addView(actions);
        root.addView(actionsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 10));

        if (press != null && !(button.getType() instanceof TL_keyboard.TL_inlineButtonTypeDisabled)) {
            addAction("Нажать", true, v -> {
                dismiss();
                press.run();
            });
        }
        addAction("Raw", false, v -> showRaw());
        if (original != null) {
            addAction("Меню ссылки", false, v -> {
                dismiss();
                original.run();
            });
        }
        ArrayList<RawCallbackLog.Entry> all = RawCallbackLog.all();
        if (!all.isEmpty()) {
            addAction("Все ответы (" + all.size() + ")", false, v -> showAllAnswers(all));
        }

        setCustomView(root);
        RawMotion.reveal(root);
    }

    // ---- fields ----

    private void fillFields() {
        TL_keyboard.ButtonTypeProto type = button.getType();
        field("Тип", typeName(type) + (constructorOf(type) != null ? "  " + constructorOf(type) : ""), false);
        field("Текст", button.getText(), false);
        if (type instanceof TL_keyboard.TL_inlineButtonTypeCallback) {
            TL_keyboard.TL_inlineButtonTypeCallback callback = (TL_keyboard.TL_inlineButtonTypeCallback) type;
            byte[] data = callback.data != null ? callback.data : new byte[0];
            String utf8 = printableUtf8(data);
            if (utf8 != null) {
                field("data (UTF-8)", utf8, true);
            }
            field("data (hex)", Utilities.bytesToHex(data).toLowerCase(Locale.US), true);
            field("data (base64)", Base64.encodeToString(data, Base64.NO_WRAP), true);
            field("Длина", data.length + " байт" + (data.length >= 64 ? " (лимит Bot API — 64)" : ""), false);
            if (callback.requires_password) {
                field("requires_password", "да — нажатие спросит пароль 2FA", false);
            }
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeUrl) {
            field("url", ((TL_keyboard.TL_inlineButtonTypeUrl) type).url, true);
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeWebView) {
            field("url", ((TL_keyboard.TL_inlineButtonTypeWebView) type).url, true);
        } else if (type instanceof TL_keyboard.TL_buttonTypeSimpleWebView) {
            field("url", ((TL_keyboard.TL_buttonTypeSimpleWebView) type).url, true);
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeUrlAuth) {
            TL_keyboard.TL_inlineButtonTypeUrlAuth urlAuth = (TL_keyboard.TL_inlineButtonTypeUrlAuth) type;
            field("url", urlAuth.url, true);
            field("fwd_text", urlAuth.fwd_text, false);
            field("button_id", String.valueOf(urlAuth.button_id), true);
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeSwitchInline) {
            TL_keyboard.TL_inlineButtonTypeSwitchInline inline = (TL_keyboard.TL_inlineButtonTypeSwitchInline) type;
            field("query", TextUtils.isEmpty(inline.query) ? "(пусто)" : inline.query, true);
            field("same_peer", inline.same_peer ? "да — запрос в этом же чате" : "нет — выбор чата", false);
            if (inline.peer_types != null && !inline.peer_types.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (TLRPC.InlineQueryPeerType peerType : inline.peer_types) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(typeName(peerType));
                }
                field("peer_types", sb.toString(), false);
            }
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeUserProfile) {
            long userId = ((TL_keyboard.TL_inlineButtonTypeUserProfile) type).user_id;
            field("user_id", String.valueOf(userId), true);
            TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
            if (user != null) {
                String username = UserObject.getPublicUsername(user);
                field("Пользователь", UserObject.getUserName(user) + (username != null ? " · @" + username : ""), false);
            }
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeCopy) {
            field("copy_text", ((TL_keyboard.TL_inlineButtonTypeCopy) type).copy_text, true);
        } else if (type instanceof TL_keyboard.TL_buttonTypeRequestPeer) {
            TL_keyboard.TL_buttonTypeRequestPeer requestPeer = (TL_keyboard.TL_buttonTypeRequestPeer) type;
            field("button_id", String.valueOf(requestPeer.button_id), true);
            field("peer_type", typeName(requestPeer.peer_type), false);
            field("max_quantity", String.valueOf(requestPeer.max_quantity), false);
        }
        long botId = message.messageOwner != null && message.messageOwner.via_bot_id != 0 ? message.messageOwner.via_bot_id : message.getFromChatId();
        if (botId > 0) {
            TLRPC.User bot = MessagesController.getInstance(account).getUser(botId);
            String username = bot != null ? UserObject.getPublicUsername(bot) : null;
            field(message.messageOwner != null && message.messageOwner.via_bot_id != 0 ? "Через бота" : "Отправитель",
                    (username != null ? "@" + username + " · " : "") + botId, true);
        }
    }

    /** A label/value row; tap copies the value. */
    private void field(String label, String value, boolean mono) {
        if (value == null) {
            return;
        }
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(7), AndroidUtilities.dp(8), AndroidUtilities.dp(7));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView labelView = new TextView(context);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        labelView.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        labelView.setText(label);
        texts.addView(labelView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView valueView = new TextView(context);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, mono ? 14 : 15);
        valueView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        if (mono) {
            valueView.setTypeface(Typeface.MONOSPACE);
        }
        valueView.setMaxLines(8);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setText(value.isEmpty() ? "(пусто)" : value);
        texts.addView(valueView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        ImageView copy = new ImageView(context);
        copy.setImageResource(R.drawable.msg_copy);
        copy.setScaleType(ImageView.ScaleType.CENTER);
        copy.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_dialogTextGray2), PorterDuff.Mode.SRC_IN));
        row.addView(copy, LayoutHelper.createLinear(36, 36));

        row.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(value);
            RawNotify.show(this, R.drawable.msg_copy, label + " скопировано");
            RawMotion.copied(copy);
        });
        RawMotion.pressable(row, 0.98f);
        list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void addLastAnswer() {
        RawCallbackLog.Entry entry = RawCallbackLog.last(account, message, button);
        if (entry == null) {
            return;
        }
        Context context = getContext();
        int text = getThemedColor(Theme.key_dialogTextBlack);
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(12));
        bg.setColor(Theme.multAlpha(text, 0.06f));
        bg.setStroke(AndroidUtilities.dp(1), Theme.multAlpha(text, 0.10f));
        block.setBackground(bg);

        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        label.setTypeface(AndroidUtilities.bold());
        label.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        label.setText("Последний ответ · " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(entry.time)) + " · Raw ›");
        block.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView summary = new TextView(context);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        summary.setTextColor(entry.error != null ? getThemedColor(Theme.key_text_RedRegular) : text);
        summary.setMaxLines(6);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        summary.setText(entry.summary());
        block.addView(summary, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        block.setOnClickListener(v -> RawCallbackLog.createRawSheet(getContext(), entry, resourcesProvider).show());
        list.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 8, 12, 0));
    }

    // ---- actions ----

    private void addAction(String text, boolean primary, View.OnClickListener listener) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        if (primary) {
            chip.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
            chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(19), accent, Theme.multAlpha(accent, 0.8f)));
        } else {
            chip.setTextColor(accent);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(AndroidUtilities.dp(19));
            bg.setStroke(AndroidUtilities.dp(1.5f), Theme.multAlpha(accent, 0.6f));
            bg.setColor(Theme.multAlpha(accent, 0.06f));
            chip.setBackground(bg);
        }
        chip.setOnClickListener(listener);
        actions.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 38, 0, 0, 8, 0));
    }

    private void showRaw() {
        String text = button.getText();
        RawObjectSheet sheet = new RawObjectSheet(getContext(), account, TextUtils.isEmpty(text) ? "Кнопка" : "Кнопка «" + text + "»", button, resourcesProvider);
        sheet.addObjectTab("Кнопка", () -> sheet.setObject(null, button));
        sheet.addObjectTab("Тип", () -> sheet.setObject(null, button.getType()));
        TLRPC.ReplyMarkup markup = message.messageOwner != null ? message.messageOwner.reply_markup : null;
        if (markup != null) {
            sheet.addObjectTab("reply_markup", () -> sheet.setObject(null, markup));
        }
        RawCallbackLog.Entry last = RawCallbackLog.last(account, message, button);
        if (last != null) {
            sheet.addObjectTab("Ответ", () -> sheet.setObject(last.summary(), last.raw()));
        }
        sheet.show();
    }

    private void showAllAnswers(ArrayList<RawCallbackLog.Entry> all) {
        ArrayList<Object> items = new ArrayList<>();
        // newest first
        for (int i = all.size() - 1; i >= 0; i--) {
            RawCallbackLog.Entry entry = all.get(i);
            java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("summary", entry.summary());
            item.put("dialog_id", entry.dialogId);
            item.put("msg_id", entry.msgId);
            item.put("button", entry.button != null ? entry.button.getText() : null);
            item.put("raw", entry.raw());
            items.add(item);
        }
        RawObjectSheet sheet = new RawObjectSheet(getContext(), account, "Ответы на кнопку", items, resourcesProvider);
        sheet.setSubtitle(all.size() + " последних ответов, сначала новые");
        sheet.show();
    }

    // ---- helpers ----

    static String typeName(Object object) {
        String name = TLDumper.typeName(object);
        return name.startsWith("TL_") ? name.substring(3) : name;
    }

    private static String constructorOf(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return String.format("#%08x", object.getClass().getField("constructor").getInt(null));
        } catch (Throwable e) {
            return null;
        }
    }

    /** The data as text if it is valid UTF-8 without control characters, else null. */
    static String printableUtf8(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                    return null;
                }
            }
            return text;
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
