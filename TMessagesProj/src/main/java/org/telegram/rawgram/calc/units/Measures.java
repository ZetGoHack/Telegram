package org.telegram.rawgram.calc.units;

import java.util.Objects;
import org.telegram.rawgram.calc.Coll;
import org.telegram.rawgram.calc.Pair;
import org.telegram.rawgram.calc.math.MathFormat;
import org.telegram.rawgram.calc.math.MathUnit;
import org.telegram.rawgram.calc.math.UnitKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;



public final class Measures {
    private static final List<String> breakdown;
    private static final HashMap<String, String> compounds;
    private static final HashMap<String, Ladder> ladders;
    public static final Measures INSTANCE = new Measures();
    private static final HashMap<String, MathUnit> units = new HashMap<>();
    private static final HashMap<String, String> words = new HashMap<>();
    private static final HashMap<String, String> counterparts = new HashMap<>();

    private Measures() {
    }

    static {
        HashMap<String, String> map = new HashMap<>();
        compounds = map;
        HashMap<String, Ladder> map2 = new HashMap<>();
        ladders = map2;
        breakdown = Coll.listOf(new String[]{"d", "h", "min", "s"});
        UnitKind unitKind = UnitKind.LENGTH;
        addUnit("mm", "mm", "мм", unitKind, 0.001d, "in", new String[]{"mm", "мм", "миллиметр", "миллиметра", "миллиметров", "миллиметрах", "millimeter", "millimeters", "millimetre", "millimetres"}, 0.0d);
        addUnit("cm", "cm", "см", unitKind, 0.01d, "in", new String[]{"cm", "см", "сантиметр", "сантиметра", "сантиметров", "сантиметрах", "centimeter", "centimeters", "centimetre", "centimetres"}, 0.0d);
        addUnit("m", "m", "м", unitKind, 1.0d, "ft", new String[]{"m", "м", "метр", "метра", "метров", "метрах", "meter", "meters", "metre", "metres"}, 0.0d);
        addUnit("km", "km", "км", unitKind, 1000.0d, "mi", new String[]{"km", "км", "километр", "километра", "километров", "километрах", "kilometer", "kilometers", "kilometre", "kilometres"}, 0.0d);
        addUnit("in", "in", "in", unitKind, 0.0254d, "cm", new String[]{"inch", "inches", "дюйм", "дюйма", "дюймов", "дюймах"}, 0.0d);
        addUnit("ft", "ft", "ft", unitKind, 0.3048d, "m", new String[]{"ft", "foot", "feet", "фут", "фута", "футов", "футах"}, 0.0d);
        addUnit("yd", "yd", "yd", unitKind, 0.9144d, "m", new String[]{"yd", "yard", "yards", "ярд", "ярда", "ярдов", "ярдах"}, 0.0d);
        addUnit("mi", "mi", "mi", unitKind, 1609.344d, "km", new String[]{"mi", "mile", "miles", "миля", "мили", "миль", "милях"}, 0.0d);
        UnitKind unitKind2 = UnitKind.MASS;
        addUnit("mg", "mg", "мг", unitKind2, 1.0E-6d, null, new String[]{"mg", "мг", "миллиграмм", "миллиграмма", "миллиграммов", "milligram", "milligrams"}, 0.0d);
        addUnit("g", "g", "г", unitKind2, 0.001d, "oz", new String[]{"g", "г", "гр", "грамм", "грамма", "граммов", "граммах", "gram", "grams", "gramme", "grammes"}, 0.0d);
        addUnit("kg", "kg", "кг", unitKind2, 1.0d, "lb", new String[]{"kg", "кг", "килограмм", "килограмма", "килограммов", "килограммах", "kilogram", "kilograms", "kilo", "kilos"}, 0.0d);
        addUnit("t", "t", "т", unitKind2, 1000.0d, "lb", new String[]{"t", "т", "тонна", "тонны", "тонн", "тоннах", "tonne", "tonnes"}, 0.0d);
        addUnit("oz", "oz", "oz", unitKind2, 0.028349523125d, "g", new String[]{"oz", "ounce", "ounces", "унция", "унции", "унций", "унциях"}, 0.0d);
        addUnit("lb", "lb", "lb", unitKind2, 0.45359237d, "kg", new String[]{"lb", "lbs", "pound", "pounds", "фунт", "фунта", "фунтов", "фунтах"}, 0.0d);
        UnitKind unitKind3 = UnitKind.VOLUME;
        addUnit("ml", "ml", "мл", unitKind3, 0.001d, "floz", new String[]{"ml", "мл", "миллилитр", "миллилитра", "миллилитров", "миллилитрах", "milliliter", "milliliters", "millilitre", "millilitres"}, 0.0d);
        addUnit("l", "l", "л", unitKind3, 1.0d, "gal", new String[]{"l", "л", "литр", "литра", "литров", "литрах", "liter", "liters", "litre", "litres"}, 0.0d);
        addUnit("floz", "fl oz", "fl oz", unitKind3, 0.0295735295625d, "ml", new String[]{"floz"}, 0.0d);
        addUnit("gal", "gal", "gal", unitKind3, 3.785411784d, "l", new String[]{"gal", "gallon", "gallons", "галлон", "галлона", "галлонов", "галлонах"}, 0.0d);
        UnitKind unitKind4 = UnitKind.AREA;
        addUnit("m2", "m²", "м²", unitKind4, 1.0d, "ft2", new String[]{"m2", "m²", "м2", "м²", "sqm"}, 0.0d);
        addUnit("km2", "km²", "км²", unitKind4, 1000000.0d, "mi2", new String[]{"km2", "km²", "км2", "км²"}, 0.0d);
        addUnit("ha", "ha", "га", unitKind4, 10000.0d, "acre", new String[]{"ha", "га", "гектар", "гектара", "гектаров", "гектарах", "hectare", "hectares"}, 0.0d);
        addUnit("ft2", "ft²", "ft²", unitKind4, 0.09290304d, "m2", new String[]{"ft2", "ft²", "sqft"}, 0.0d);
        addUnit("mi2", "mi²", "mi²", unitKind4, 2589988.110336d, "km2", new String[]{"mi2", "mi²"}, 0.0d);
        addUnit("acre", "ac", "ac", unitKind4, 4046.8564224d, "ha", new String[]{"ac", "acre", "acres", "акр", "акра", "акров", "акрах"}, 0.0d);
        UnitKind unitKind5 = UnitKind.SPEED;
        addUnit("kmh", "km/h", "км/ч", unitKind5, 0.2777777777777778d, "mph", new String[]{"kmh", "kph"}, 0.0d);
        addUnit("ms", "m/s", "м/с", unitKind5, 1.0d, "kmh", new String[]{"mps"}, 0.0d);
        addUnit("mph", "mph", "mph", unitKind5, 0.44704d, "kmh", new String[]{"mph"}, 0.0d);
        addUnit("knot", "kn", "уз", unitKind5, 0.5144444444444445d, "kmh", new String[]{"knot", "knots", "узел", "узла", "узлов", "узлах"}, 0.0d);
        UnitKind unitKind6 = UnitKind.TEMPERATURE;
        addUnit("C", "°C", "°C", unitKind6, 1.0d, "F", new String[]{"celsius", "цельсий", "цельсия", "цельсию", "цельсиях"}, 273.15d);
        addUnit("F", "°F", "°F", unitKind6, 0.5555555555555556d, "C", new String[]{"fahrenheit", "фаренгейт", "фаренгейта", "фаренгейту", "фаренгейтах"}, 255.3722222222222d);
        addUnit("K", "K", "K", unitKind6, 1.0d, "C", new String[]{"kelvin", "kelvins", "кельвин", "кельвина", "кельвинов", "кельвинах"}, 0.0d);
        UnitKind unitKind7 = UnitKind.DURATION;
        addUnit("msec", "ms", "мс", unitKind7, 0.001d, null, new String[]{"ms", "мс", "millisecond", "milliseconds", "миллисекунда", "миллисекунды", "миллисекунд"}, 0.0d);
        addUnit("s", "s", "с", unitKind7, 1.0d, null, new String[]{"s", "sec", "secs", "second", "seconds", "с", "сек", "секунда", "секунды", "секунд", "секундах", "секунду"}, 0.0d);
        addUnit("min", "min", "мин", unitKind7, 60.0d, null, new String[]{"min", "mins", "minute", "minutes", "мин", "минута", "минуты", "минут", "минутах", "минуту"}, 0.0d);
        addUnit("h", "h", "ч", unitKind7, 3600.0d, null, new String[]{"h", "hr", "hrs", "hour", "hours", "ч", "час", "часа", "часов", "часах"}, 0.0d);
        addUnit("d", "d", "д", unitKind7, 86400.0d, null, new String[]{"d", "day", "days", "д", "дн", "день", "дня", "дней", "днях", "сут", "сутки", "суток"}, 0.0d);
        addUnit("w", "w", "нед", unitKind7, 604800.0d, null, new String[]{"w", "wk", "week", "weeks", "нед", "неделя", "недели", "недель", "неделях", "неделю"}, 0.0d);
        addUnit("mo", "mo", "мес", unitKind7, 2592000.0d, null, new String[]{"mo", "month", "months", "мес", "месяц", "месяца", "месяцев", "месяцах"}, 0.0d);
        addUnit("y", "y", "г.", unitKind7, 3.1536E7d, null, new String[]{"y", "yr", "yrs", "year", "years", "год", "года", "лет", "годах"}, 0.0d);
        map.put("km/h", "kmh");
        map.put("км/ч", "kmh");
        map.put("m/s", "ms");
        map.put("м/с", "ms");
        map.put("fl oz", "floz");
        addLadder(false, "mm", "cm", "m", "km");
        addLadder(false, "mg", "g", "kg", "t");
        addLadder(false, "ml", "l");
        addLadder(false, "m2", "ha", "km2");
        map2.put("yd", addLadder(true, "in", "ft", "mi"));
        addLadder(true, "oz", "lb");
        addLadder(true, "floz", "gal");
        addLadder(true, "ft2", "acre", "mi2");
    }


