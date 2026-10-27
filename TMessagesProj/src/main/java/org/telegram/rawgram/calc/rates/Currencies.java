package org.telegram.rawgram.calc.rates;

import java.util.Objects;
import org.telegram.rawgram.calc.Coll;
import org.telegram.rawgram.calc.math.MathUnit;
import java.util.Collections;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;



public final class Currencies {
    public static final Currencies INSTANCE = new Currencies();
    private static final Set<String> conversions;
    private static Map<String, String> labels;
    private static final Set<String> lowercaseCodes;
    private static final Set<Character> symbolChars;
    private static Map<Character, String> symbols;
    private static Map<String, String> words;

    private Currencies() {
    }

    static {
        Map<Character, String> symbols = new LinkedHashMap<>();
        symbols.put('\u0024', "USD");
        symbols.put('\u20ac', "EUR");
        symbols.put('\u20bd', "RUB");
        symbols.put('\u00a3', "GBP");
        symbols.put('\u00a5', "JPY");
        symbols.put('\u20b8', "KZT");
        symbols.put('\u20b4', "UAH");
        symbols.put('\u20ba', "TRY");
        symbols.put('\u20b9', "INR");
        symbols.put('\u20aa', "ILS");
        symbols.put('\u20a9', "KRW");
        symbols.put('\u20be', "GEL");
        symbols.put('\u20bc', "AZN");
        symbols.put('\u058f', "AMD");
        symbols.put('\u20ab', "VND");
        symbols.put('\u0e3f', "THB");
        symbols.put('\u20b1', "PHP");
        symbols.put('\u20a6', "NGN");
        symbols.put('\u20bf', "BTC");
        symbols.put('\u2b50', "XTR");
        symbols.put('\u2605', "XTR");
        Currencies.symbols = symbols;
        Map<String, String> labels = new LinkedHashMap<>();
        for (Map.Entry<Character, String> entry : symbols.entrySet()) {
            if (!"BTC".equals(entry.getValue()) && entry.getKey() != '\u2605') {
                labels.put(entry.getValue(), String.valueOf(entry.getKey()));
            }
        }
        Currencies.labels = labels;
        lowercaseCodes = Coll.setOf(new String[]{"usd", "eur", "rub", "gbp", "kzt", "uah", "byn", "cny", "jpy", "pln", "czk", "aed", "inr", "ils", "chf", "cad", "aud", "uzs", "kgs", "azn", "krw", "thb", "vnd", "sgd", "hkd", "brl", "mxn", "sek", "huf", "btc", "eth", "usdt", "xtr"});
        conversions = Coll.setOf(new String[]{"в", "to", "in"});
        Map<String, String> words = new HashMap<>();
        add(words, "USD", "доллар", "доллара", "долларов", "доллары", "долларах", "долл", "бакс", "бакса", "баксов", "баксы", "баксах", "dollar", "dollars", "buck", "bucks");
        add(words, "EUR", "евро", "euro", "euros");
        add(words, "RUB", "рубль", "рубля", "рублей", "рубли", "рублях", "руб", "р", "ruble", "rubles", "rouble", "roubles");
        add(words, "GBP", "фунт", "фунта", "фунтов", "фунты", "фунтах");
        add(words, "KZT", "тенге", "tenge");
        add(words, "UAH", "гривна", "гривны", "гривен", "гривнах", "грн", "hryvnia", "hryvnias");
        add(words, "CNY", "юань", "юаня", "юаней", "юани", "юанях", "yuan");
        add(words, "JPY", "иена", "иены", "иен", "иенах", "йена", "йены", "йен", "йенах", "yen");
        add(words, "TRY", "лира", "лиры", "лир", "лирах", "lira", "liras");
        add(words, "PLN", "злотый", "злотого", "злотых", "злотые", "zloty", "zlotys", "zł");
        add(words, "CZK", "kč");
        add(words, "INR", "рупия", "рупии", "рупий", "рупиях", "rupee", "rupees");
        add(words, "ILS", "шекель", "шекеля", "шекелей", "шекели", "шекелях", "shekel", "shekels");
        add(words, "CHF", "франк", "франка", "франков", "франки", "франках", "franc", "francs");
        add(words, "AED", "дирхам", "дирхама", "дирхамов", "дирхамы", "дирхамах", "dirham", "dirhams");
        add(words, "THB", "бат", "бата", "батов", "баты", "батах", "baht");
        add(words, "GEL", "лари", "lari");
        add(words, "AMD", "драм", "драмов", "драмы", "драмах", "dram", "drams");
        add(words, "UZS", "сум", "сумов", "сумы", "сумах");
        add(words, "KGS", "сом", "сомов", "сомах", "som");
        add(words, "BTC", "биткоин", "биткоина", "биткоинов", "биткоины", "биткоинах", "биткойн", "биткойна", "биткойнов", "биткойны", "биткойнах", "bitcoin", "bitcoins");
        add(words, "ETH", "эфириум", "эфириума", "эфириумов", "эфириумах", "ethereum", "ether");
        add(words, "GRAM", "gram", "grams", "грам", "грама", "грамов", "грамы", "грамах", "ton", "тон", "тона", "тонов", "тонах", "toncoin", "toncoins", "тонкоин", "тонкоина", "тонкоинов");
        add(words, "USDT", "tether");
        add(words, "XTR", "звезда", "звезды", "звёзды", "звезд", "звёзд", "звездах", "звёздах", "звездами", "звёздами", "звезду", "star", "stars");
        Currencies.words = words;
        symbolChars = Collections.unmodifiableSet(symbols.keySet());
    }

    public final Set<Character> getSymbolChars() {
        return symbolChars;
    }

    public final boolean isConversion(String word) {
        return conversions.contains(word.toLowerCase(Locale.ROOT));
    }

    public final MathUnit symbol(char c2, CurrencyRates rates) {
        String str = symbols.get(Character.valueOf(c2));
        if (str != null && rates.supports(str)) {
            return new MathUnit(str, Objects.equals(str, "XTR") ? label(str) : String.valueOf(c2));
        }
        return null;
    }

    public final MathUnit word(String word, CurrencyRates rates) {
        String str;
        String upperCase = word;
        String lowerCase = upperCase.toLowerCase(Locale.ROOT);
        String str2 = words.get(lowerCase);
        if (str2 == null) {
            for (int i = 0; i < upperCase.length(); i++) {
                char cCharAt = upperCase.charAt(i);
                if ('A' > cCharAt || cCharAt >= '[') {
                    if (!lowercaseCodes.contains(lowerCase)) {
                        return null;
                    }
                    upperCase = lowerCase.toUpperCase(Locale.ROOT);
                    break;
                }
            }
            str = upperCase;
        } else {
            str = str2;
        }
        if (rates.supports(str)) {
            return new MathUnit(str, str2 != null ? label(str) : str);
        }
        return null;
    }

    public final MathUnit unit(String code) {
        return new MathUnit(code, label(code));
    }

    public final int fractionDigits(String code) {
        if (Objects.equals(code, "XTR")) {
            return 0;
        }
        try {
            return Currency.getInstance(code).getDefaultFractionDigits();
        } catch (IllegalArgumentException unused) {
            return -1;
        }
    }

    private final String label(String code) {
        String str = labels.get(code);
        return str == null ? code : str;
    }

    private static void add(Map<String, String> map, String str, String... strArr) {
        for (String str2 : strArr) {
            map.put(str2, str);
        }
    }
}
