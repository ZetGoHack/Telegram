package org.telegram.rawgram.calc.math;



public final class MathFunction {
    private final AngleUse angle;
    private final Fn1<double[], Double> apply;
    private final IntRange arity;
    private final String name;

        public MathFunction(String str, IntRange intRange, AngleUse angleUse, Fn1<double[], Double> function1) {
        this.name = str;
        this.arity = intRange;
        this.angle = angleUse;
        this.apply = function1;
    }

    public final String getName() {
        return this.name;
    }

    public final IntRange getArity() {
        return this.arity;
    }


    public final AngleUse getAngle() {
        return this.angle;
    }

    public final Fn1<double[], Double> getApply() {
        return this.apply;
    }
}