    public static final class Ladder {
        private final List<String> codes;
        private final boolean imperial;

        public Ladder(boolean z, List<String> list) {
            this.imperial = z;
            this.codes = list;
        }

        public final List<String> getCodes() {
            return this.codes;
        }

        public final boolean getImperial() {
            return this.imperial;
        }
    }

    private static final void addUnit(String str, String str2, String str3, UnitKind unitKind, double d, String str4, String[] strArr, double d2) {
        units.put(str, new MathUnit(str, str2, unitKind, d, d2, str3));
        if (str4 != null) {
            counterparts.put(str, str4);
        }
        for (String str5 : strArr) {
            words.put(str5, str);
        }
    }

    private static final Ladder addLadder(boolean z, String... strArr) {
        Ladder ladder = new Ladder(z, Coll.listOf(strArr));
        for (String str : strArr) {
            ladders.put(str, ladder);
        }
        return ladder;
    }

    public final MathUnit word(String word) {
        String str = words.get(word.toLowerCase(Locale.ROOT));
        if (str != null) {
            return units.get(str);
        }
        return null;
    }

    public final MathUnit compound(String first, char separator, String second) {
        Locale locale = Locale.ROOT;
        String str = compounds.get(first.toLowerCase(locale) + separator + second.toLowerCase(locale));
        if (str != null) {
            return units.get(str);
        }
        return null;
    }

