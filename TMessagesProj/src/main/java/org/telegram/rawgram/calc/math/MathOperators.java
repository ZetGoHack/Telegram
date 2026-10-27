package org.telegram.rawgram.calc.math;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operators, functions, constants and number shorthands of the inline calculator.
 * Ported from exteraGram 13.1.1 (com.exteragram.messenger.inline.math.MathOperators), GPLv3.
 */
public final class MathOperators {
    public static final MathOperators INSTANCE = new MathOperators();

    private static final Map<String, Integer> bases;
    private static final Map<String, Double> constants;
    private static final List<MathFunction> functions;
    private static final Set<String> gluedMultipliers;
    private static final List<InfixOperator> infix;
    private static final Map<String, Double> multipliers;
    private static final Set<String> percentOf;
    private static final List<PostfixOperator> postfix;
    private static final List<PrefixOperator> prefix;
    private static final Set<Character> symbolChars;
    private static final List<String> symbols;

    private MathOperators() {
    }

    private static MathFunction fn(String name, int min, int max, AngleUse angle, Fn1<double[], Double> apply) {
        return new MathFunction(name, new IntRange(min, max), angle, apply);
    }

    private static void multiplier(Map<String, Double> map, double value, String... names) {
        for (String name : names) {
            map.put(name, value);
        }
    }

