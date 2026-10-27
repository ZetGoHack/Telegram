package org.telegram.rawgram.calc;


import org.telegram.messenger.LocaleController;
import org.telegram.rawgram.RawCalcConfig;
import org.telegram.rawgram.calc.math.MathExpression;
import org.telegram.rawgram.calc.math.MathOptions;
import org.telegram.rawgram.calc.math.MathSuggestion;
import org.telegram.rawgram.calc.time.TimeZoneOptions;

import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** The offline provider: the parser ported from exteraGram (GPLv3). */
public final class RawLocalCalc implements RawCalcProvider {

    private static final Set<String> IMPERIAL_REGIONS = new HashSet<>(Arrays.asList("US", "LR", "MM"));

    private final CalcRates rates;
    private char decimalSeparator = '.';
    private Locale separatorLocale;

    public RawLocalCalc(Runnable onRatesUpdated) {
        rates = new CalcRates(onRatesUpdated);
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public void cancel() {
    }

    @Override
    public void evaluate(CharSequence text, int caret, Callback callback) {
        MathSuggestion suggestion = null;
        try {
            suggestion = MathExpression.INSTANCE.suggestionAt(text, caret, options());
        } catch (RuntimeException ignore) {
        }
        callback.onResult(suggestion == null ? null : suggestion.getValue());
    }

    private MathOptions options() {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        if (locale == null) {
            locale = Locale.US;
        }
        if (!locale.equals(separatorLocale)) {
            separatorLocale = locale;
            decimalSeparator = DecimalFormatSymbols.getInstance(locale).getDecimalSeparator();
        }
        boolean currency = RawCalcConfig.currency.get();
        Locale system = LocaleController.getInstance().getSystemDefaultLocale();
        boolean imperial = system != null && IMPERIAL_REGIONS.contains(system.getCountry());
        return new MathOptions(decimalSeparator, currency ? rates : null, currency ? rates.localCurrency() : null,
                RawCalcConfig.math.get(), RawCalcConfig.units.get(), RawCalcConfig.degrees.get(), RawCalcConfig.grouping.get(),
                RawCalcConfig.timeZones.get() ? new TimeZoneOptions(ZoneId.systemDefault(), null) : null, imperial);
    }

    /** Loads the parser's tables off the UI thread, so the first «=» does not stall typing. */
    public static void warmUp() {
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            try {
                MathOptions warm = new MathOptions('.', null, null, true, true, true, true,
                        new TimeZoneOptions(ZoneId.systemDefault(), null), false);
                MathExpression.INSTANCE.suggestionAt("2 m + 3 ft 6pm est =", 20, warm);
            } catch (Throwable ignore) {
            }
        });
    }
}
