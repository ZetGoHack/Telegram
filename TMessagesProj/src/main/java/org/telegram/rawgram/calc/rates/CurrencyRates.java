package org.telegram.rawgram.calc.rates;



public interface CurrencyRates {
    Double rate(String from, String to);

    boolean supports(String code);
}