    static {
        infix = Collections.unmodifiableList(Arrays.asList(
                new InfixOperator(Arrays.asList("+"), 10, UnitRule.SAME, false,
                        (a, b, percent) -> percent ? a + b * a : a + b),
                new InfixOperator(Arrays.asList("-", "−"), 10, UnitRule.SAME, false,
                        (a, b, percent) -> percent ? a - b * a : a - b),
                new InfixOperator(Arrays.asList("*", "×"), 20, UnitRule.SCALE, false,
                        (a, b, percent) -> a * b),
                new InfixOperator(Arrays.asList("/", "÷"), 20, UnitRule.RATIO, false,
                        (a, b, percent) -> a / b),
                new InfixOperator(Arrays.asList("^"), 40, UnitRule.NONE, true,
                        (a, b, percent) -> Math.pow(a, b))));
        prefix = Collections.unmodifiableList(Arrays.asList(
                new PrefixOperator(Arrays.asList("-", "−"), 30, false, a -> -a),
                new PrefixOperator(Arrays.asList("+"), 30, false, a -> a),
                new PrefixOperator(Arrays.asList("√"), 40, true, a -> Math.sqrt(a))));
        postfix = Collections.unmodifiableList(Arrays.asList(
                new PostfixOperator(Arrays.asList("%"), true, a -> a / 100.0),
                new PostfixOperator(Arrays.asList("!"), false, a -> INSTANCE.factorial(a))));
        functions = Collections.unmodifiableList(Arrays.asList(
                fn("sqrt", 1, 1, AngleUse.NONE, a -> Math.sqrt(a[0])),
                fn("cbrt", 1, 1, AngleUse.NONE, a -> Math.cbrt(a[0])),
                fn("abs", 1, 1, AngleUse.NONE, a -> Math.abs(a[0])),
                fn("sign", 1, 1, AngleUse.NONE, a -> Math.signum(a[0])),
                fn("round", 1, 1, AngleUse.NONE, a -> Double.isNaN(a[0]) ? Double.NaN : (double) Math.round(a[0])),
                fn("floor", 1, 1, AngleUse.NONE, a -> Math.floor(a[0])),
                fn("ceil", 1, 1, AngleUse.NONE, a -> Math.ceil(a[0])),
                fn("fact", 1, 1, AngleUse.NONE, a -> INSTANCE.factorial(a[0])),
                fn("min", 1, Integer.MAX_VALUE, AngleUse.NONE, a -> {
                    double result = a[0];
                    for (double v : a) {
                        result = Math.min(result, v);
                    }
                    return result;
                }),
                fn("max", 1, Integer.MAX_VALUE, AngleUse.NONE, a -> {
                    double result = a[0];
                    for (double v : a) {
                        result = Math.max(result, v);
                    }
                    return result;
                }),
                fn("log", 1, 1, AngleUse.NONE, a -> Math.log10(a[0])),
                fn("log2", 1, 1, AngleUse.NONE, a -> Math.log(a[0]) / Math.log(2.0)),
                fn("ln", 1, 1, AngleUse.NONE, a -> Math.log(a[0])),
                fn("exp", 1, 1, AngleUse.NONE, a -> Math.exp(a[0])),
                fn("sin", 1, 1, AngleUse.ARGUMENT, a -> Math.sin(a[0])),
                fn("cos", 1, 1, AngleUse.ARGUMENT, a -> Math.cos(a[0])),
                fn("tan", 1, 1, AngleUse.ARGUMENT, a -> Math.tan(a[0])),
                fn("asin", 1, 1, AngleUse.RESULT, a -> Math.asin(a[0])),
                fn("acos", 1, 1, AngleUse.RESULT, a -> Math.acos(a[0])),
                fn("atan", 1, 1, AngleUse.RESULT, a -> Math.atan(a[0])),
                fn("atan2", 2, 2, AngleUse.RESULT, a -> Math.atan2(a[0], a[1])),
                fn("sinh", 1, 1, AngleUse.NONE, a -> Math.sinh(a[0])),
                fn("cosh", 1, 1, AngleUse.NONE, a -> Math.cosh(a[0])),
                fn("tanh", 1, 1, AngleUse.NONE, a -> Math.tanh(a[0]))));

        Map<String, Double> mult = new HashMap<>();
        multiplier(mult, 1000.0, "к", "k", "тыс", "тысяча", "тысячи", "тысяч", "thousand", "thousands");
        multiplier(mult, 1000000.0, "кк", "kk", "млн", "миллион", "миллиона", "миллионов", "million", "millions");
        multiplier(mult, 1.0E9, "ккк", "kkk", "млрд", "миллиард", "миллиарда", "миллиардов", "billion", "billions");
        multipliers = Collections.unmodifiableMap(mult);
        gluedMultipliers = new HashSet<>(Arrays.asList("к", "k", "кк", "kk", "ккк", "kkk"));
        percentOf = new HashSet<>(Arrays.asList("от", "of"));

        Map<String, Integer> radixes = new HashMap<>();
        radixes.put("bin", 2);
        radixes.put("oct", 8);
        radixes.put("hex", 16);
        radixes.put("dec", 10);
        bases = Collections.unmodifiableMap(radixes);

        Map<String, Double> consts = new HashMap<>();
        consts.put("pi", Math.PI);
        consts.put("π", Math.PI);
        consts.put("e", Math.E);
        constants = Collections.unmodifiableMap(consts);

        Set<String> all = new LinkedHashSet<>();
        for (InfixOperator op : infix) {
            all.addAll(op.getSymbols());
        }
        for (PrefixOperator op : prefix) {
            all.addAll(op.getSymbols());
        }
        for (PostfixOperator op : postfix) {
            all.addAll(op.getSymbols());
        }
        List<String> sorted = new ArrayList<>(all);
        Collections.sort(sorted, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return b.length() - a.length();
            }
        });
        symbols = Collections.unmodifiableList(sorted);

        Set<Character> chars = new HashSet<>();
        for (String symbol : sorted) {
            for (int i = 0; i < symbol.length(); i++) {
                chars.add(symbol.charAt(i));
            }
        }
        for (String name : consts.keySet()) {
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (!Character.isLetter(c)) {
                    chars.add(c);
                }
            }
        }
        chars.add('(');
        chars.add(')');
        chars.add(';');
        symbolChars = Collections.unmodifiableSet(chars);
    }

    public int radixFor(char prefix) {
        switch (prefix) {
            case 'B':
            case 'b':
                return 2;
            case 'O':
            case 'o':
                return 8;
            case 'X':
            case 'x':
                return 16;
            default:
                return 0;
        }
    }

    public Map<String, Double> getMultipliers() {
        return multipliers;
    }

    public Set<String> getGluedMultipliers() {
        return gluedMultipliers;
    }

    public Set<String> getPercentOf() {
        return percentOf;
    }

    public Map<String, Integer> getBases() {
        return bases;
    }

    public String stripRadixPrefix(String digits, int radix) {
        return (digits.length() > 2 && digits.charAt(0) == '0' && radixFor(digits.charAt(1)) == radix) ? digits.substring(2) : digits;
    }

    public Map<String, Double> getConstants() {
        return constants;
    }

    public Set<Character> getSymbolChars() {
        return symbolChars;
    }

    public String matchSymbol(CharSequence text, int at) {
        for (int i = 0, n = symbols.size(); i < n; i++) {
            String symbol = symbols.get(i);
            int length = symbol.length();
            if (at + length > text.length()) {
                continue;
            }
            boolean same = true;
            for (int k = 0; k < length; k++) {
                if (text.charAt(at + k) != symbol.charAt(k)) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return symbol;
            }
        }
        return null;
    }

    public InfixOperator infixFor(String symbol) {
        for (InfixOperator op : infix) {
            if (op.getSymbols().contains(symbol)) {
                return op;
            }
        }
        return null;
    }

    public PrefixOperator prefixFor(String symbol) {
        for (PrefixOperator op : prefix) {
            if (op.getSymbols().contains(symbol)) {
                return op;
            }
        }
        return null;
    }

    public PostfixOperator postfixFor(String symbol) {
        for (PostfixOperator op : postfix) {
            if (op.getSymbols().contains(symbol)) {
                return op;
            }
        }
        return null;
    }

    public MathFunction functionFor(String name) {
        for (MathFunction function : functions) {
            if (function.getName().equals(name)) {
                return function;
            }
        }
        return null;
    }

    public double factorial(double value) {
        if (value < 0.0 || value != Math.floor(value) || value > 170.0) {
            return Double.NaN;
        }
        double result = 1.0;
        for (int i = 2; i <= (int) value; i++) {
            result *= i;
        }
        return result;
    }
}
