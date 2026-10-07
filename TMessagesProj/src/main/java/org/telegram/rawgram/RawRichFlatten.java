package org.telegram.rawgram;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.tl.TL_iv;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * A rich_message (Instant View blocks, no message text or entities) flattened into plain text with ranges, so
 * RawEntitiesSheet can highlight it like entities: one range per block (pre-order, with nesting depth) and one per
 * formatting node of the RichText trees (bold, url, custom_emoji, ...). Blocks are walked by reflection over their
 * fields, so every pageBlock type (lists, tables, details, captions) is covered without a case per type.
 */
final class RawRichFlatten {

    static final class Span {
        int start;
        int end;
        int depth;
        boolean block;
        String type;
        TLObject node;
        /** Non-default scalar fields of the node: "url: …", "document_id: …". */
        String detail;
        /** What a copy should take: url / email / id, else the covered text. */
        String value;
    }

    final StringBuilder text = new StringBuilder();
    final ArrayList<Span> spans = new ArrayList<>();
    int blockCount;

    static RawRichFlatten of(TL_iv.RichMessage message) {
        RawRichFlatten f = new RawRichFlatten();
        if (message != null) {
            for (TL_iv.PageBlock block : message.blocks) {
                f.block(block, 0);
            }
        }
        while (f.text.length() > 0 && f.text.charAt(f.text.length() - 1) == '\n') {
            f.text.setLength(f.text.length() - 1);
        }
        for (Span s : f.spans) {
            s.end = Math.min(s.end, f.text.length());
            s.start = Math.min(s.start, s.end);
            if (s.value == null) {
                s.value = f.text.substring(s.start, s.end);
            }
        }
        return f;
    }

    private void block(TL_iv.PageBlock block, int depth) {
        if (block == null) {
            return;
        }
        Span span = new Span();
        span.block = true;
        span.type = typeName(block, "pageBlock");
        span.depth = depth;
        span.node = block;
        span.start = text.length();
        spans.add(span);
        blockCount++;
        walk(block, depth + 1, span);
        int end = text.length();
        if (end > span.start && text.charAt(end - 1) != '\n') {
            text.append('\n');
        }
        span.end = end;
    }

