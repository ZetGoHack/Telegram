package org.telegram.rawgram.calc.math;

import java.util.List;


public final class InfixOperator {
    private final Fn3<Double, Double, Boolean, Double> apply;
    private final int precedence;
    private final boolean rightAssociative;
    private final List<String> symbols;
    private final UnitRule units;

        public InfixOperator(List<String> list, int i, UnitRule unitRule, boolean z, Fn3<Double, Double, Boolean, Double> function3) {
        this.symbols = list;
        this.precedence = i;
        this.units = unitRule;
        this.rightAssociative = z;
        this.apply = function3;
    }


    public final List<String> getSymbols() {
        return this.symbols;
    }

    public final int getPrecedence() {
        return this.precedence;
    }

    public final UnitRule getUnits() {
        return this.units;
    }

    public final boolean getRightAssociative() {
        return this.rightAssociative;
    }

    public final Fn3<Double, Double, Boolean, Double> getApply() {
        return this.apply;
    }
}
