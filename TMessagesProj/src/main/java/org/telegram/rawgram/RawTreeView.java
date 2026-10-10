package org.telegram.rawgram;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Base64;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.RecyclerListView;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * "Tree (beta)" view of the raw code block: the dumped object as a collapsible tree.
 * Nodes are built lazily by reflection with the same field enumeration and null handling as
 * {@link TLDumper} (null fields of TL objects are skipped), visible nodes are flattened into
 * rows of a RecyclerListView. Tap toggles containers (and long strings / byte arrays),
 * long press copies a value or the JSON of a subtree.
 */
public class RawTreeView extends RecyclerListView {

    public interface CopyHandler {
        void onCopy(String text, String toast);
    }

    /** Long press on an object node: true when it showed its own menu (media files); {@code copy} is the usual copy. */
    public interface MediaHandler {
        boolean onLongPress(View row, Object source, List<Object> ancestors, Runnable copy);
    }

    private static final int MAX_DEPTH = 32;
    private static final int PAGE = 50;
    private static final int MORE_STEP = 200;
    private static final int LONG_STRING = 48;
    private static final int COLLAPSED_STRING = 300;
    private static final int MAX_STRING_SHOWN = 20_000;
    private static final int MAX_HEX_BYTES = 4096;
    private static final int PREVIEW_BYTES = 8;
    private static final int ROOT_EXPAND_LIMIT = 10;

    static final int KIND_VALUE = 0, KIND_OBJECT = 1, KIND_LIST = 2, KIND_MAP = 3, KIND_BYTES = 4;

    /** One node of the tree; children are created on first access. */
    static final class Node {
        String key;
        boolean indexKey;
        Node parent;
        int depth;
        int kind;
        Object source;
        /** Plain text shown instead of the value (cycles, depth limit, non-TL objects). */
        String marker;
        boolean expanded;
        /** Leaves: 0 collapsed; strings 1 = full; bytes 1 = hex, 2 = base64. */
        int valueMode;
        int shown = PAGE;
        List<Node> children;

        int count() {
            if (kind == KIND_LIST) {
                return ((List<?>) source).size();
            } else if (kind == KIND_MAP) {
                return ((Map<?, ?>) source).size();
            } else if (kind == KIND_OBJECT) {
                return children().size();
            }
            return 0;
        }

        boolean expandable() {
            return (kind == KIND_OBJECT || kind == KIND_LIST || kind == KIND_MAP) && count() > 0;
        }

