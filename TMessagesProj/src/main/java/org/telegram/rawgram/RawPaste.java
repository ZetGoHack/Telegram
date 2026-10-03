package org.telegram.rawgram;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.CodeHighlighting;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.TextStyleSpan;
import org.telegram.ui.Components.URLSpanMono;

/** Paste fixes for the message input: NBSP inside pasted code, and rich paste for IME (Gboard) clipboard commits. */
public class RawPaste {

    private static final char NBSP = ' ';

    /**
     * HTML on the clipboard (Telegram's own CustomHtml writes runs of spaces as "&amp;nbsp;&amp;nbsp; ", other apps do the
     * same) decodes to U+00A0. Inside code that breaks the pasted code, so turn it back into plain spaces — only within
     * code/pre spans of the pasted text, or everywhere when the paste lands inside an existing code span of {@code target}.
     * Returns {@code pasted} itself when nothing changed, otherwise an equal-length copy with the same spans.
     */
    public static SpannableStringBuilder fixCodeNbsp(SpannableStringBuilder pasted, Spanned target, int selStart, int selEnd) {
        if (pasted == null || TextUtils.indexOf(pasted, NBSP) < 0) {
            return pasted;
        }
        try {
            final int len = pasted.length();
            final char[] chars = new char[len];
            TextUtils.getChars(pasted, 0, len, chars, 0);
            boolean changed = false;
            if (target != null && isInsideCode(target, selStart, selEnd)) {
                for (int i = 0; i < len; i++) {
                    if (chars[i] == NBSP) {
                        chars[i] = ' ';
                        changed = true;
                    }
                }
            } else {
                final Object[] spans = pasted.getSpans(0, len, Object.class);
                for (Object span : spans) {
                    if (!isCodeSpan(span)) continue;
                    final int s = Math.max(0, pasted.getSpanStart(span));
                    final int e = Math.min(len, pasted.getSpanEnd(span));
                    for (int i = s; i < e; i++) {
                        if (chars[i] == NBSP) {
                            chars[i] = ' ';
                            changed = true;
                        }
                    }
                }
            }
            if (!changed) {
                return pasted;
            }
            final SpannableStringBuilder out = new SpannableStringBuilder(new String(chars));
            final Object[] spans = pasted.getSpans(0, len, Object.class);
            for (Object span : spans) {
                try {
                    out.setSpan(span, pasted.getSpanStart(span), pasted.getSpanEnd(span), pasted.getSpanFlags(span));
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            return out;
        } catch (Exception e) {
            FileLog.e(e);
            return pasted;
        }
    }

    private static boolean isCodeSpan(Object span) {
        if (span instanceof CodeHighlighting.Span || span instanceof URLSpanMono) {
            return true;
        }
        if (span instanceof TextStyleSpan) {
            final TextStyleSpan s = (TextStyleSpan) span;
            return s.isMono() || (s.getStyleFlags() & TextStyleSpan.FLAG_STYLE_MONO) != 0;
        }
        return false;
    }

    private static boolean isInsideCode(Spanned target, int a, int b) {
        final int len = target.length();
        final int st = Math.max(0, Math.min(Math.min(a, b), len));
        final int en = Math.max(0, Math.min(Math.max(a, b), len));
        final Object[] spans = target.getSpans(st, en, Object.class);
        for (Object span : spans) {
            if (!isCodeSpan(span)) continue;
            final int s = target.getSpanStart(span);
            final int e = target.getSpanEnd(span);
            // the paste must land strictly inside the span: at its edges inserted text doesn't join it
            if (st == en ? (s < st && st < e) : (s <= st && en <= e)) {
                return true;
            }
        }
        return false;
    }

    // ---- Gboard clipboard strip -------------------------------------------------------------------------------

    private static long cachedClipStamp = Long.MIN_VALUE;
    private static String cachedClipPlain;

    /**
     * Gboard's clipboard chip/strip inserts text with {@code InputConnection.commitText} (plain text only), which never
     * reaches {@code onTextContextMenuItem(android.R.id.paste)} where the HTML → spans paste lives. If a non-composing
     * commit equals exactly the primary clip's plain text and that clip carries HTML, run the regular rich paste instead.
     * {@code commitContent} (images/GIFs) is passed through untouched.
     */
    public static InputConnection wrapClipboardCommit(InputConnection ic, EditText view) {
        if (ic == null || view == null) {
            return ic;
        }
        return new InputConnectionWrapper(ic, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (isRichClipboardCommit(view, text)) {
                    // post: don't setText() in the middle of the IME's batch edit
                    AndroidUtilities.runOnUIThread(() -> {
                        try {
                            view.onTextContextMenuItem(android.R.id.paste);
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    });
                    return true;
                }
                return super.commitText(text, newCursorPosition);
            }
        };
    }

    private static boolean isRichClipboardCommit(EditText view, CharSequence text) {
        try {
            if (text == null || text.length() < 2) {
                return false;
            }
            final Editable editable = view.getText();
            if (editable == null || BaseInputConnection.getComposingSpanStart(editable) != -1) {
                return false; // typing / autocorrect replacing a composing word
            }
            final ClipboardManager cm = (ClipboardManager) view.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return false;
            }
            // description only: doesn't trigger the Android 12+ "pasted from clipboard" toast
            final ClipDescription description = cm.getPrimaryClipDescription();
            if (description == null || !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) {
                return false;
            }
            String plain;
            final long stamp = Build.VERSION.SDK_INT >= 26 ? description.getTimestamp() : Long.MIN_VALUE;
            if (Build.VERSION.SDK_INT >= 26 && stamp == cachedClipStamp) {
                plain = cachedClipPlain;
            } else {
                plain = null;
                final ClipData clip = cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() == 1) {
                    final ClipData.Item item = clip.getItemAt(0);
                    final CharSequence t = item.getText();
                    if (t != null && !TextUtils.isEmpty(item.getHtmlText())) {
                        plain = t.toString();
                    }
                }
                cachedClipStamp = stamp;
                cachedClipPlain = plain;
            }
            return plain != null && plain.length() == text.length() && plain.contentEquals(text);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }
}
