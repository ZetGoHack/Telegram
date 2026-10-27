package org.telegram.rawgram.calc.time;

import java.util.Objects;
import org.telegram.rawgram.calc.Coll;
import org.telegram.rawgram.calc.Pair;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;



public final class TimeZones {
    public static final TimeZones INSTANCE = new TimeZones();
    private static final Set<String> targetMarkers = Coll.setOf(new String[]{"в", "во", "to", "in"});
    private static final Set<String> sourceMarkers = Coll.setOf("по");
    private static final Set<String> nowWords = Coll.setOf(new String[]{"сейчас", "now"});
    private static final Set<String> meridiems = Coll.setOf(new String[]{"am", "pm", "a.m.", "p.m."});
    private static final Pattern timePattern = Pattern.compile("^(\\d{1,2})(?:[:.](\\d{2}))?(am|pm|a\\.m\\.|p\\.m\\.)?$");
    private static final Pattern offsetPattern = Pattern.compile("^(utc|gmt|msk|мск) ?([+-]) ?(\\d{1,2})(?::?(\\d{2}))?$");
    private static final Pattern SPLIT = Pattern.compile("[ \t\u00a0]");
    private static final HashMap<String, String> zones = new HashMap<>();

    private TimeZones() {
    }

    static {
        addZone("UTC", "utc");
        addZone("GMT", "gmt");
        addZone("Europe/Moscow", "msk", "мск", "москва", "москве", "moscow", "питер", "питере", "спб", "петербург", "петербурге", "санкт-петербург", "санкт-петербурге", "st petersburg", "saint petersburg");
        addZone("Europe/Kaliningrad", "калининград", "калининграде", "kaliningrad");
        addZone("Europe/Samara", "самара", "самаре", "samara");
        addZone("Asia/Yekaterinburg", "екб", "екатеринбург", "екатеринбурге", "yekaterinburg");
        addZone("Asia/Omsk", "омск", "омске", "omsk");
        addZone("Asia/Novosibirsk", "нск", "новосибирск", "новосибирске", "novosibirsk");
        addZone("Asia/Krasnoyarsk", "красноярск", "красноярске", "krasnoyarsk");
        addZone("Asia/Irkutsk", "иркутск", "иркутске", "irkutsk");
        addZone("Asia/Yakutsk", "якутск", "якутске", "yakutsk");
        addZone("Asia/Vladivostok", "владивосток", "владивостоке", "vladivostok");
        addZone("Asia/Magadan", "магадан", "магадане", "magadan");
        addZone("Asia/Kamchatka", "камчатка", "камчатке", "kamchatka");
        addZone("Europe/Kiev", "киев", "киеве", "київ", "києві", "kyiv", "kiev");
        addZone("Europe/Minsk", "минск", "минске", "minsk");
        addZone("Asia/Almaty", "алматы", "almaty", "астана", "астане", "astana");
        addZone("Asia/Tashkent", "ташкент", "ташкенте", "tashkent");
        addZone("Asia/Bishkek", "бишкек", "бишкеке", "bishkek");
        addZone("Asia/Dushanbe", "душанбе", "dushanbe");
        addZone("Asia/Tbilisi", "тбилиси", "tbilisi");
        addZone("Asia/Yerevan", "ереван", "ереване", "yerevan");
        addZone("Asia/Baku", "баку", "baku");
        addZone("Europe/Chisinau", "кишинев", "кишиневе", "chisinau");
        addZone("Europe/Riga", "рига", "риге", "riga");
        addZone("Europe/Vilnius", "вильнюс", "вильнюсе", "vilnius");
        addZone("Europe/Tallinn", "таллин", "таллине", "tallinn");
        addZone("Europe/Helsinki", "eet", "eest", "хельсинки", "helsinki");
        addZone("Europe/Warsaw", "варшава", "варшаве", "warsaw");
        addZone("Europe/Prague", "прага", "праге", "prague");
        addZone("Europe/Berlin", "cet", "cest", "берлин", "берлине", "berlin");
        addZone("Europe/Paris", "париж", "париже", "paris");
        addZone("Europe/Rome", "рим", "риме", "rome");
        addZone("Europe/Madrid", "мадрид", "мадриде", "madrid", "барселона", "барселоне", "barcelona");
        addZone("Europe/Amsterdam", "амстердам", "амстердаме", "amsterdam");
        addZone("Europe/London", "bst", "лондон", "лондоне", "london");
        addZone("Europe/Lisbon", "wet", "west", "лиссабон", "лиссабоне", "lisbon");
        addZone("Europe/Istanbul", "стамбул", "стамбуле", "istanbul", "анталья", "анталье", "antalya");
        addZone("Asia/Jerusalem", "тель-авив", "тель-авиве", "tel aviv", "иерусалим", "иерусалиме", "jerusalem");
        addZone("Asia/Dubai", "дубай", "дубае", "dubai");
        addZone("Africa/Cairo", "каир", "каире", "cairo");
        addZone("Asia/Kolkata", "ist", "дели", "delhi", "new delhi", "мумбаи", "mumbai");
        addZone("Asia/Bangkok", "бангкок", "бангкоке", "bangkok", "пхукет", "пхукете", "phuket");
        addZone("Asia/Makassar", "бали", "bali");
        addZone("Asia/Singapore", "sgt", "сингапур", "сингапуре", "singapore");
        addZone("Asia/Hong_Kong", "hkt", "гонконг", "гонконге", "hong kong");
        addZone("Asia/Shanghai", "пекин", "пекине", "beijing", "шанхай", "шанхае", "shanghai");
        addZone("Asia/Seoul", "kst", "сеул", "сеуле", "seoul");
        addZone("Asia/Tokyo", "jst", "токио", "tokyo");
        addZone("Australia/Perth", "awst", "перт", "перте", "perth");
        addZone("Australia/Sydney", "aest", "aedt", "сидней", "сиднее", "sydney");
        addZone("Australia/Melbourne", "мельбурн", "мельбурне", "melbourne");
        addZone("Pacific/Auckland", "nzst", "nzdt", "окленд", "окленде", "auckland");
        addZone("America/New_York", "est", "edt", "нью-йорк", "нью-йорке", "new york", "nyc", "вашингтон", "вашингтоне", "washington", "майами", "miami");
        addZone("America/Toronto", "торонто", "toronto");
        addZone("America/Chicago", "cst", "cdt", "чикаго", "chicago");
        addZone("America/Denver", "mst", "mdt", "денвер", "денвере", "denver");
        addZone("America/Los_Angeles", "pst", "pdt", "лос-анджелес", "лос-анджелесе", "los angeles", "сан-франциско", "san francisco", "сиэтл", "сиэтле", "seattle");
        addZone("America/Vancouver", "ванкувер", "ванкувере", "vancouver");
        addZone("America/Anchorage", "akst", "akdt", "анкоридж", "анкоридже", "anchorage");
        addZone("Pacific/Honolulu", "hst", "гонолулу", "honolulu");
        addZone("America/Mexico_City", "мехико", "mexico city");
        addZone("America/Sao_Paulo", "сан-паулу", "sao paulo");
        addZone("America/Argentina/Buenos_Aires", "буэнос-айрес", "буэнос-айресе", "buenos aires");
    }