    public final MathUnit temperature(char scale) {
        switch (scale) {
            case 'C':
            case 'c':
            case 1057:
            case 1089:
                return units.get("C");
            case 'F':
            case 'f':
            case 1060:
            case 1092:
                return units.get("F");
            case 'K':
            case 'k':
            case 1050:
            case 1082:
                return units.get("K");
            default:
                return null;
        }
    }

    public final MathUnit symbol(char c2) {
        if (c2 == 8451) {
            return units.get("C");
        }
        if (c2 != 8457) {
            return null;
        }
        return units.get("F");
    }

    public final MathUnit implicitTarget(MathUnit unit, double value, boolean imperial) {
        MathUnit mathUnitRescale;
        Ladder ladder = ladders.get(unit.getCode());
        if (ladder != null && ladder.getImperial() == imperial && (mathUnitRescale = rescale(ladder, unit, Math.abs(unit.toBase(value)))) != null) {
            return mathUnitRescale;
        }
        String str = counterparts.get(unit.getCode());
        if (str != null) {
            return units.get(str);
        }
        return null;
    }

    private final MathUnit rescale(Ladder ladder, MathUnit unit, double base) {
        List<String> codes = ladder.getCodes();
        for (int k = codes.size() - 1; k >= 0; k--) {
            String str = codes.get(k);
            MathUnit mathUnit = units.get(str);
            double dFromBase = mathUnit.fromBase(base);
            if (!Objects.equals(str, unit.getCode()) && dFromBase >= 0.1d && dFromBase < 1000.0d) {
                return mathUnit;
            }
        }
        int iIndexOf = ladder.getCodes().indexOf(unit.getCode());
        if (iIndexOf <= 0) {
            return null;
        }
        MathUnit mathUnit2 = units.get(ladder.getCodes().get(iIndexOf - 1));
        double dFromBase2 = mathUnit2.fromBase(base);
        if (dFromBase2 < 1.0d || dFromBase2 >= 100000.0d) {
            return null;
        }
        return mathUnit2;
    }

