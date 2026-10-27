package org.telegram.rawgram.calc;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Editable;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.TextView;

import org.telegram.messenger.FileLog;
import org.telegram.rawgram.RawCalcConfig;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.QuoteSpan;
import org.telegram.ui.Components.TextStyleSpan;

/**
 * The inline calculator of the chat input: after "=" the answer shows as a grey "ghost" after the cursor,
 * Space (or Tab / the right arrow) inserts it and Backspace right after the insertion takes it back.
 * The text is never touched until the answer is inserted: the ghost is only drawn over the field.
 * It shows when the cursor is at the end of its line and the answer fits on that line.
 *
 * The behaviour (triggering, the input-connection wrapper, the undo window) follows exteraGram's InlineMathController
 * (GPLv3); the ghost is simplified (it never moves the text).
 */
public final class RawCalcController {

    public interface Delegate {
        /** Runs the text edit so the chat treats it as programmatic (no typing event, no link/emoji reprocessing). */
        void runProgrammatic(Runnable action);

        /** Whether the typed expression may leave the device (false in secret chats). */
        boolean allowRemote();
    }

    private static final float GHOST_ALPHA = 0.4f;

    private final TextView view;
    private final Delegate delegate;
    private final AnimatedFloat appear;
    private final TextPaint ghostPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final RawLocalCalc local;
    private RawCalcmula calcmula;

    /** The answer ready to insert and where; null when there is none. */
    private String suggestionValue;
    private String suggestionInsert;
    private int suggestionAt = -1;
    private int token;
    private int undoStart = -1;
    private int undoEnd = -1;
    private int suppressedAt = -1;
    private boolean insertingSelf;
    private int swallowKeyCode;
    private static boolean warmed;

    public RawCalcController(TextView view, Delegate delegate) {
        this.view = view;
        this.delegate = delegate;
        this.appear = new AnimatedFloat(view, 0L, 120L, CubicBezierInterpolator.EASE_OUT_QUINT);
        this.local = new RawLocalCalc(this::refresh);
    }

    // ---- events from the field ----

    public void onTextChanged() {
        if (!insertingSelf) {
            clearUndo();
            suppressedAt = -1;
        }
        refresh();
    }

    public void onSelectionChanged() {
        if (!insertingSelf) {
            int selection = view.getSelectionStart();
            if (selection != suppressedAt) {
                suppressedAt = -1;
            }
            if (selection != undoEnd || view.getSelectionEnd() != undoEnd) {
                clearUndo();
            }
        }
        refresh();
    }

    public void onFocusChanged(boolean focused) {
        if (!focused) {
            clearUndo();
            drop();
        }
    }

    /** Draws the ghost; call after the field has drawn its text. */
    public void draw(Canvas canvas) {
        if (suggestionInsert == null) {
            appear.set(0f, true);
            return;
        }
        Layout layout = view.getLayout();
        CharSequence text = view.getText();
        int selection = view.getSelectionStart();
        if (layout == null || text == null || selection != suggestionAt || selection != view.getSelectionEnd()
                || selection > text.length() || layout.getParagraphDirection(layout.getLineForOffset(selection)) != Layout.DIR_LEFT_TO_RIGHT) {
            return;
        }
        int line = layout.getLineForOffset(selection);
        ghostPaint.set(view.getPaint());
        ghostPaint.setColor(view.getCurrentTextColor());
        float width = ghostPaint.measureText(suggestionInsert);
        float x = layout.getPrimaryHorizontal(selection);
        if (x + width > layout.getWidth()) {
            // it does not fit on the line: nothing is shown, so nothing can be inserted either
            drop();
            return;
        }
        ghostPaint.setAlpha(Math.round(ghostPaint.getAlpha() * appear.set(1f) * GHOST_ALPHA));
        float top = view.getExtendedPaddingTop();
        int gravity = view.getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
        if (gravity != Gravity.TOP) {
            int box = view.getHeight() - view.getExtendedPaddingTop() - view.getExtendedPaddingBottom();
            int content = layout.getHeight();
            if (content < box) {
                top += gravity == Gravity.BOTTOM ? box - content : (box - content) / 2f;
            }
        }
        canvas.drawText(suggestionInsert, 0, suggestionInsert.length(),
                view.getCompoundPaddingLeft() + x, top + layout.getLineBaseline(line), ghostPaint);
    }

