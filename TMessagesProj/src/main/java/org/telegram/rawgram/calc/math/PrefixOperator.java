package org.telegram.rawgram.calc.math;

import java.util.List;


public final class PrefixOperator {
    private final Fn1<Double, Double> apply;
    private final boolean operation;
    private final int precedence;
    private final List<String> symbols;

        public PrefixOperator(List<String> list, int i, boolean z, Fn1<Double, Double> function1) {
        this.symbols = list;
        this.precedence = i;
        this.operation = z;
        this.apply = function1;
    }


    public final List<String> getSymbols() {
        return this.symbols;
    }

    public final int getPrecedence() {
        return this.precedence;
    }

    public final boolean getOperation() {
        return this.operation;
    }

    public final Fn1<Double, Double> getApply() {
        return this.apply;
    }
}
