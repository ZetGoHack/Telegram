package org.telegram.rawgram;

import android.text.NoCopySpan;
import android.text.SpanWatcher;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * Upstream leaks found in heap dumps (RawMemWatch), each keeping closed chats with their emoji panels (~50 MB a chat):
 * <ul>
 * <li>ViewTreeObserver listeners added through a view that is later shown in the activity window and never removed:
 * the window's observer outlives the view (ActionBarPopupWindowLayout's scroll listener, from every chat preview menu;
 * RichEditor's focus listener). {@link #onScrollChanged} / {@link #onGlobalFocusChange} keep them only while the view is
 * attached.</li>
 * <li>PhotoViewer (a singleton) kept the caption field's live Editable as «caption for all media», and the Editable
 * holds its TextView through the change watchers. {@link #detachedText} copies the text without them.</li>
 * </ul>
 */
public final class RawLeakFixes {

    private RawLeakFixes() {
    }

    public static void onScrollChanged(View view, ViewTreeObserver.OnScrollChangedListener listener) {
        whileAttached(view, vto -> vto.addOnScrollChangedListener(listener), vto -> vto.removeOnScrollChangedListener(listener));
    }

    public static void onGlobalFocusChange(View view, ViewTreeObserver.OnGlobalFocusChangeListener listener) {
        whileAttached(view, vto -> vto.addOnGlobalFocusChangeListener(listener), vto -> vto.removeOnGlobalFocusChangeListener(listener));
    }

    private interface VtoAction {
        void run(ViewTreeObserver vto);
    }

    private static void whileAttached(View view, VtoAction add, VtoAction remove) {
        if (view.isAttachedToWindow()) {
            add.run(view.getViewTreeObserver());
        }
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                add.run(v.getViewTreeObserver());
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                ViewTreeObserver vto = v.getViewTreeObserver();
                if (vto.isAlive()) {
                    remove.run(vto);
                }
            }
        });
    }

    /** A copy of the text with its spans but without the watchers that tie an Editable to its TextView. */
    public static CharSequence detachedText(CharSequence text) {
        if (!(text instanceof Spanned) || text instanceof String) {
            return text;
        }
        SpannableStringBuilder copy = new SpannableStringBuilder(text);
        for (Object span : copy.getSpans(0, copy.length(), Object.class)) {
            if (span instanceof TextWatcher || span instanceof SpanWatcher || span instanceof NoCopySpan) {
                copy.removeSpan(span);
            }
        }
        return copy;
    }
}
