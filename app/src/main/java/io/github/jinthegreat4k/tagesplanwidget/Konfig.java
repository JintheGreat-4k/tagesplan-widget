package io.github.jinthegreat4k.tagesplanwidget;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Token der Widget-App für den Lifehub und die Adresse, zu der er gehört. Liegen nur in den privaten Daten der App
 * (kein Backup, kein Umzug aufs neue Handy: res/xml/datenregeln.xml). Der Token taugt nur für Laden, Termine und Abhaken
 * und lässt sich im Lifehub jederzeit entfernen.
 */
final class Konfig {
    static final String APP_URL = "https://lauener.online/plan/";
    private static final Object LOCK = new Object();

    final String token;
    final String basis;

    Konfig(String token, String basis) {
        this.token = token;
        this.basis = basis;
    }

    /** null, wenn nicht verbunden. Räumt bei jedem Aufruf weg, was 1.0/1.1 gespeichert hat (Adresse und Schlüssel des Google-Skripts). */
    static Konfig lesen(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains("url") || p.contains("key")) {
            p.edit().remove("url").remove("key").apply();
            Speicher.allesLoeschen(c);
        }
        String t = p.getString("token", null), b = p.getString("basis", null);
        return t != null && Api.TOKEN.matcher(t).matches() && Api.basisErlaubt(b, BuildConfig.DEBUG) ? new Konfig(t, b) : null;
    }

    static boolean da(Context c) {
        return lesen(c) != null;
    }

    /** Gleich auf den Speicher schreiben (läuft im Hintergrund): Der Widget-Code ist schon verbraucht. */
    static void speichern(Context c, Konfig k) {
        synchronized (LOCK) {
            prefs(c).edit().putString("token", k.token).putString("basis", k.basis).remove("getrennt").commit();
        }
    }

    /**
     * «Verbindung trennen» in der App. Setzt bewusst nicht «getrennt»: Das heisst «im Lifehub entfernt» (Widgets und App
     * zeigen dann «Getrennt»); nach dem Trennen in der App zeigen sie «Noch nicht verbunden».
     */
    static void loeschen(Context c) {
        synchronized (LOCK) {
            prefs(c).edit().remove("token").remove("basis").remove("getrennt").apply();
        }
    }

    /** Ist noch genau dieser Token gespeichert? Nein, wenn inzwischen getrennt oder neu verbunden wurde. */
    static boolean gilt(Context c, String token) {
        return token != null && token.equals(prefs(c).getString("token", null));
    }

    /**
     * Der Lifehub kennt diesen Token nicht mehr (401): vergessen und «getrennt» merken. Nur wenn noch genau dieser Token
     * gespeichert ist; sonst wurde inzwischen neu verbunden. true, wenn die Verbindung damit weg ist.
     */
    static boolean abmelden(Context c, String token) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            if (!token.equals(p.getString("token", null))) return false;
            p.edit().remove("token").remove("basis").putBoolean("getrennt", true).commit();
            return true;
        }
    }

    /** Im Lifehub entfernt (oder überall abgemeldet), seither nicht neu verbunden */
    static boolean getrennt(Context c) {
        return prefs(c).getBoolean("getrennt", false);
    }

    /** Nur den Hash des zuletzt benutzten Widget-Codes: Derselbe Link nochmals (z. B. aus dem Verlauf) fragt nicht nochmals. */
    static void codeMerken(Context c, String code) {
        prefs(c).edit().putString("letzterCode", Api.codeHash(code)).apply();
    }

    static boolean codeBenutzt(Context c, String code) {
        return Api.codeHash(code).equals(prefs(c).getString("letzterCode", null));
    }

    /** Für die Einrichtungsseite: neu zeigen, sobald sich die Verbindung ändert (auch durch den Abgleich im Hintergrund) */
    static void beobachten(Context c, SharedPreferences.OnSharedPreferenceChangeListener l, boolean an) {
        if (an) prefs(c).registerOnSharedPreferenceChangeListener(l);
        else prefs(c).unregisterOnSharedPreferenceChangeListener(l);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("konfig", Context.MODE_PRIVATE);
    }
}
