package io.github.jinthegreat4k.tagesplanwidget;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.regex.Pattern;

/** Adresse und Schlüssel des Google-Skripts. Liegen nur in den privaten Daten der App. */
final class Konfig {
    static final String APP_URL = "https://lauener.online/plan/";
    static final Pattern URL_RE = Pattern.compile("^https://script\\.google\\.com/(?:a/macros/[^/?#]+|macros)/s/[\\w-]+/exec$");
    // Nur für den Test auf dem Emulator (Debug-Version): nachgebildetes Skript auf dem Rechner
    static final Pattern TEST_URL_RE = Pattern.compile("^http://10\\.0\\.2\\.2:\\d{2,5}/macros/s/[\\w-]+/exec$");

    final String url;
    final String key;

    Konfig(String url, String key) {
        this.url = url;
        this.key = key;
    }

    static Konfig lesen(Context c) {
        SharedPreferences p = prefs(c);
        String u = p.getString("url", null), k = p.getString("key", null);
        return u != null && k != null ? new Konfig(u, k) : null;
    }

    static boolean da(Context c) {
        return lesen(c) != null;
    }

    static void speichern(Context c, Konfig k) {
        prefs(c).edit().putString("url", k.url).putString("key", k.key).apply();
    }

    static void loeschen(Context c) {
        prefs(c).edit().clear().apply();
    }

    /** null, wenn alles stimmt, sonst der Grund */
    static String pruefen(String url, String key) {
        if (url == null || !(URL_RE.matcher(url).matches() || (BuildConfig.DEBUG && TEST_URL_RE.matcher(url).matches()))) {
            return "Die Adresse muss mit https://script.google.com/ beginnen und auf /exec enden.";
        }
        if (key == null || key.length() < 20) return "Der Schlüssel ist zu kurz.";
        return null;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("konfig", Context.MODE_PRIVATE);
    }
}
