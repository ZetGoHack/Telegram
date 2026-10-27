package org.telegram.rawgram.calc;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.rawgram.calc.math.MathExpression;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The online provider: https://calcmula.app/api/calc (GET, "q" = the expression, JSON back, "result.formatted").
 * It sends the typed expression to a third-party server, so it is opt-in. Requests are debounced, at most one is in flight,
 * a stale one is dropped, the last answers are kept in a small cache, and any failure just means "no answer".
 */
public final class RawCalcmula implements RawCalcProvider {

    private static final String ENDPOINT = "https://calcmula.app/api/calc?q=";
    private static final long DEBOUNCE_MS = 350;
    private static final int TIMEOUT_MS = 3000;
    private static final int MAX_QUERY = 200;
    private static final int MAX_RESPONSE = 32 * 1024;
    private static final String NONE = "";
    private static final String FAILED = "\u0000";
    private static final java.util.regex.Pattern WORDS = java.util.regex.Pattern.compile(
            "(?iu)(?<![\\p{L}])(today|tomorrow|yesterday|now|сегодня|завтра|вчера|послезавтра|позавчера|сейчас|сьогодні|вчора|зараз|післязавтра|позавчора)(?![\\p{L}])");

    /** expression -> answer ("" = the server has none); touched on the UI thread only */
    private final Map<String, String> cache = new LinkedHashMap<String, String>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Entry<String, String> eldest) {
            return size() > 48;
        }
    };

    private static DispatchQueue queue;

    private int generation;
    private Runnable pending;
    private volatile HttpURLConnection running;

    @Override
    public boolean isAsync() {
        return true;
    }

    @Override
    public void evaluate(CharSequence text, int caret, Callback callback) {
        cancel();
        String expression = MathExpression.INSTANCE.expressionAt(text, caret);
        if (expression == null) {
            expression = wordsExpressionAt(text, caret);
        }
        if (expression == null || expression.length() > MAX_QUERY || looksPrivate(expression)) {
            callback.onResult(null);
            return;
        }
        String cached = cache.get(expression);
        if (cached != null) {
            if (cached.equals(FAILED)) {
                callback.onError();
            } else {
                callback.onResult(cached.isEmpty() ? null : cached);
            }
            return;
        }
        callback.onLoading();
        final int mine = ++generation;
        pending = () -> {
            pending = null;
            if (mine != generation) {
                return;
            }
            send(expression, mine, callback);
        };
        AndroidUtilities.runOnUIThread(pending, DEBOUNCE_MS);
    }

    @Override
    public void cancel() {
        generation++;
        if (pending != null) {
            AndroidUtilities.cancelRunOnUIThread(pending);
            pending = null;
        }
        HttpURLConnection connection = running;
        if (connection != null) {
            connection.disconnect();
        }
    }

    /** «сегодня - вчера =»: the date words need no digits, but one of them must be there. */
    private static String wordsExpressionAt(CharSequence text, int caret) {
        if (caret < 2 || caret > text.length() || text.charAt(caret - 1) != '=') {
            return null;
        }
        int start = caret - 1;
        while (start > 0 && caret - 1 - start < MAX_QUERY) {
            char c = text.charAt(start - 1);
            if (!(Character.isLetterOrDigit(c) || " \t.:+-*/()".indexOf(c) >= 0)) {
                break;
            }
            start--;
        }
        String expression = text.subSequence(start, caret - 1).toString().trim();
        return expression.length() >= 3 && WORDS.matcher(expression).find() ? expression : null;
    }

    private static boolean looksPrivate(String expression) {
        return expression.indexOf('@') >= 0 || expression.contains("://") || expression.contains("www.");
    }

    private void send(String expression, int mine, Callback callback) {
        if (queue == null) {
            queue = new DispatchQueue("rawCalcmula");
        }
        queue.postRunnable(() -> {
            if (mine != generation) {
                return;
            }
            String answer = null;
            boolean definite = false; // a reply from the server, not a network failure
            boolean failed = true;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(ENDPOINT + URLEncoder.encode(expression, "UTF-8")).openConnection();
                running = connection;
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setRequestProperty("Accept", "application/json");
                int code = connection.getResponseCode();
                if (code == 200) {
                    answer = parse(read(connection.getInputStream()), expression);
                    definite = true;
                    failed = false;
                } else if (code == 400) {
                    definite = true;
                }
            } catch (Throwable ignore) {
            } finally {
                running = null;
                if (connection != null) {
                    connection.disconnect();
                }
            }
            final String result = answer;
            final boolean remember = definite;
            final boolean error = failed;
            AndroidUtilities.runOnUIThread(() -> {
                if (remember) {
                    cache.put(expression, error ? FAILED : result == null ? NONE : result);
                }
                if (mine == generation) {
                    if (error) {
                        callback.onError();
                    } else {
                        callback.onResult(result);
                    }
                }
            });
        });
    }

    private static String read(InputStream stream) throws Exception {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0 && out.size() < MAX_RESPONSE) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    private static String parse(String body, String expression) {
        try {
            JSONObject json = new JSONObject(body);
            if (!json.optBoolean("ok")) {
                return null;
            }
            JSONObject result = json.optJSONObject("result");
            String formatted = result == null ? null : result.optString("formatted", null);
            if (formatted == null) {
                return null;
            }
            formatted = formatted.trim();
            if (formatted.isEmpty() || formatted.length() > 64 || formatted.indexOf('\n') >= 0 || formatted.equals(expression)) {
                return null;
            }
            return formatted;
        } catch (Exception e) {
            return null;
        }
    }
}
