package org.telegram.rawgram.calc;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small immutable collection helpers. */
public final class Coll {
    private Coll() {
    }

    @SafeVarargs
    public static <T> List<T> listOf(T... items) {
        return Collections.unmodifiableList(Arrays.asList(items));
    }

    @SafeVarargs
    public static <T> Set<T> setOf(T... items) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(items)));
    }
}
