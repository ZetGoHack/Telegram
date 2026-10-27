package org.telegram.rawgram.calc.math;

import java.util.List;


public final class PostfixOperator {
    private final Fn1<Double, Double> apply;
    private final boolean percent;
    private final List<String> symbols;

        public PostfixOperator(List<String> list, boolean z, Fn1<Double, Double> function1) {
        this.symbols = list;
        this.percent = z;
        this.apply = function1;
    }


    public final List<String> getSymbols() {
        return this.symbols;
    }

    public final boolean getPercent() {
        return this.percent;
    }

    public final Fn1<Double, Double> getApply() {
        return this.apply;
    }
}
