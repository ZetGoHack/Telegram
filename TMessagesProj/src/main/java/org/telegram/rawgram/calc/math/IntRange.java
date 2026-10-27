package org.telegram.rawgram.calc.math;

/** Inclusive int range, used for function arity. */
public final class IntRange {
    private final int first;
    private final int last;

    public IntRange(int first, int last) {
        this.first = first;
        this.last = last;
    }

    public int getFirst() {
        return first;
    }

    public int getLast() {
        return last;
    }

    public boolean contains(int value) {
        return value >= first && value <= last;
    }
}
