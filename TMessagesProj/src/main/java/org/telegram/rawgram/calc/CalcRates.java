package org.telegram.rawgram.calc;

import android.os.SystemClock;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.rawgram.RawCalcConfig;
import org.telegram.rawgram.calc.rates.CurrencyRates;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Currency;
import java.util.HashMap;
import java.util.Locale;

/**
 * Currency rates for the inline calculator. Stars come from the rate the server gives in the app config, and USD is 1.
 * Other currencies need the "online rates" switch: they come from Coinbase's exchange-rates endpoint
 * (the approach of exteraGram's InlineRates, GPLv3), cached for five minutes, one request at a time.
 */
public final class CalcRates implements CurrencyRates {

    private static final String ENDPOINT = "https://api.coinbase.com/v2/exchange-rates?currency=USD";
    private static final long FRESH_MS = 5 * 60 * 1000L;
    private static final long RETRY_MS = 30 * 1000L;

    // code -> units of that currency per 1 USD; replaced as a whole, read from any thread
    private static volatile HashMap<String, Double> table;
    private static volatile long tableTime;
    private static boolean requesting;
    private static long requestedAt;

    private final Runnable onUpdated;
    private String localCurrency;
    private Locale localeOfCurrency;

    public CalcRates(Runnable onUpdated) {
        this.onUpdated = onUpdated;
    }

    /** TON is called GRAM inside the parser. */
    private static String rateCode(String code) {
        return "GRAM".equals(code) ? "TON" : code;
    }

    @Override
    public boolean supports(String code) {
        if ("USD".equals(code)) {
            return true;
        }
        if ("XTR".equals(code)) {
            return starsRate() > 0;
        }
        if (!RawCalcConfig.onlineRates.get()) {
            return false;
        }
        HashMap<String, Double> rates = table;
        if (rates == null) {
            request(RETRY_MS);
            return false;
        }
        return rates.containsKey(rateCode(code));
    }

    @Override
    public Double rate(String from, String to) {
        Double fromUsd = usdRate(from);
        Double toUsd = usdRate(to);
        if (fromUsd == null || toUsd == null || toUsd == 0.0) {
            return null;
        }
        return fromUsd / toUsd;
    }

    private static double starsRate() {
        float rate1000 = MessagesController.getInstance(UserConfig.selectedAccount).starsUsdSellRate1000;
        return rate1000 > 0 ? rate1000 / 100000.0 : 0;
    }

    private Double usdRate(String code) {
        if ("USD".equals(code)) {
            return 1.0;
        }
        if ("XTR".equals(code)) {
            double rate = starsRate();
            return rate > 0 ? rate : null;
        }
        HashMap<String, Double> rates = table;
        if (!RawCalcConfig.onlineRates.get() || rates == null) {
            return null;
        }
        Double perUsd = rates.get(rateCode(code));
        if (perUsd == null || perUsd == 0.0) {
            return null;
        }
        if (SystemClock.elapsedRealtime() - tableTime > FRESH_MS) {
            request(RETRY_MS);
        }
        return 1.0 / perUsd;
    }

    /** The account's currency by the system locale, when it can be converted to. */
    public String localCurrency() {
        Locale locale = LocaleController.getInstance().getSystemDefaultLocale();
        if (locale == null) {
            return null;
        }
        if (!locale.equals(localeOfCurrency)) {
            localeOfCurrency = locale;
            localCurrency = null;
            try {
                localCurrency = Currency.getInstance(locale).getCurrencyCode();
            } catch (Exception ignore) {
            }
        }
        return localCurrency != null && supports(localCurrency) ? localCurrency : null;
    }

    private void request(long interval) {
        long now = SystemClock.elapsedRealtime();
        synchronized (CalcRates.class) {
            if (requesting || (requestedAt != 0 && now - requestedAt < interval)) {
                return;
            }
            requesting = true;
            requestedAt = now;
        }
        Utilities.globalQueue.postRunnable(() -> {
            HashMap<String, Double> fetched = null;
            try {
                fetched = fetch();
            } catch (Throwable ignore) {
            }
            if (fetched != null && !fetched.isEmpty()) {
                table = fetched;
                tableTime = SystemClock.elapsedRealtime();
            }
            synchronized (CalcRates.class) {
                requesting = false;
            }
            if (fetched != null) {
                AndroidUtilities.runOnUIThread(onUpdated);
            }
        });
    }

    private static HashMap<String, Double> fetch() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != 200) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0 && out.size() < 512 * 1024) {
                    out.write(buffer, 0, read);
                }
            }
            JSONObject rates = new JSONObject(out.toString("UTF-8")).getJSONObject("data").getJSONObject("rates");
            HashMap<String, Double> result = new HashMap<>();
            java.util.Iterator<String> keys = rates.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                double value = Double.parseDouble(rates.getString(key));
                if (value > 0) {
                    result.put(key, value);
                }
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }
}
