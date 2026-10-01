package org.telegram.rawgram;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.LayoutHelper;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Entities overlay: the message text with every entity range highlighted (color per type), a list of the
 * entities (type, offset/length in UTF-16 units, covered substring, extra fields) and the reply_markup buttons.
 */
public class RawEntitiesSheet extends BottomSheet {

    private final int account;
    private final Theme.ResourcesProvider rp;
    private final String text;
    private final ArrayList<TLRPC.MessageEntity> entities;
    private final LinearLayout list;
    private final ArrayList<View> entityRows = new ArrayList<>();
    private AnimatedEmojiSpan.TextViewEmojis textView;
    private ScrollView textScroll;
    /** Text with emoji spans only; highlight spans are layered over a copy of it. */
    private CharSequence baseText;
    private int selected = -1;

    public RawEntitiesSheet(BaseFragment fragment, int account, MessageObject message, Theme.ResourcesProvider rp) {
        super(fragment.getParentActivity(), false, rp);
        this.account = account;
        this.rp = rp;
        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));
        Context context = getContext();
        TLRPC.Message m = message.messageOwner;
        text = m.message != null ? m.message : "";
        entities = m.entities != null ? m.entities : new ArrayList<>();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText("Форматирование · #" + message.getId());
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 0));

        TextView subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        subtitle.setText("сущностей " + entities.size() + " · длина " + text.length() + " (UTF-16) · "
                + text.codePointCount(0, text.length()) + " кодпоинтов");
        root.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 2, 16, 8));

        HorizontalScrollView actionsScroll = new HorizontalScrollView(context);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(8), 0);
        actionsScroll.addView(actions);
        root.addView(actionsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        if (!entities.isEmpty()) {
            chip(actions, "Raw entities", () -> new RawObjectSheet(getContext(), account, "messageOwner.entities", entities, rp).show());
        }
        if (m.reply_markup != null) {
            TLRPC.ReplyMarkup markup = m.reply_markup;
            chip(actions, "Raw reply_markup", () -> new RawObjectSheet(getContext(), account, "messageOwner.reply_markup", markup, rp).show());
        }
        if (!text.isEmpty()) {
            chip(actions, "Копировать текст", () -> copy(text, "Текст скопирован"));
        }

        if (!text.isEmpty()) {
            // the text itself, in a code-block-like frame
            int base = getThemedColor(Theme.key_dialogTextBlack);
            textScroll = new ScrollView(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int max = (int) (AndroidUtilities.displaySize.y * 0.28f);
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
                }
            };
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(AndroidUtilities.dp(12));
            bg.setColor(Theme.multAlpha(base, 0.06f));
            bg.setStroke(AndroidUtilities.dp(1), Theme.multAlpha(base, 0.10f));
            textScroll.setBackground(bg);
            textView = new AnimatedEmojiSpan.TextViewEmojis(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textView.setTextColor(base);
            textView.setTextIsSelectable(true);
            textView.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
            textScroll.addView(textView, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
            root.addView(textScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 8));
            Paint.FontMetricsInt fm = textView.getPaint().getFontMetricsInt();
            SpannableStringBuilder sb = new SpannableStringBuilder(text);
            Emoji.replaceEmoji(sb, fm, false);
            // custom emoji drawn in place of their alt text, like in the chat
            baseText = MessageObject.replaceAnimatedEmoji(sb, entities, fm);
            renderText();
        }

        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = (int) (AndroidUtilities.displaySize.y * 0.42f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, AndroidUtilities.dp(12));
        scroll.addView(list, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (!entities.isEmpty()) {
            section("Сущности (" + entities.size() + ")");
            for (int i = 0; i < entities.size(); i++) {
                addEntity(i, entities.get(i));
            }
            hint("Нажатие — подсветить в тексте, долгое — скопировать значение (url, id) или подстроку.");
        }
        if (m.reply_markup != null) {
            addMarkup(m.reply_markup);
        }
        if (entities.isEmpty() && m.reply_markup == null) {
            hint("Сущностей и кнопок нет.");
        }

        setCustomView(root);
        RawMotion.reveal(root);
    }

    // ---- text overlay ----

    private void renderText() {
        if (textView == null) {
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(baseText);
        int len = sb.length();
        for (int i = 0; i < entities.size(); i++) {
            TLRPC.MessageEntity e = entities.get(i);
            int start = Math.max(0, Math.min(e.offset, len));
            int end = Math.max(start, Math.min(e.offset + e.length, len));
            if (start == end) continue;
            int color = typeColor(entityType(e));
            boolean isSelected = i == selected;
            // wide containers (pre/blockquote) get a lighter tint so nested entities stay visible
            float alpha = isSelected ? 0.45f : isContainer(e) ? 0.10f : 0.22f;
            if (selected >= 0 && !isSelected) alpha *= 0.5f;
            sb.setSpan(new BackgroundColorSpan(Theme.multAlpha(color, alpha)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (isSelected) {
                sb.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        textView.setText(sb);
    }

    private void select(int index) {
        selected = selected == index ? -1 : index;
        for (int i = 0; i < entityRows.size(); i++) {
            entityRows.get(i).setBackground(rowBackground(i == selected));
        }
        renderText();
        if (selected < 0 || textView == null) {
            return;
        }
        TLRPC.MessageEntity e = entities.get(selected);
        textView.post(() -> {
            Layout layout = textView.getLayout();
            if (layout == null) return;
            int offset = Math.max(0, Math.min(e.offset, layout.getText().length()));
            int top = layout.getLineTop(layout.getLineForOffset(offset)) + textView.getPaddingTop();
            textScroll.smoothScrollTo(0, Math.max(0, top - AndroidUtilities.dp(24)));
        });
    }

    // ---- entities list ----

    private void addEntity(int index, TLRPC.MessageEntity e) {
        String type = entityType(e);
        int len = text.length();
        int start = Math.max(0, Math.min(e.offset, len));
        int end = Math.max(start, Math.min(e.offset + e.length, len));
        String covered = text.substring(start, end);

        StringBuilder sb = new StringBuilder();
        sb.append('#').append(index).append("  ").append(type);
        if (e instanceof TLRPC.TL_messageEntityPre && !TextUtils.isEmpty(e.language)) sb.append('(').append(e.language).append(')');
        sb.append("  @").append(e.offset).append('+').append(e.length);
        if (e.offset + e.length > len || e.offset < 0) sb.append("  ⚠ вне текста");
        sb.append("\n«").append(ellipsize(covered.replace("\n", "⏎"), 120)).append('»');

        String extra = null;
        if (e instanceof TLRPC.TL_messageEntityMentionName) {
            long userId = ((TLRPC.TL_messageEntityMentionName) e).user_id;
            extra = Long.toString(userId);
            RawPeers.Info info = RawPeers.user(account, userId);
            sb.append("\nuser_id: ").append(info != null ? info.format() : extra);
        } else if (e instanceof TLRPC.TL_inputMessageEntityMentionName) {
            TLRPC.InputUser user = ((TLRPC.TL_inputMessageEntityMentionName) e).user_id;
            if (user != null) {
                extra = Long.toString(user.user_id);
                sb.append("\nuser_id: ").append(user.user_id);
            }
        } else if (e instanceof TLRPC.TL_messageEntityCustomEmoji) {
            long documentId = ((TLRPC.TL_messageEntityCustomEmoji) e).document_id;
            extra = Long.toString(documentId);
            sb.append("\ndocument_id: ").append(documentId);
            TLRPC.Document doc = ((TLRPC.TL_messageEntityCustomEmoji) e).document;
            if (doc == null) doc = AnimatedEmojiDrawable.findDocument(account, documentId);
            if (doc != null) {
                String alt = null;
                for (TLRPC.DocumentAttribute a : doc.attributes) {
                    if (a instanceof TLRPC.TL_documentAttributeCustomEmoji) alt = a.alt;
                }
                if (!TextUtils.isEmpty(alt)) sb.append(" · alt ").append(alt);
                if (doc.mime_type != null) sb.append(" · ").append(doc.mime_type);
            }
        } else if (e instanceof TLRPC.TL_messageEntityFormattedDate) {
            int date = ((TLRPC.TL_messageEntityFormattedDate) e).date;
            extra = Integer.toString(date);
            sb.append("\ndate: ").append(date);
        }
        if (!TextUtils.isEmpty(e.url)) {
            extra = e.url;
            sb.append("\nurl: ").append(e.url);
        }
        if (!(e instanceof TLRPC.TL_messageEntityPre) && !TextUtils.isEmpty(e.language)) {
            sb.append("\nlanguage: ").append(e.language);
        }
        if (e.collapsed) {
            sb.append("\ncollapsed");
        }

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(rowBackground(false));
        View bar = new View(getContext());
        GradientDrawable barBg = new GradientDrawable();
        barBg.setCornerRadius(AndroidUtilities.dp(2));
        barBg.setColor(typeColor(type));
        bar.setBackground(barBg);
        row.addView(bar, LayoutHelper.createLinear(4, LayoutHelper.MATCH_PARENT, 8, 8, 0, 8));
        TextView label = mono();
        label.setText(sb);
        row.addView(label, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        final String copyValue = extra != null ? extra : covered;
        row.setOnClickListener(v -> {
            if (textView == null) {
                copy(copyValue, "Скопировано");
                RawMotion.copied(v);
            } else {
                select(index);
            }
        });
        row.setOnLongClickListener(v -> {
            copy(copyValue, "Скопировано: " + ellipsize(copyValue, 60));
            RawMotion.copied(v);
            return true;
        });
        entityRows.add(row);
        list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 6));
    }

    // ---- reply_markup ----

    private void addMarkup(TLRPC.ReplyMarkup markup) {
        ArrayList<ArrayList<TL_keyboard.KeyboardButtonProto>> rows = new ArrayList<>();
        if (markup instanceof TLRPC.TL_replyInlineMarkup) {
            for (TL_keyboard.KeyboardInlineButtonRow r : ((TLRPC.TL_replyInlineMarkup) markup).rows) {
                rows.add(new ArrayList<TL_keyboard.KeyboardButtonProto>(r.buttons));
            }
        } else if (markup instanceof TLRPC.TL_replyKeyboardMarkup) {
            for (TL_keyboard.KeyboardButtonRow r : ((TLRPC.TL_replyKeyboardMarkup) markup).rows) {
                rows.add(new ArrayList<TL_keyboard.KeyboardButtonProto>(r.buttons));
            }
        }
        int count = 0;
        for (ArrayList<TL_keyboard.KeyboardButtonProto> r : rows) count += r.size();
        section("reply_markup · " + snake(strip(TLDumper.typeName(markup), "TL_")) + (rows.isEmpty() ? "" : " (" + rows.size() + " рядов, " + count + " кнопок)"));

        StringBuilder flags = new StringBuilder();
        if (markup.resize) flags.append("resize ");
        if (markup.single_use) flags.append("single_use ");
        if (markup.is_persistent) flags.append("persistent ");
        if (markup.selective) flags.append("selective ");
        if (markup.force_reply) flags.append("force_reply ");
        if (!TextUtils.isEmpty(markup.placeholder)) flags.append("placeholder «").append(markup.placeholder).append("» ");
        if (flags.length() > 0) hint(flags.toString().trim());

        for (int r = 0; r < rows.size(); r++) {
            ArrayList<TL_keyboard.KeyboardButtonProto> buttons = rows.get(r);
            for (int c = 0; c < buttons.size(); c++) {
                addButton(r, c, buttons.get(c));
            }
        }
        if (!rows.isEmpty()) {
            hint("Нажатие — скопировать data/url/query, долгое — raw кнопки.");
        }
    }

    private void addButton(int r, int c, TL_keyboard.KeyboardButtonProto button) {
        Object type = button.getType();
        String typeName = type != null ? buttonType(type) : TLDumper.typeName(button);
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(r).append(':').append(c).append("]  ").append(typeName).append("\n«").append(button.getText()).append('»');
        String value = button.getText();
        if (type instanceof TL_keyboard.TL_inlineButtonTypeCallback) {
            TL_keyboard.TL_inlineButtonTypeCallback cb = (TL_keyboard.TL_inlineButtonTypeCallback) type;
            byte[] data = cb.data;
            if (data != null) {
                String utf8 = printableUtf8(data);
                sb.append("\ndata[").append(data.length).append("]");
                if (utf8 != null) sb.append(" utf8: ").append(utf8);
                sb.append("\nhex: ").append(hex(data));
                String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
                sb.append("\nbase64: ").append(b64);
                value = utf8 != null ? utf8 : hex(data);
            }
            if (cb.requires_password) sb.append("\nrequires_password");
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeUrl) {
            value = ((TL_keyboard.TL_inlineButtonTypeUrl) type).url;
            sb.append("\nurl: ").append(value);
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeUrlAuth) {
            TL_keyboard.TL_inlineButtonTypeUrlAuth auth = (TL_keyboard.TL_inlineButtonTypeUrlAuth) type;
            value = auth.url;
            sb.append("\nurl: ").append(auth.url).append("\nbutton_id: ").append(auth.button_id);
            if (!TextUtils.isEmpty(auth.fwd_text)) sb.append("\nfwd_text: ").append(auth.fwd_text);
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeWebView) {
            value = ((TL_keyboard.TL_inlineButtonTypeWebView) type).url;
            sb.append("\nurl: ").append(value);
        } else if (type instanceof TL_keyboard.TL_buttonTypeSimpleWebView) {
            value = ((TL_keyboard.TL_buttonTypeSimpleWebView) type).url;
            sb.append("\nurl: ").append(value);
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeSwitchInline) {
            TL_keyboard.TL_inlineButtonTypeSwitchInline sw = (TL_keyboard.TL_inlineButtonTypeSwitchInline) type;
            value = sw.query;
            sb.append("\nquery: «").append(sw.query).append('»');
            if (sw.same_peer) sb.append(" · same_peer");
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeUserProfile) {
            long userId = ((TL_keyboard.TL_inlineButtonTypeUserProfile) type).user_id;
            value = Long.toString(userId);
            RawPeers.Info info = RawPeers.user(account, userId);
            sb.append("\nuser_id: ").append(info != null ? info.format() : value);
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeCopy) {
            value = ((TL_keyboard.TL_inlineButtonTypeCopy) type).copy_text;
            sb.append("\ncopy_text: ").append(value);
        } else if (type instanceof TL_keyboard.TL_buttonTypeRequestPeer) {
            TL_keyboard.TL_buttonTypeRequestPeer rq = (TL_keyboard.TL_buttonTypeRequestPeer) type;
            sb.append("\nbutton_id: ").append(rq.button_id).append(" · max_quantity: ").append(rq.max_quantity);
            if (rq.peer_type != null) sb.append(" · ").append(TLDumper.typeName(rq.peer_type));
        }

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(rowBackground(false));
        TextView label = mono();
        label.setText(sb);
        row.addView(label, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final String copyValue = value != null ? value : "";
        row.setOnClickListener(v -> {
            copy(copyValue, "Скопировано: " + ellipsize(copyValue, 60));
            RawMotion.copied(v);
        });
        row.setOnLongClickListener(v -> {
            RawObjectSheet sheet = new RawObjectSheet(getContext(), account, "Кнопка [" + r + ":" + c + "]", button, rp);
            sheet.addObjectTab("Button", () -> sheet.setObject(null, button));
            if (type != null) {
                sheet.addObjectTab("Type", () -> sheet.setObject(null, type));
            }
            sheet.show();
            return true;
        });
        list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 6));
    }

    // ---- naming / encoding ----

    /** TL_messageEntityTextUrl → text_url, TL_inputMessageEntityMentionName → input_mention_name; layer copies use their base name. */
    static String entityType(TLRPC.MessageEntity e) {
        Class<?> cls = e.getClass();
        while (cls.getSuperclass() != null && cls.getSuperclass() != TLRPC.MessageEntity.class && cls.getSimpleName().contains("_layer")) {
            cls = cls.getSuperclass();
        }
        String name = cls.getSimpleName();
        if (name.startsWith("TL_inputMessageEntity")) {
            return "input_" + snake(name.substring("TL_inputMessageEntity".length()));
        }
        if (name.startsWith("TL_messageEntity")) {
            return snake(name.substring("TL_messageEntity".length()));
        }
        return name;
    }

    private static String buttonType(Object type) {
        String name = TLDumper.typeName(type);
        for (String prefix : new String[]{"TL_inputInlineButtonType", "TL_inlineButtonType", "TL_inputButtonType", "TL_buttonType"}) {
            if (name.startsWith(prefix)) {
                String rest = name.substring(prefix.length());
                int layer = rest.indexOf("_layer");
                return snake(layer >= 0 ? rest.substring(0, layer) : rest);
            }
        }
        return name;
    }

    private static String strip(String s, String prefix) {
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }

    /** CamelCase → snake_case; underscores already present are kept. */
    private static String snake(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') sb.append('_');
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static boolean isContainer(TLRPC.MessageEntity e) {
        return e instanceof TLRPC.TL_messageEntityPre || e instanceof TLRPC.TL_messageEntityBlockquote;
    }

    private static int typeColor(String type) {
        switch (type) {
            case "bold": return 0xFFEF5350;
            case "italic": return 0xFFAB47BC;
            case "underline": return 0xFF5C6BC0;
            case "strike": return 0xFF8D6E63;
            case "spoiler": return 0xFF78909C;
            case "code": return 0xFF26A69A;
            case "pre": return 0xFF00897B;
            case "url": return 0xFF42A5F5;
            case "text_url": return 0xFF1E88E5;
            case "mention": return 0xFFFFA726;
            case "mention_name":
            case "input_mention_name": return 0xFFFB8C00;
            case "hashtag":
            case "cashtag": return 0xFF66BB6A;
            case "bot_command": return 0xFF9CCC65;
            case "email":
            case "phone":
            case "bank_card": return 0xFF29B6F6;
            case "custom_emoji": return 0xFFFFCA28;
            case "blockquote": return 0xFF9E9E9E;
            case "formatted_date": return 0xFFEC407A;
            default:
                int[] palette = {0xFFD4E157, 0xFF7E57C2, 0xFF26C6DA, 0xFFFF7043};
                return palette[Math.abs(type.hashCode()) % palette.length];
        }
    }

    /** Callback data as text when it is valid UTF-8 without control characters, else null. */
    static String printableUtf8(byte[] data) {
        if (data.length == 0) return "";
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data));
            String s = chars.toString();
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (Character.isISOControl(ch) && ch != '\n' && ch != '\t') return null;
            }
            return s;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02x", data[i] & 0xff));
        }
        return sb.toString();
    }

    private static String ellipsize(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private void copy(String value, String toast) {
        AndroidUtilities.addToClipboard(value);
        RawNotify.show(this, R.drawable.msg_copy, toast);
    }

    // ---- views ----

    private void section(String text) {
        TextView header = new TextView(getContext());
        header.setText(text);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        list.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 6, 16, 6));
    }

    private void hint(String text) {
        TextView hint = new TextView(getContext());
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        hint.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        hint.setText(text);
        list.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 8));
    }

    private TextView mono() {
        TextView view = new TextView(getContext());
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        view.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        view.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        return view;
    }

    private android.graphics.drawable.Drawable rowBackground(boolean selected) {
        int base = getThemedColor(Theme.key_dialogTextBlack);
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(AndroidUtilities.dp(10));
            bg.setColor(Theme.multAlpha(accent, 0.08f));
            bg.setStroke(AndroidUtilities.dp(1.5f), accent);
            return bg;
        }
        return Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10), Theme.multAlpha(base, 0.04f), Theme.multAlpha(base, 0.12f));
    }

    private void chip(LinearLayout row, String text, Runnable onClick) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        chip.setTextColor(accent);
        chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), Theme.multAlpha(accent, 0.12f), Theme.multAlpha(accent, 0.24f)));
        chip.setOnClickListener(v -> onClick.run());
        row.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
    }
}
