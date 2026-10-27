package org.telegram.rawgram.calc.math;

/** A one-argument function (stands in for kotlin.jvm.functions.Function1). */
public interface Fn1<A, R> {
    R invoke(A a);
}
