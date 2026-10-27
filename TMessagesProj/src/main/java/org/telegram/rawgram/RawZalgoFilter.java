package org.telegram.rawgram;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;

/**
 * Replaces runs of stacked combining marks («Zalgo») and bidi-control characters in displayed text with
 * U+2060 WORD JOINER, one per removed char, so the text length and the span offsets stay the same.
 * Ported from exteraGram's ZalgoFilter. Called from {@code Emoji.replaceEmoji}; allocates only when something is found.
 */
public final class RawZalgoFilter {

    private static final int DEFAULT_MARKS = 4;
    private static final int RANGE_MARKS = 2;
    private static final char WORD_JOINER = '⁠';

    private RawZalgoFilter() {
    }

    private static boolean isDirectionControl(int c) {
        return c == 0x061C || c == 0x200E || c == 0x200F || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069);
    }

    private static boolean isStackingRange(int c) {
        return (c >= 0x0300 && c <= 0x036F) || (c >= 0x1AB0 && c <= 0x1AFF) || (c >= 0x1DC0 && c <= 0x1DFF)
                || (c >= 0x20D0 && c <= 0x20FF) || (c >= 0xFE20 && c <= 0xFE2F);
    }

    private static int allowedMarks(int c) {
        if (c < 0x0300) {
            return 0;
        }
        int type = Character.getType(c);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) {
            return isStackingRange(c) ? RANGE_MARKS : DEFAULT_MARKS;
        }
        return 0;
    }

    /** Returns the text with the Zalgo removed (keeping spans), or the same object when there is none or the filter is off. */
    public static CharSequence filter(CharSequence cs) {
        if (cs == null || cs.length() == 0 || !RawUiConfig.filterZalgo()) {
            return cs;
        }
        int[] ranges = findRanges(cs);
        if (ranges == null) {
            return cs;
        }
        String replaced = apply(cs.toString(), ranges);
        if (!(cs instanceof Spannable)) {
            return replaced;
        }
        Spannable src = (Spannable) cs;
        SpannableString out = new SpannableString(replaced);
        int length = out.length();
        for (Object span : src.getSpans(0, src.length(), Object.class)) {
            int start = Math.max(0, Math.min(src.getSpanStart(span), length));
            int end = Math.max(start, Math.min(src.getSpanEnd(span), length));
            out.setSpan(span, start, end, src.getSpanFlags(span));
        }
        return out;
    }

    public static String filter(String s) {
        if (TextUtils.isEmpty(s) || !RawUiConfig.filterZalgo()) {
            return s;
        }
        int[] ranges = findRanges(s);
        return ranges == null ? s : apply(s, ranges);
    }

    private static String apply(String s, int[] ranges) {
        StringBuilder sb = new StringBuilder(s.length());
        int pos = 0;
        for (int i = 0; i + 1 < ranges.length && ranges[i + 1] != 0; i += 2) {
            sb.append(s, pos, ranges[i]);
            for (int k = ranges[i]; k < ranges[i + 1]; k++) {
                sb.append(WORD_JOINER);
            }
            pos = ranges[i + 1];
        }
        sb.append(s, pos, s.length());
        return sb.toString();
    }

    /** Pairs of [start, end) char offsets, zero-terminated; null when nothing to replace. */
    private static int[] findRanges(CharSequence cs) {
        int length = cs.length();
        int[] out = null;
        int count = 0;
        int seqStart = -1;
        int marks = 0;
        int limit = DEFAULT_MARKS;
        int i = 0;
        while (i < length) {
            int cp = Character.codePointAt(cs, i);
            int n = Character.charCount(cp);
            int allowed = allowedMarks(cp);
            if (allowed > 0) {
                if (seqStart < 0) {
                    marks = 0;
                    seqStart = i;
                } else {
                    allowed = Math.min(limit, allowed);
                }
                marks++;
                limit = allowed;
            } else {
                if (seqStart >= 0 && marks > limit) {
                    out = add(out, count, seqStart, i);
                    count += 2;
                }
                if (isDirectionControl(cp)) {
                    out = add(out, count, i, i + n);
                    count += 2;
                }
                seqStart = -1;
                marks = 0;
                limit = DEFAULT_MARKS;
            }
            i += n;
        }
        if (seqStart >= 0 && marks > limit) {
            out = add(out, count, seqStart, length);
        }
        return out;
    }

    private static int[] add(int[] arr, int count, int start, int end) {
        if (arr == null) {
            arr = new int[8];
        } else if (count + 3 >= arr.length) {
            arr = java.util.Arrays.copyOf(arr, arr.length * 2);
        }
        arr[count] = start;
        arr[count + 1] = end;
        arr[count + 2] = 0;
        arr[count + 3] = 0;
        return arr;
    }
}