    public final String valueAt(CharSequence text, int equalsIndex, TimeZoneOptions options) {
        ZoneId zoneIdZone;
        ZoneId zoneIdZone2;
        String window = text.subSequence(Math.max(0, equalsIndex - 64), equalsIndex).toString().toLowerCase(Locale.ROOT).replace('\u0451', '\u0435').replace('\u2212', '-');
        List<String> arrayList = new ArrayList<>();
        for (String word : SPLIT.split(window)) {
            if (word.length() > 0) {
                arrayList.add(word);
            }
        }
        int size = arrayList.size();
        int i = 3;
        while (true) {
            if (i <= 0) {
                zoneIdZone = null;
                break;
            }
            int i2 = size - i;
            int i3 = i2 - 1;
            if (i3 >= 0 && targetMarkers.contains(arrayList.get(i3)) && (zoneIdZone = zone(arrayList, i2, size)) != null) {
                size -= i + 1;
                break;
            }
            i--;
        }
        int i4 = 3;
        while (true) {
            if (i4 <= 0) {
                zoneIdZone2 = null;
                break;
            }
            int i5 = size - i4;
            if (i5 >= 1 && (zoneIdZone2 = zone(arrayList, i5, size)) != null) {
                if (sourceMarkers.contains(arrayList.get(i5 - 1)) && i5 > 1) {
                    i5--;
                }
                size = i5;
                break;
            }
            i4--;
        }
        if (size < 1 || (zoneIdZone2 == null && zoneIdZone == null)) {
            return null;
        }
        String str = (String) arrayList.get(size - 1);
        if (meridiems.contains(str) && size >= 2) {
            str = arrayList.get(size - 2) + str;
        }
        ZoneId local = options.getLocal();
        ZonedDateTime now = options.getNow();
        if (now == null) {
            now = ZonedDateTime.now(local);
        }
        if (nowWords.contains(str)) {
            if (zoneIdZone == null || zoneIdZone2 != null) {
                return null;
            }
            return format(now.withZoneSameInstant(zoneIdZone).toLocalTime(), null);
        }
        Matcher matchResultMatchEntire = timePattern.matcher(str);
        if (!matchResultMatchEntire.matches()) {
            return null;
        }
        int i6 = Integer.parseInt(matchResultMatchEntire.group(1));
        String str2 = group(matchResultMatchEntire, 2);
        String str3 = group(matchResultMatchEntire, 3);
        if (str3.length() == 0) {
            str3 = null;
        }
        String str4 = str3;
        if (str2.length() == 0 && str4 == null && zoneIdZone2 == null) {
            return null;
        }
        int i7 = str2.length() == 0 ? 0 : Integer.parseInt(str2);
        if (i7 > 59) {
            return null;
        }
        if (str4 != null) {
            if (1 > i6 || i6 >= 13) {
                return null;
            }
            i6 = (i6 % 12) + (str4.startsWith("p") ? 12 : 0);
        } else if (i6 > 23) {
            return null;
        }
        if (zoneIdZone2 == null) {
            zoneIdZone2 = local;
        }
        if (zoneIdZone == null) {
            zoneIdZone = local;
        }
        ZonedDateTime zonedDateTimeOf = ZonedDateTime.of(now.withZoneSameInstant(zoneIdZone2).toLocalDate(), LocalTime.of(i6, i7), zoneIdZone2);
        ZonedDateTime zonedDateTimeO = zonedDateTimeOf.withZoneSameInstant(zoneIdZone);
        if (Objects.equals(zonedDateTimeO.getOffset(), zonedDateTimeOf.getOffset())) {
            return null;
        }
        long epochDay = zonedDateTimeO.toLocalDate().toEpochDay() - zonedDateTimeOf.toLocalDate().toEpochDay();
        String str5 = format(zonedDateTimeO.toLocalTime(), str4);
        if (epochDay > 0) {
            return str5 + " (+" + epochDay + ")";
        }
        if (epochDay >= 0) {
            return str5;
        }
        return str5 + " (" + epochDay + ")";
    }

