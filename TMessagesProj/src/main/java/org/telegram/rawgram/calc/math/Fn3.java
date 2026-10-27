package org.telegram.rawgram.calc.math;

/** A three-argument function (stands in for kotlin.jvm.functions.Function3). */
public interface Fn3<A, B, C, R> {
    R invoke(A a, B b, C c);
}
