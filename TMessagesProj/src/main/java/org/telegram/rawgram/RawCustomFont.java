package org.telegram.rawgram;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * «Свой шрифт» (prototype): a .ttf / .otf picked by the user replaces Telegram's fonts.
 * <ul>
 * <li>Telegram's bundled fonts (medium / bold titles, italic, extra bold) go through AndroidUtilities.getTypeface,
 * already hooked by RawTypeface — that part is reliable.</li>
 * <li>Regular text uses the platform default typeface, not an asset. At startup {@link #applyDefault()} tries to
 * replace it by reflection (Typeface.DEFAULT / SANS_SERIF, sDefaults, setDefault, sSystemFontMap); newer Android may
 * block some of these (hidden API), so every attempt is recorded and shown in the settings ({@link #status()}).</li>
 * </ul>
 * The medium weight comes from a separate bold file if one is picked, else from the font's own 'wght' axis (variable
 * fonts), else Android's synthetic bold. Applies after a restart.
 */
public final class RawCustomFont {

    public static final int REQUEST_REGULAR = 7701;
    public static final int REQUEST_BOLD = 7702;

    private static final String DIR = "rawgram_fonts";
    private static final String REGULAR = "regular.ttf";
    private static final String BOLD = "bold.ttf";

    private static boolean loaded;
    private static Typeface regular, bold, italic, boldItalic, extraBold;
    private static String status = "";

    private RawCustomFont() {
    }

    public static boolean active() {
        return RawUiConfig.fontMode() == RawUiConfig.FONT_CUSTOM && regularFile().exists();
    }

    private static File dir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), DIR);
        dir.mkdirs();
        return dir;
    }

    public static File regularFile() {
        return new File(dir(), REGULAR);
    }

    public static File boldFile() {
        return new File(dir(), BOLD);
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            regular = Typeface.createFromFile(regularFile());
        } catch (Throwable e) {
            FileLog.e(e);
            regular = null;
            return;
        }
        if (boldFile().exists()) {
            try {
                bold = Typeface.createFromFile(boldFile());
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        if (bold == null) {
            bold = variableWeight(600);
        }
        if (bold == null) {
            bold = Typeface.create(regular, Typeface.BOLD);
        }
        extraBold = variableWeight(800);
        if (extraBold == null) {
            extraBold = Typeface.create(bold, Typeface.BOLD);
        }
        italic = Typeface.create(regular, Typeface.ITALIC);
        boldItalic = Typeface.create(bold, Typeface.ITALIC);
    }

    /** The regular file at another weight of its 'wght' axis, if it is a variable font and the weight looks different. */
    private static Typeface variableWeight(int weight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }
        try {
            Typeface t = new Typeface.Builder(regularFile()).setFontVariationSettings("'wght' " + weight).build();
            return t != null && RawSystemFonts.rendersDifferently(regular, t) ? t : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Typeface for one of Telegram's bundled assets, or null to keep it (monospace) or when not active. */
    public static Typeface forAsset(String assetPath) {
        if (!active()) {
            return null;
        }
        load();
        if (regular == null) {
            return null;
        }
        switch (assetPath) {
            case "fonts/rregular.ttf":
                return regular;
            case "fonts/ritalic.ttf":
                return italic;
            case AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM:
            case "fonts/rcondensedbold.ttf":
                return bold;
            case AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM_ITALIC:
                return boldItalic;
            case AndroidUtilities.TYPEFACE_ROBOTO_EXTRA_BOLD:
                return extraBold;
            default:
                return null;
        }
    }

    // ---- regular text: the platform default ----

    /** ApplicationLoader.onCreate, before any text is drawn: makes the custom font the default typeface. */
    public static void applyDefault() {
        if (!active()) {
            return;
        }
        load();
        if (regular == null) {
            status = "Файл шрифта не открылся";
            return;
        }
        StringBuilder ok = new StringBuilder();
        StringBuilder failed = new StringBuilder();
        attempt("DEFAULT", () -> setStatic("DEFAULT", regular), ok, failed);
        attempt("SANS_SERIF", () -> setStatic("SANS_SERIF", regular), ok, failed);
        attempt("DEFAULT_BOLD", () -> setStatic("DEFAULT_BOLD", bold), ok, failed);
        attempt("sDefaults", () -> setStatic("sDefaults", new Typeface[]{regular, bold, italic, boldItalic}), ok, failed);
        attempt("setDefault", () -> {
            Method m = Typeface.class.getDeclaredMethod("setDefault", Typeface.class);
            m.setAccessible(true);
            m.invoke(null, regular);
        }, ok, failed);
        attempt("sSystemFontMap", RawCustomFont::replaceSystemFontMap, ok, failed);
        status = "Применено: " + (ok.length() > 0 ? ok : "—") + (failed.length() > 0 ? ". Не удалось: " + failed : "");
        FileLog.d("rawGram custom font: " + status);
    }

    private interface Step {
        void run() throws Throwable;
    }

    private static void attempt(String name, Step step, StringBuilder ok, StringBuilder failed) {
        try {
            step.run();
            ok.append(ok.length() > 0 ? ", " : "").append(name);
        } catch (Throwable e) {
            failed.append(failed.length() > 0 ? ", " : "").append(name);
            FileLog.e("rawGram custom font: " + name, e);
        }
    }

    private static void setStatic(String fieldName, Object value) throws Throwable {
        Field field = Typeface.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    @SuppressWarnings("unchecked")
    private static void replaceSystemFontMap() throws Throwable {
        Field field = Typeface.class.getDeclaredField("sSystemFontMap");
        field.setAccessible(true);
        Map<String, Typeface> current = (Map<String, Typeface>) field.get(null);
        Map<String, Typeface> map = current != null ? new HashMap<>(current) : new HashMap<>();
        map.put("sans-serif", regular);
        map.put("sans-serif-medium", bold);
        map.put("roboto", regular);
        try {
            field.set(null, map);
        } catch (Throwable notReplaceable) {
            if (current == null) {
                throw notReplaceable;
            }
            current.putAll(map);
        }
    }

    /** Result of {@link #applyDefault()} for the settings info line; empty when it hasn't run. */
    public static String status() {
        return status;
    }

    // ---- picking the files ----

    public static void pick(BaseFragment fragment, int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            fragment.startActivityForResult(intent, request);
        } catch (Exception e) {
            RawNotify.show(R.drawable.msg_warning, "Не удалось открыть выбор файла");
        }
    }

    /** The host fragment's onActivityResultFragment; {@code onSaved} runs on the UI thread after a valid font is stored. */
    public static boolean onActivityResult(int requestCode, int resultCode, Intent data, Runnable onSaved) {
        if (requestCode != REQUEST_REGULAR && requestCode != REQUEST_BOLD) {
            return false;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        final Uri uri = data.getData();
        final File target = requestCode == REQUEST_REGULAR ? regularFile() : boldFile();
        Utilities.globalQueue.postRunnable(() -> {
            File tmp = new File(dir(), target.getName() + ".tmp");
            boolean valid = false;
            try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (in != null) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                    valid = true;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (valid) {
                try {
                    Typeface probe = Typeface.createFromFile(tmp);
                    valid = probe != null && probe != Typeface.DEFAULT;
                } catch (Throwable e) {
                    valid = false;
                }
            }
            final boolean saved = valid && tmp.renameTo(target) || valid && copyOver(tmp, target);
            tmp.delete();
            AndroidUtilities.runOnUIThread(() -> {
                if (saved) {
                    if (onSaved != null) {
                        onSaved.run();
                    }
                } else {
                    RawNotify.show(R.drawable.msg_warning, "Файл не похож на шрифт");
                }
            });
        });
        return true;
    }

    private static boolean copyOver(File from, File to) {
        to.delete();
        return from.renameTo(to);
    }

    public static void removeBold() {
        boldFile().delete();
    }
}