    /** Fields of a block or a helper object (list item, table row/cell, caption): text, nested blocks, scalars. */
    private void walk(Object node, int depth, Span owner) {
        StringBuilder detail = owner != null && owner.node == node ? new StringBuilder() : null;
        // two passes: the node's own RichText (a details/table title) comes before the blocks it holds, whatever the
        // field order in the class is (pageBlockDetails declares blocks before title)
        for (int pass = 0; pass < 2; pass++)
        for (Field field : TLDumper.fieldsOf(node.getClass())) {
            if (field.getName().startsWith("parent")) {
                continue;
            }
            Object value;
            try {
                value = field.get(node);
            } catch (Throwable e) {
                continue;
            }
            if (value == null || (pass == 0) != (value instanceof TL_iv.RichText)) {
                continue;
            }
            if (value instanceof TL_iv.RichText) {
                int before = text.length();
                rich((TL_iv.RichText) value, depth);
                if (text.length() > before && text.charAt(text.length() - 1) != '\n') {
                    text.append('\n');
                }
            } else if (value instanceof TL_iv.PageBlock) {
                block((TL_iv.PageBlock) value, depth);
            } else if (value instanceof List) {
                for (Object item : (List<?>) value) {
                    if (item instanceof TL_iv.PageBlock) {
                        block((TL_iv.PageBlock) item, depth);
                    } else if (item instanceof TL_iv.RichText) {
                        rich((TL_iv.RichText) item, depth);
                        if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') {
                            text.append('\n');
                        }
                    } else if (item instanceof TLObject) {
                        walk(item, depth, null);
                    }
                }
            } else if (value instanceof TL_iv.PageCaption || value instanceof TL_iv.PageListItem
                    || value instanceof TL_iv.PageListOrderedItem) {
                walk(value, depth, null);
            } else if (detail != null) {
                scalar(detail, owner, field.getName(), value);
            }
        }
        if (detail != null && detail.length() > 0) {
            owner.detail = detail.toString();
        }
    }

    private void rich(TL_iv.RichText rt, int depth) {
        if (rt == null || rt instanceof TL_iv.textEmpty) {
            return;
        }
        if (rt instanceof TL_iv.textPlain) {
            String plain = ((TL_iv.textPlain) rt).text;
            if (plain != null) {
                text.append(plain);
            }
            return;
        }
        if (rt instanceof TL_iv.textConcat) {
            for (TL_iv.RichText child : rt.texts) {
                rich(child, depth);
            }
            return;
        }
        Span span = new Span();
        span.type = typeName(rt, "text");
        span.depth = depth;
        span.node = rt;
        span.start = text.length();
        spans.add(span);
        if (rt.text != null) {
            rich(rt.text, depth + 1);
        }
        if (!rt.texts.isEmpty()) {
            for (TL_iv.RichText child : rt.texts) {
                rich(child, depth + 1);
            }
        }
        if (text.length() == span.start) {
            text.append(placeholder(rt));
        }
        span.end = text.length();
        StringBuilder detail = new StringBuilder();
        for (Field field : TLDumper.fieldsOf(rt.getClass())) {
            if (field.getName().startsWith("parent")) {
                continue;
            }
            try {
                Object value = field.get(rt);
                if (value != null && !(value instanceof TLObject) && !(value instanceof List)) {
                    scalar(detail, span, field.getName(), value);
                }
            } catch (Throwable ignore) {
            }
        }
        if (detail.length() > 0) {
            span.detail = detail.toString();
        }
    }

    private static String placeholder(TL_iv.RichText rt) {
        if (rt instanceof TL_iv.textCustomEmoji) {
            String alt = ((TL_iv.textCustomEmoji) rt).alt;
            return alt != null && !alt.isEmpty() ? alt : "◻";
        }
        if (rt instanceof TL_iv.textMath) {
            String source = ((TL_iv.textMath) rt).source;
            return source != null && !source.isEmpty() ? source : "∑";
        }
        if (rt instanceof TL_iv.textImage) {
            return "🖼";
        }
        if (rt instanceof TL_iv.textDate) {
            return "📅";
        }
        return "·";
    }

    /** Appends a non-default scalar as "name: value" and picks the copy value (url, email, phone, ids). */
    private static void scalar(StringBuilder detail, Span span, String name, Object value) {
        if ("flags".equals(name) || "flags2".equals(name)) {
            return;
        }
        if (value instanceof Boolean && !(Boolean) value
                || value instanceof Number && ((Number) value).longValue() == 0
                || value instanceof String && ((String) value).isEmpty()) {
            return;
        }
        if (!(value instanceof Boolean || value instanceof Number || value instanceof String)) {
            return;
        }
        if (detail.length() > 0) {
            detail.append('\n');
        }
        if (value instanceof Boolean) {
            detail.append(name);
        } else {
            detail.append(name).append(": ").append(value);
        }
        if (span.value == null && !(value instanceof Boolean)
                && (name.equals("url") || name.equals("email") || name.equals("phone") || name.endsWith("_id"))) {
            span.value = String.valueOf(value);
        }
    }

    /** pageBlockParagraph → paragraph, textMentionName → mention_name; layer copies use their base name. */
    static String typeName(Object node, String prefix) {
        String name = node.getClass().getSimpleName();
        int layer = name.indexOf("_layer");
        if (layer > 0) {
            name = name.substring(0, layer);
        }
        if (name.startsWith("TL_")) {
            name = name.substring(3);
        }
        if (name.startsWith(prefix) && name.length() > prefix.length()) {
            name = name.substring(prefix.length());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