        List<Node> children() {
            if (children != null) {
                return children;
            }
            ArrayList<Node> out = new ArrayList<>();
            try {
                if (kind == KIND_LIST) {
                    List<?> list = (List<?>) source;
                    for (int i = 0; i < list.size(); i++) {
                        out.add(make("[" + i + "]", true, list.get(i), this));
                    }
                } else if (kind == KIND_MAP) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) source).entrySet()) {
                        out.add(make(String.valueOf(e.getKey()), false, e.getValue(), this));
                    }
                } else if (kind == KIND_OBJECT) {
                    for (Field field : TLDumper.fieldsOf(source.getClass())) {
                        Object value;
                        try {
                            value = field.get(source);
                        } catch (Throwable e) {
                            continue;
                        }
                        if (value == null) {
                            continue; // same as TLDumper: base classes carry the union of all constructors' fields
                        }
                        out.add(make(field.getName(), false, value, this));
                    }
                }
            } catch (Throwable e) {
                Node error = new Node();
                error.key = "error";
                error.parent = this;
                error.depth = depth + 1;
                error.kind = KIND_VALUE;
                error.marker = String.valueOf(e);
                out.add(error);
            }
            children = out.isEmpty() ? Collections.emptyList() : out;
            return children;
        }
    }

    static Node make(String key, boolean indexKey, Object value, Node parent) {
        Node n = new Node();
        n.key = key;
        n.indexKey = indexKey;
        n.parent = parent;
        n.depth = parent == null ? 0 : parent.depth + 1;
        n.source = value;
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Enum || value instanceof CharSequence || value instanceof Character) {
            n.kind = KIND_VALUE;
        } else if (value instanceof byte[]) {
            n.kind = KIND_BYTES;
        } else if (n.depth > MAX_DEPTH || onPath(parent, value)) {
            n.kind = KIND_VALUE;
            n.marker = "<" + TLDumper.typeName(value) + " …>";
        } else if (value instanceof List) {
            n.kind = KIND_LIST;
        } else if (value instanceof Map) {
            n.kind = KIND_MAP;
        } else if (value instanceof TLObject || value.getClass().getName().startsWith("org.telegram.tgnet")) {
            n.kind = KIND_OBJECT;
        } else {
            n.kind = KIND_VALUE;
            n.marker = value.toString();
        }
        return n;
    }

    private static boolean onPath(Node parent, Object value) {
        for (Node a = parent; a != null; a = a.parent) {
            if (a.source == value) {
                return true;
            }
        }
        return false;
    }

    /** TL_botInfo -> botInfo, TLRPC$TL_messageMediaPhoto -> messageMediaPhoto. */
    static String shortType(Object object) {
        if (object == null) {
            return "null";
        }
        String s = object.getClass().getSimpleName();
        if (TextUtils.isEmpty(s)) {
            s = object.getClass().getName();
            int dot = Math.max(s.lastIndexOf('.'), s.lastIndexOf('$'));
            s = s.substring(dot + 1);
        }
        return s.startsWith("TL_") && s.length() > 3 ? s.substring(3) : s;
    }

    private static final class Row {
        final Node node;
        /** "ещё N" row of {@link #node}'s children. */
        final boolean more;
        final int depth;

        Row(Node node, boolean more) {
            this.node = node;
            this.more = more;
            this.depth = more ? node.depth + 1 : node.depth;
        }
    }

    private final int currentAccount;
    private final ArrayList<Row> rows = new ArrayList<>();
    private final TreeAdapter adapter;
    private Node root;
    private RawSyntax.Palette palette;
    private CopyHandler copyHandler;
    private MediaHandler mediaHandler;
    private int maxHeight;
    private long lastScrollTime;

    public RawTreeView(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
        this.currentAccount = currentAccount;
        setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        setItemAnimator(RawMotion.active() ? RawMotion.listAnimator() : null);
        setVerticalScrollBarEnabled(true);
        setClipToPadding(false);
        setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(8));
        setSelectorDrawableColor(Theme.getColor(Theme.key_listSelector, resourcesProvider));
        setAdapter(adapter = new TreeAdapter());
        setOnItemClickListener((view, position) -> onTap(view, position));
        setOnItemLongClickListener((view, position) -> onLongPress(view, position));
        addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy != 0) {
                    lastScrollTime = android.os.SystemClock.uptimeMillis();
                }
            }
        });
    }

    public void setCopyHandler(CopyHandler handler) {
        copyHandler = handler;
    }

    public void setMediaHandler(MediaHandler handler) {
        mediaHandler = handler;
    }

    public void setMaxHeight(int maxHeight) {
        this.maxHeight = maxHeight;
    }

    /** Uptime of the last scroll step; together with {@link #getScrollState()} tells a settled list from a fling. */
    public long getLastScrollTime() {
        return lastScrollTime;
    }

    // the list wraps its content: on expand/collapse its height would jump in one frame (the sheet with it),
    // so the height glides from the old value to the new one while the rows animate
    private int heightFrom = -1;
    private int animatedHeight = -1;
    private android.animation.ValueAnimator heightAnimator;

    private void prepareHeightChange() {
        if (!RawMotion.active() || getHeight() <= 0) {
            return;
        }
        heightFrom = animatedHeight >= 0 ? animatedHeight : getHeight();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (maxHeight > 0) {
            heightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthSpec, heightSpec);
        int target = getMeasuredHeight();
        if (heightFrom >= 0 && heightFrom != target) {
            final int from = heightFrom;
            heightFrom = -1;
            if (heightAnimator != null) {
                heightAnimator.cancel();
            }
            animatedHeight = from;
            heightAnimator = android.animation.ValueAnimator.ofInt(from, target);
            heightAnimator.setDuration(300);
            heightAnimator.setInterpolator(RawMotion.EMPHASIZED);
            heightAnimator.addUpdateListener(a -> {
                animatedHeight = (int) a.getAnimatedValue();
                requestLayout();
            });
            heightAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (heightAnimator == animation) {
                        heightAnimator = null;
                        animatedHeight = -1;
                        requestLayout();
                    }
                }
            });
            heightAnimator.start();
        } else {
            heightFrom = -1;
        }
        if (animatedHeight >= 0) {
            setMeasuredDimension(getMeasuredWidth(), animatedHeight);
        }
    }

    public void setObject(Object object) {
        palette = RawSyntax.Palette.of(Theme.isCurrentThemeDark());
        root = make(null, false, object, null);
        root.expanded = true;
        if (root.expandable()) {
            List<Node> first = root.children();
            int n = first.size() <= ROOT_EXPAND_LIMIT ? first.size() : 1;
            for (int i = 0; i < n; i++) {
                first.get(i).expanded = true;
            }
        }
        rows.clear();
        appendNode(root, rows);
        adapter.notifyDataSetChanged();
        scrollToPosition(0);
    }

    // ---- flattening ----

    private void appendNode(Node node, ArrayList<Row> out) {
        out.add(new Row(node, false));
        if (node.expanded && node.expandable()) {
            appendChildren(node, 0, out);
        }
    }

    private void appendChildren(Node node, int from, ArrayList<Row> out) {
        List<Node> children = node.children();
        int limit = Math.min(children.size(), node.shown);
        for (int i = from; i < limit; i++) {
            appendNode(children.get(i), out);
        }
        if (limit < children.size()) {
            out.add(new Row(node, true));
        }
    }

    // ---- interaction ----

    private void onTap(View view, int position) {
        if (position < 0 || position >= rows.size()) {
            return;
        }
        Row row = rows.get(position);
        Node node = row.node;
        if (row.more) {
            prepareHeightChange();
            int from = node.shown;
            node.shown += MORE_STEP;
            ArrayList<Row> added = new ArrayList<>();
            appendChildren(node, from, added);
            rows.remove(position);
            adapter.notifyItemRemoved(position);
            rows.addAll(position, added);
            adapter.notifyItemRangeInserted(position, added.size());
            return;
        }
        if (node.expandable()) {
            prepareHeightChange();
            node.expanded = !node.expanded;
            if (view instanceof RowView) {
                ((RowView) view).setExpanded(node.expanded, true);
            }
            if (node.expanded) {
                ArrayList<Row> added = new ArrayList<>();
                appendChildren(node, 0, added);
                rows.addAll(position + 1, added);
                adapter.notifyItemRangeInserted(position + 1, added.size());
            } else {
                int end = position + 1;
                while (end < rows.size() && rows.get(end).depth > node.depth) {
                    end++;
                }
                int count = end - position - 1;
                if (count > 0) {
                    rows.subList(position + 1, end).clear();
                    adapter.notifyItemRangeRemoved(position + 1, count);
                }
            }
            return;
        }
        if (node.kind == KIND_BYTES) {
            node.valueMode = (node.valueMode + 1) % 3;
            adapter.notifyItemChanged(position);
        } else if (node.kind == KIND_VALUE && (node.valueMode != 0 || isLongValue(node)
                || view instanceof RowView && ((RowView) view).isTruncated())) {
            node.valueMode = node.valueMode == 0 ? 1 : 0;
            adapter.notifyItemChanged(position);
        }
    }

    private boolean onLongPress(View view, int position) {
        if (position < 0 || position >= rows.size() || copyHandler == null) {
            return false;
        }
        Row row = rows.get(position);
        if (row.more) {
            return false;
        }
        Node node = row.node;
        if (mediaHandler != null && node.kind == KIND_OBJECT && node.marker == null) {
            ArrayList<Object> ancestors = new ArrayList<>();
            for (Node a = node.parent; a != null; a = a.parent) {
                ancestors.add(a.source);
            }
            boolean handled;
            try {
                handled = mediaHandler.onLongPress(view, node.source, ancestors, () -> copyNode(view, node));
            } catch (Throwable e) {
                handled = false;
            }
            if (handled) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                return true;
            }
        }
        return copyNode(view, node);
    }

    /** The usual long press: copies the subtree's JSON, the bytes or the value. */
    private boolean copyNode(View view, Node node) {
        String text;
        String toast;
        try {
            if (node.kind == KIND_OBJECT || node.kind == KIND_LIST || node.kind == KIND_MAP) {
                text = TLDumper.toJson(node.source);
                toast = "JSON узла скопирован";
            } else if (node.kind == KIND_BYTES) {
                byte[] bytes = (byte[]) node.source;
                if (node.valueMode == 1) {
                    text = hex(bytes, bytes.length, false);
                    toast = "Байты скопированы (hex)";
                } else {
                    text = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    toast = "Байты скопированы (base64)";
                }
            } else if (colorHex(node) != null) {
                text = colorHex(node);
                toast = "Цвет скопирован: " + text;
            } else {
                text = node.marker != null ? node.marker : String.valueOf(node.source);
                toast = "Значение скопировано";
            }
        } catch (Throwable e) {
            return false;
        }
        if (RawMotion.active()) {
            RawMotion.copied(view); // check badge + haptic
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        copyHandler.onCopy(text, toast);
        return true;
    }

    private static boolean isLongValue(Node node) {
        if (node.marker != null) {
            return node.marker.length() > LONG_STRING;
        }
        if (node.source instanceof CharSequence) {
            CharSequence s = (CharSequence) node.source;
            return s.length() > LONG_STRING || TextUtils.indexOf(s, '\n') >= 0;
        }
        return false;
    }

    // ---- rendering ----

    private CharSequence render(Row row) {
        RawSyntax.Palette p = palette;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        Node node = row.node;
        if (row.more) {
            int rest = node.children().size() - Math.min(node.children().size(), node.shown);
            append(sb, "ещё " + rest, Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
            return sb;
        }
        boolean hasKey = node.key != null;
        if (hasKey) {
            append(sb, node.key, node.indexKey ? p.punctuation : p.key);
        }
        switch (node.kind) {
            case KIND_OBJECT: {
                if (hasKey) {
                    sb.append("  ");
                }
                append(sb, shortType(node.source), p.type);
                String constructor = TLDumper.constructorOf(node.source.getClass());
                if (constructor != null && constructor.startsWith("0x")) {
                    int start = sb.length();
                    append(sb, "  #" + constructor.substring(2), Theme.multAlpha(p.punctuation, 0.8f));
                    sb.setSpan(new RelativeSizeSpan(0.85f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                break;
            }
            case KIND_LIST:
            case KIND_MAP: {
                int count = node.count();
                boolean list = node.kind == KIND_LIST;
                if (count == 0) {
                    if (hasKey) {
                        append(sb, " = ", p.punctuation);
                    }
                    append(sb, list ? "[]" : "{}", p.keyword);
                } else if (!hasKey) {
                    append(sb, (list ? "[" : "{") + count + (count == 1 ? " item" : " items") + (list ? "]" : "}"), p.punctuation);
                } else {
                    append(sb, " " + (list ? "[" : "{") + count + (list ? "]" : "}"), p.punctuation);
                }
                break;
            }
            case KIND_BYTES: {
                if (hasKey) {
                    append(sb, " = ", p.punctuation);
                }
                byte[] bytes = (byte[]) node.source;
                append(sb, "bytes[" + bytes.length + "]", p.type);
                if (bytes.length == 0) {
                    break;
                }
                if (node.valueMode == 0) {
                    append(sb, " " + hex(bytes, PREVIEW_BYTES, false) + (bytes.length > PREVIEW_BYTES ? " …" : ""), p.number);
                } else if (node.valueMode == 1) {
                    append(sb, "  hex\n", p.punctuation);
                    append(sb, hex(bytes, MAX_HEX_BYTES, true) + (bytes.length > MAX_HEX_BYTES ? "\n…" : ""), p.number);
                } else {
                    append(sb, "  base64\n", p.punctuation);
                    append(sb, Base64.encodeToString(bytes, Base64.NO_WRAP), p.string);
                }
                break;
            }
            default: {
                if (hasKey) {
                    append(sb, " = ", p.punctuation);
                }
                appendValue(sb, node, p);
                appendPeer(sb, node, p);
                appendColor(sb, node, p);
                break;
            }
        }
        return sb;
    }

    private void appendValue(SpannableStringBuilder sb, Node node, RawSyntax.Palette p) {
        boolean full = node.valueMode == 1;
        if (node.marker != null) {
            String s = node.marker;
            if (!full && s.length() > COLLAPSED_STRING) {
                s = s.substring(0, COLLAPSED_STRING);
            }
            append(sb, s, p.punctuation);
            return;
        }
        Object v = node.source;
        if (v == null) {
            append(sb, "null", Theme.multAlpha(p.keyword, 0.55f));
        } else if (v instanceof Boolean) {
            append(sb, v.toString(), p.keyword);
        } else if (v instanceof Number) {
            append(sb, v.toString(), p.number);
        } else if (v instanceof Enum) {
            append(sb, v.toString(), p.keyword);
        } else {
            String s = v.toString();
            if (full) {
                if (s.length() > MAX_STRING_SHOWN) {
                    s = s.substring(0, MAX_STRING_SHOWN) + "…";
                }
                append(sb, "\"" + s + "\"", p.string);
            } else {
                if (s.length() > COLLAPSED_STRING) {
                    s = s.substring(0, COLLAPSED_STRING);
                }
                append(sb, "\"" + escape(s) + "\"", p.string);
            }
        }
    }

    /** "user_id = 123  ← Name" when the peer is cached. */
    private void appendPeer(SpannableStringBuilder sb, Node node, RawSyntax.Palette p) {
        if (!(node.source instanceof Long || node.source instanceof Integer) || node.key == null) {
            return;
        }
        long id = ((Number) node.source).longValue();
        if (id == 0) {
            return;
        }
        try {
            RawPeers.Info info;
            switch (node.key) {
                case "user_id":
                case "bot_id":
                case "admin_id":
                case "inviter_id":
                    info = RawPeers.user(currentAccount, id);
                    break;
                case "chat_id":
                case "channel_id":
                    info = RawPeers.resolve(currentAccount, -Math.abs(id));
                    break;
                default:
                    return;
            }
            if (info != null && info.isCached && !TextUtils.isEmpty(info.name)) {
                append(sb, "  ← " + info.name, Theme.multAlpha(p.punctuation, 0.9f));
            }
        } catch (Throwable ignore) {
        }
    }

    /** "center_color = 16777215  ■ #FFFFFF" for RGB ints; the square is drawn in the color itself. */
    private static void appendColor(SpannableStringBuilder sb, Node node, RawSyntax.Palette p) {
        String hex = colorHex(node);
        if (hex == null) {
            return;
        }
        append(sb, "  ■", 0xFF000000 | Integer.parseInt(hex.substring(1), 16));
        if (node.source instanceof Integer) {
            append(sb, " " + hex, Theme.multAlpha(p.punctuation, 0.9f));
        }
    }

    /** "#RRGGBB" for an int (or an already formatted "#RRGGBB" string) under a *_color key, null otherwise. */
    private static String colorHex(Node node) {
        if (node.kind != KIND_VALUE || node.marker != null || !TLDumper.isColorKey(node.key)) {
            return null;
        }
        if (node.source instanceof Integer) {
            return TLDumper.colorHex((Integer) node.source);
        }
        if (node.source instanceof String && ((String) node.source).matches("#[0-9A-Fa-f]{6}")) {
            return (String) node.source;
        }
        return null;
    }

    private static void append(SpannableStringBuilder sb, CharSequence text, int color) {
        int start = sb.length();
        sb.append(text);
        if (sb.length() > start) {
            sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: out.append(c < 0x20 ? '·' : c);
            }
        }
        return out.toString();
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String hex(byte[] bytes, int max, boolean lines) {
        int n = Math.min(bytes.length, max);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(lines && i % 16 == 0 ? '\n' : ' ');
            }
            int b = bytes[i] & 0xff;
            sb.append(HEX[b >> 4]).append(HEX[b & 0xf]);
        }
        return sb.toString();
    }

    private class TreeAdapter extends SelectionAdapter {

        @Override
        public boolean isEnabled(ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            RowView view = new RowView(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Row row = rows.get(position);
            Node node = row.node;
            boolean expandable = !row.more && node.expandable();
            boolean wrap = !row.more && node.valueMode != 0 && (node.kind == KIND_VALUE || node.kind == KIND_BYTES);
            ((RowView) holder.itemView).bind(row.depth, expandable, expandable && node.expanded, render(row), wrap);
        }
    }

    /** A row: indentation guides, a rotating chevron for containers and the highlighted text. */
    private class RowView extends FrameLayout {

        private final TextView textView;
        private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrowPath = new Path();
        private final int pad = AndroidUtilities.dp(6);
        private final int step = AndroidUtilities.dp(14);
        private final int arrowWidth = AndroidUtilities.dp(14);
        private int depth;
        private boolean hasArrow;
        private float progress;
        private ValueAnimator animator;

        RowView(Context context) {
            super(context);
            setWillNotDraw(false);
            int text = Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider);
            guidePaint.setColor(Theme.multAlpha(text, 0.12f));
            guidePaint.setStrokeWidth(Math.max(1, AndroidUtilities.dp(1)));
            arrowPaint.setColor(Theme.multAlpha(text, 0.55f));
            arrowPaint.setStyle(Paint.Style.STROKE);
            arrowPaint.setStrokeWidth(AndroidUtilities.dp(1.6f));
            arrowPaint.setStrokeCap(Paint.Cap.ROUND);
            arrowPaint.setStrokeJoin(Paint.Join.ROUND);
            float h = AndroidUtilities.dp(3.5f);
            arrowPath.moveTo(-h * 0.6f, -h);
            arrowPath.lineTo(h * 0.6f, 0);
            arrowPath.lineTo(-h * 0.6f, h);

            textView = new TextView(context);
            textView.setTypeface(Typeface.MONOSPACE);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            textView.setTextColor(text);
            addView(textView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind(int depth, boolean hasArrow, boolean expanded, CharSequence text, boolean wrap) {
            this.depth = depth;
            this.hasArrow = hasArrow;
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            progress = expanded ? 1f : 0f;
            int left = pad + depth * step + arrowWidth + AndroidUtilities.dp(2);
            textView.setPadding(left, AndroidUtilities.dp(3), AndroidUtilities.dp(10), AndroidUtilities.dp(3));
            if (wrap) {
                textView.setSingleLine(false);
                textView.setMaxLines(Integer.MAX_VALUE);
                textView.setEllipsize(null);
            } else {
                textView.setSingleLine(true);
                textView.setEllipsize(TextUtils.TruncateAt.END);
            }
            textView.setText(text);
            invalidate();
        }

        boolean isTruncated() {
            android.text.Layout layout = textView.getLayout();
            return layout != null && layout.getLineCount() > 0 && layout.getEllipsisCount(0) > 0;
        }

        void setExpanded(boolean expanded, boolean animated) {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            float target = expanded ? 1f : 0f;
            if (!animated || !RawMotion.active()) {
                progress = target;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(progress, target);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(240);
            animator.setInterpolator(RawMotion.EMPHASIZED);
            animator.start();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            for (int i = 0; i < depth; i++) {
                float x = pad + i * step + arrowWidth / 2f;
                canvas.drawLine(x, 0, x, getHeight(), guidePaint);
            }
            super.dispatchDraw(canvas);
            if (hasArrow) {
                float cx = pad + depth * step + arrowWidth / 2f;
                float cy = textView.getTop() + textView.getPaddingTop() + textView.getLineHeight() / 2f;
                canvas.save();
                canvas.translate(cx, cy);
                canvas.rotate(90f * progress);
                canvas.drawPath(arrowPath, arrowPaint);
                canvas.restore();
            }
        }
    }
}
