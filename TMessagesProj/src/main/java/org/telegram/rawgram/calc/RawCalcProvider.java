package org.telegram.rawgram.calc;

/** Something that can answer "what is the expression before this «=»". */
public interface RawCalcProvider {

    interface Callback {
        /** @param value the answer as shown, or null when there is none */
        void onResult(String value);

        /** An online request is under way (called before {@link #onResult}). */
        default void onLoading() {
        }

        /** The request failed or the server refused the expression. */
        default void onError() {
        }
    }

    /** True when the answer comes later (a network round trip), so the caller must wait for the callback. */
    boolean isAsync();

    /**
     * Evaluates the expression that ends with the "=" right before {@code caret} in {@code text}.
     * A synchronous provider calls the callback before returning; an asynchronous one calls it on the UI thread,
     * and never after {@link #cancel()}.
     */
    void evaluate(CharSequence text, int caret, Callback callback);

    /** Drops a pending or running request. */
    void cancel();
}