    public final boolean canBreakDown(MathUnit unit) {
        return breakdown.contains(unit.getCode());
    }

    public final boolean breaksDown(double value, MathUnit unit) {
        List<Pair<String, Double>> listParts;
        if (breakdown.contains(unit.getCode()) && (listParts = parts(Math.abs(value * unit.getScale()))) != null) {
            return listParts.size() > 1 || !Objects.equals(listParts.get(0).getFirst(), unit.getCode());
        }
        return false;
    }

    public final String formatDuration(double seconds, char decimalSeparator, boolean russian) {
        List<Pair<String, Double>> listParts;
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || (listParts = parts(Math.abs(seconds))) == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (seconds < 0.0d) {
            sb.append('-');
        }
        for (int i = 0; i < listParts.size(); i++) {
            Pair<String, Double> pair = listParts.get(i);
            if (i > 0) {
                sb.append(' ');
            }
            String number = MathFormat.INSTANCE.format(pair.getSecond(), decimalSeparator, false);
            if (number == null) {
                return null;
            }
            sb.append(number);
            sb.append(' ');
            sb.append(units.get(pair.getFirst()).label(russian));
        }
        return sb.toString();
    }

    private final List<Pair<String, Double>> parts(double seconds) {
        if (seconds >= 1.0E12d) {
            return null;
        }
        if (seconds < 60.0d) {
            return Coll.listOf(Pair.to("s", Double.valueOf(seconds)));
        }
        long jRoundToLong = Math.round(seconds);
        ArrayList arrayList = new ArrayList(4);
        for (String str : breakdown) {
            long scale = (long) (units.get(str)).getScale();
            long jFloor = (long) Math.floor(jRoundToLong / scale);
            if (jFloor > 0) {
                arrayList.add(Pair.to(str, Double.valueOf(jFloor)));
                jRoundToLong -= jFloor * scale;
            }
        }
        boolean zIsEmpty = arrayList.isEmpty();
        List<Pair<String, Double>> listListOf = arrayList;
        if (zIsEmpty) {
            listListOf = Coll.listOf(Pair.to("s", Double.valueOf(0.0d)));
        }
        return listListOf;
    }
}
