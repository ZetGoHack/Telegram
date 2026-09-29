package org.telegram.rawgram;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

/** Minimal syntax colouring for the raw code block: JSON and the flat "path = value" list. */
public class RawSyntax {

    private static final int MAX_HIGHLIGHT = 120_000;

    public static class Palette {
        public int key, string, number, keyword, type, punctuation;

        public static Palette of(boolean dark) {
            Palette p = new Palette();
            if (dark) {
                p.key = 0xFF8AB4F8;
                p.string = 0xFFA5D6A7;
                p.number = 0xFFFFCC80;
                p.keyword = 0xFFCE93D8;
                p.type = 0xFF80CBC4;
                p.punctuation = 0xFF9AA0A6;
            } else {
                p.key = 0xFF1A5FB4;
                p.string = 0xFF2E7D32;
                p.number = 0xFFB35C00;
                p.keyword = 0xFF7B1FA2;
                p.type = 0xFF00796B;
                p.punctuation = 0xFF80868B;
            }
            return p;
        }
    }

    public static CharSequence json(String text, Palette p) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        int n = Math.min(text.length(), MAX_HIGHLIGHT);
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = skipString(text, i, n);
                int after = end;
                while (after < n && text.charAt(after) == ' ') {
                    after++;
                }
                boolean isKey = after < n && text.charAt(after) == ':';
                boolean isType = !isKey && i >= 5 && text.startsWith("\"_\": ", i - 5);
                color(sb, i, end, isKey ? p.key : isType ? p.type : p.string);
                i = end;
            } else if (c == '-' || Character.isDigit(c)) {
                int end = i + 1;
                while (end < n && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) {
                    end++;
                }
                color(sb, i, end, p.number);
                i = end;
            } else if (text.startsWith("true", i) || text.startsWith("null", i)) {
                color(sb, i, i + 4, p.keyword);
                i += 4;
            } else if (text.startsWith("false", i)) {
                color(sb, i, i + 5, p.keyword);
                i += 5;
            } else {
                if ("{}[],:".indexOf(c) >= 0) {
                    color(sb, i, i + 1, p.punctuation);
                }
                i++;
            }
        }
        return sb;
    }

    /** Flat list lines: "a.b[0].c = value" or "a.b : TypeName". */
    public static CharSequence fields(String text, Palette p) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        int n = Math.min(text.length(), MAX_HIGHLIGHT);
        int lineStart = 0;
        while (lineStart < n) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0 || lineEnd > n) {
                lineEnd = n;
            }
            int eq = text.indexOf(" = ", lineStart);
            int typeSep = text.indexOf(" : ", lineStart);
            if (eq >= 0 && eq < lineEnd) {
                color(sb, lineStart, eq, p.key);
                color(sb, eq + 1, eq + 2, p.punctuation);
                int v = eq + 3;
                if (v < lineEnd) {
                    char c = text.charAt(v);
                    int valueColor = c == '"' ? p.string
                            : (c == '-' || Character.isDigit(c)) ? p.number
                            : (text.startsWith("true", v) || text.startsWith("false", v) || text.startsWith("null", v) || text.startsWith("[]", v)) ? p.keyword
                            : p.string;
                    color(sb, v, lineEnd, valueColor);
                }
            } else if (typeSep >= 0 && typeSep < lineEnd) {
                color(sb, lineStart, typeSep, p.key);
                color(sb, typeSep + 1, typeSep + 2, p.punctuation);
                color(sb, typeSep + 3, lineEnd, p.type);
            } else if (lineEnd > lineStart) {
                color(sb, lineStart, lineEnd, p.type);
            }
            lineStart = lineEnd + 1;
        }
        return sb;
    }

    private static int skipString(String text, int start, int n) {
        int i = start + 1;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static void color(SpannableStringBuilder sb, int start, int end, int color) {
        if (end > start) {
            sb.setSpan(new ForegroundColorSpan(color), start, Math.min(end, sb.length()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