    private final String format(LocalTime time, String meridiem) {
        String strPadStart = pad2(time.getMinute());
        if (meridiem == null) {
            return pad2(time.getHour()) + ":" + strPadStart;
        }
        return (((time.getHour() + 11) % 12) + 1) + ":" + strPadStart + " " + (time.getHour() < 12 ? "am" : "pm");
    }

    private static String join(List<String> words, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) {
                sb.append(' ');
            }
            sb.append(words.get(i));
        }
        return sb.toString();
    }

    private static String pad2(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    private static String group(Matcher matcher, int index) {
        String value = matcher.group(index);
        return value == null ? "" : value;
    }

    private final ZoneId zone(List<String> words, int from, int to) {
        String joined = join(words, from, to);
        String str = zones.get(normalize(joined));
        if (str != null) {
            try {
                return ZoneId.of(str);
            } catch (DateTimeException unused) {
                return null;
            }
        }
        Matcher matchResultMatchEntire = offsetPattern.matcher(joined);
        if (!matchResultMatchEntire.matches()) {
            return null;
        }
        int i = (Objects.equals(group(matchResultMatchEntire, 1), "msk") || Objects.equals(group(matchResultMatchEntire, 1), "мск")) ? 180 : 0;
        int i2 = Integer.parseInt(group(matchResultMatchEntire, 3));
        String str2 = group(matchResultMatchEntire, 4);
        if (str2.length() == 0) {
            str2 = "0";
        }
        int i3 = Integer.parseInt(str2);
        if (i2 <= 18 && i3 <= 59) {
            try {
                return ZoneOffset.ofTotalSeconds((i + ((Objects.equals(group(matchResultMatchEntire, 2), "-") ? -1 : 1) * ((i2 * 60) + i3))) * 60);
            } catch (DateTimeException unused2) {
            }
        }
        return null;
    }

    private final String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replace('\u0451', '\u0435').replace('-', ' ');
    }

    private static void addZone(String str, String... strArr) {
        for (String str2 : strArr) {
            zones.put(INSTANCE.normalize(str2), str);
        }
    }
}