    /** Hardware keys and keys sent by the keyboard: Space / Tab / right arrow insert, Backspace takes back. */
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (swallowKeyCode != 0 && event.getKeyCode() == swallowKeyCode) {
                swallowKeyCode = 0;
                return true;
            }
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0
                || event.isCtrlPressed() || event.isAltPressed() || event.isShiftPressed()) {
            return false;
        }
        boolean handled = false;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_SPACE:
                handled = commit();
                break;
            case KeyEvent.KEYCODE_DEL:
                handled = undo();
                break;
        }
        if (handled) {
            swallowKeyCode = event.getKeyCode();
        }
        return handled;
    }

    /** Wraps the keyboard connection: a lone space inserts the answer, a one-char delete takes it back. */
    public InputConnection wrap(InputConnection connection) {
        return new InputConnectionWrapper(connection, true) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (suggestionInsert != null && text != null && text.length() == 1 && text.charAt(0) == ' ') {
                    finishComposingText();
                    if (commit()) {
                        return true;
                    }
                }
                return super.commitText(text, newCursorPosition);
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (beforeLength == 1 && afterLength == 0 && undo()) {
                    return true;
                }
                return super.deleteSurroundingText(beforeLength, afterLength);
            }

            @Override
            public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
                if (beforeLength == 1 && afterLength == 0 && undo()) {
                    return true;
                }
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
            }
        };
    }

    // ---- state ----

    private void drop() {
        token++;
        if (calcmula != null) {
            calcmula.cancel();
        }
        if (suggestionInsert != null) {
            suggestionInsert = null;
            suggestionValue = null;
            suggestionAt = -1;
            view.invalidate();
        }
    }

    private void clearUndo() {
        undoStart = -1;
        undoEnd = -1;
    }

    private boolean canTrigger() {
        CharSequence text = view.getText();
        int selection = view.getSelectionStart();
        return text != null && selection >= 2 && selection <= text.length() && text.charAt(selection - 1) == '=';
    }

    private void refresh() {
        if (!canTrigger() || insertingSelf) {
            if (suggestionInsert != null || calcmula != null) {
                drop();
            }
            return;
        }
        if (!RawCalcConfig.enabled.get() || suppressedAt >= 0 || !view.isFocused() || !view.isEnabled()
                || view.getSelectionStart() != view.getSelectionEnd() || !canShowAt(view.getText(), view.getSelectionStart())) {
            drop();
            return;
        }
        if (!warmed) {
            warmed = true;
            RawLocalCalc.warmUp();
        }
        final CharSequence text = view.getText();
        final int caret = view.getSelectionStart();
        final int mine = ++token;
        if (calcmula != null) {
            calcmula.cancel();
        }
        if (suggestionInsert != null && suggestionAt != caret) {
            suggestionInsert = null;
            suggestionValue = null;
            suggestionAt = -1;
        }
        boolean useLocal = RawCalcConfig.useLocal();
        boolean useCalcmula = RawCalcConfig.useCalcmula() && delegate.allowRemote();
        if (useLocal) {
            local.evaluate(text, caret, value -> {
                if (value != null) {
                    accept(text, caret, value, mine);
                } else if (useCalcmula) {
                    askCalcmula(text, caret, mine);
                } else {
                    clearSuggestion();
                }
            });
        } else if (useCalcmula) {
            askCalcmula(text, caret, mine);
        } else {
            clearSuggestion();
        }
    }

    private void askCalcmula(CharSequence text, int caret, int mine) {
        if (calcmula == null) {
            calcmula = new RawCalcmula();
        }
        // the text is edited in place: keep an immutable copy of what is asked about
        final String snapshot = text.toString();
        calcmula.evaluate(snapshot, caret, value -> {
            if (value != null) {
                accept(snapshot, caret, value, mine);
            } else if (mine == token) {
                clearSuggestion();
            }
        });
    }

    private void clearSuggestion() {
        if (suggestionInsert != null) {
            suggestionInsert = null;
            suggestionValue = null;
            suggestionAt = -1;
            view.invalidate();
        }
    }

    private void accept(CharSequence asked, int caret, String value, int mine) {
        if (mine != token) {
            return;
        }
        CharSequence now = view.getText();
        if (now == null || view.getSelectionStart() != caret || view.getSelectionEnd() != caret || caret > now.length()
                || !sameBefore(asked, now, caret)) {
            return;
        }
        char before = now.charAt(caret - 2);
        boolean blank = before == ' ' || before == '\t' || before == ' ';
        suggestionValue = value;
        suggestionInsert = blank ? " " + value : value;
        suggestionAt = caret;
        view.invalidate();
    }

    /** The text before the caret is what the answer was computed for. */
    private static boolean sameBefore(CharSequence asked, CharSequence now, int caret) {
        if (asked == now) {
            return true;
        }
        int from = Math.max(0, caret - 80);
        for (int i = from; i < caret; i++) {
            if (i >= asked.length() || asked.charAt(i) != now.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Composing text, styled text and special spans around the caret would not match the plain ghost. */
    private boolean canShowAt(CharSequence text, int caret) {
        if (text == null || caret < 2 || caret > text.length()) {
            return false;
        }
        if (text instanceof Spannable && BaseInputConnection.getComposingSpanStart((Spannable) text) != -1) {
            return false;
        }
        int next = caret;
        if (next < text.length() && text.charAt(next) != '\n') {
            return false; // text follows on the same line
        }
        if (text instanceof Spanned) {
            Spanned spanned = (Spanned) text;
            if (spanned.getSpans(caret - 1, caret, MetricAffectingSpan.class).length != 0) {
                return false;
            }
            int lineStart = caret - 1;
            while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') {
                lineStart--;
            }
            if (spanned.getSpans(lineStart, caret, AnimatedEmojiSpan.class).length != 0
                    || spanned.getSpans(lineStart, caret, QuoteSpan.class).length != 0) {
                return false;
            }
            for (TextStyleSpan span : spanned.getSpans(lineStart, caret, TextStyleSpan.class)) {
                if (span.isSpoiler()) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- insert / undo ----

    private boolean commit() {
        final String insert = suggestionInsert;
        final int at = suggestionAt;
        if (insert == null) {
            return false;
        }
        CharSequence text = view.getText();
        final Editable editable = text instanceof Editable ? (Editable) text : null;
        if (editable == null || at != view.getSelectionStart() || view.getSelectionStart() != view.getSelectionEnd() || at > editable.length()) {
            return false;
        }
        BaseInputConnection.removeComposingSpans(editable);
        if (!edit(() -> editable.insert(at, insert))) {
            return false;
        }
        Selection.setSelection(editable, at + insert.length());
        undoStart = at;
        undoEnd = at + insert.length();
        suggestionInsert = null;
        suggestionValue = null;
        suggestionAt = -1;
        token++;
        if (calcmula != null) {
            calcmula.cancel();
        }
        appear.set(0f, true);
        view.invalidate();
        return true;
    }

    private boolean undo() {
        if (undoStart < 0) {
            return false;
        }
        CharSequence text = view.getText();
        final Editable editable = text instanceof Editable ? (Editable) text : null;
        if (editable == null) {
            return false;
        }
        if (undoEnd > editable.length() || view.getSelectionStart() != undoEnd || view.getSelectionEnd() != undoEnd) {
            clearUndo();
            return false;
        }
        final int start = undoStart;
        final int end = undoEnd;
        if (!edit(() -> editable.delete(start, end))) {
            return false;
        }
        Selection.setSelection(editable, start);
        clearUndo();
        suppressedAt = start;
        view.invalidate();
        return true;
    }

    private boolean edit(Runnable block) {
        final boolean[] ok = {true};
        Runnable action = () -> {
            insertingSelf = true;
            try {
                block.run();
            } catch (Exception e) {
                FileLog.e(e);
                ok[0] = false;
            } finally {
                insertingSelf = false;
            }
        };
        if (delegate != null) {
            delegate.runProgrammatic(action);
        } else {
            action.run();
        }
        return ok[0];
    }
}
