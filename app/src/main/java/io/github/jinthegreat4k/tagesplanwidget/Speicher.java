package io.github.jinthegreat4k.tagesplanwidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Letzter Stand vom Tagesplan (über den Lifehub), Status und Änderungen, die noch gesendet werden müssen. Alles nur auf dem Handy. */
final class Speicher {
    static final String TAG = "Tagesplan";
    private static final Object LOCK = new Object();
    private static final long LAEUFT_MAX_MS = 3 * 60_000L;

    private Speicher() {}

    /* ---------- Daten vom Skript ---------- */

    static JSONObject aufgaben(Context c) {
        return datei(c, "aufgaben.json");
    }

    static void aufgabenSetzen(Context c, JSONObject daten) {
        if (daten == null) return;
        synchronized (LOCK) {
            schreiben(c, "aufgaben.json", daten);
            prefs(c).edit().putLong("standAufgaben", System.currentTimeMillis()).apply();
        }
    }

    /** {von, bis, events, errors} */
    static JSONObject termine(Context c) {
        return datei(c, "termine.json");
    }

    static void termineSetzen(Context c, String von, String bis, JSONObject antwort) {
        try {
            JSONObject j = new JSONObject();
            j.put("von", von);
            j.put("bis", bis);
            j.put("events", antwort.optJSONArray("events") != null ? antwort.optJSONArray("events") : new JSONArray());
            j.put("errors", antwort.optJSONArray("errors") != null ? antwort.optJSONArray("errors") : new JSONArray());
            synchronized (LOCK) {
                schreiben(c, "termine.json", j);
                prefs(c).edit().putLong("standTermine", System.currentTimeMillis()).apply();
            }
        } catch (JSONException e) {
            Log.w(TAG, "Termine nicht gespeichert", e);
        }
    }

    static long standAufgaben(Context c) {
        return prefs(c).getLong("standAufgaben", 0);
    }

    static long standTermine(Context c) {
        return prefs(c).getLong("standTermine", 0);
    }

    /* ---------- Status ---------- */

    /** Fehlercode vom letzten Abgleich, null wenn alles geklappt hat */
    static void fehler(Context c, String code) {
        prefs(c).edit().putString("fehler", code).apply();
    }

    static String fehler(Context c) {
        return prefs(c).getString("fehler", null);
    }

    static void laeuft(Context c, boolean ja) {
        prefs(c).edit().putLong("laeuftSeit", ja ? System.currentTimeMillis() : 0).apply();
    }

    static boolean laeuft(Context c) {
        long t = prefs(c).getLong("laeuftSeit", 0);
        return t > 0 && System.currentTimeMillis() - t < LAEUFT_MAX_MS;
    }

    /* ---------- Abhaken im Widget ---------- */

    static void abhaken(Context c, String id, boolean erledigt) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            long seq = p.getLong("seq", 0) + 1;
            Map<String, Logik.Aenderung> m = aenderungen(c);
            m.put(id, new Logik.Aenderung(erledigt, erledigt ? Logik.heute().toString() : null, seq));
            Map<String, Long> k = kuerzlich(c);
            if (erledigt) k.put(id, System.currentTimeMillis());
            else k.remove(id);
            p.edit().putLong("seq", seq).putString("aenderungen", aenderungenJson(m)).putString("kuerzlich", new JSONObject(k).toString()).apply();
        }
    }

    /** Gespeichert (oder vom Skript abgelehnt): aus der Warteschlange nehmen, falls inzwischen nicht nochmals geändert */
    static void aenderungFertig(Context c, String id, long seq, JSONObject task) {
        synchronized (LOCK) {
            Map<String, Logik.Aenderung> m = aenderungen(c);
            Logik.Aenderung a = m.get(id);
            if (a == null || a.seq != seq) return;
            m.remove(id);
            prefs(c).edit().putString("aenderungen", aenderungenJson(m)).apply();
            // Den gespeicherten Stand gleich nachführen, damit nichts zurückspringt, bevor neu geladen ist
            JSONObject d = aufgaben(c);
            JSONArray ts = d != null ? d.optJSONArray("tasks") : null;
            for (int i = 0; task != null && ts != null && i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t != null && id.equals(t.optString("id"))) {
                    try {
                        ts.put(i, task);
                    } catch (JSONException e) {
                        return;
                    }
                    schreiben(c, "aufgaben.json", d);
                    return;
                }
            }
        }
    }

    static Map<String, Logik.Aenderung> aenderungen(Context c) {
        Map<String, Logik.Aenderung> m = new LinkedHashMap<>();
        try {
            JSONObject j = new JSONObject(prefs(c).getString("aenderungen", "{}"));
            for (Iterator<String> it = j.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject a = j.getJSONObject(id);
                m.put(id, new Logik.Aenderung(a.getBoolean("erledigt"), Logik.str(a, "am"), a.getLong("seq")));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Änderungen nicht lesbar", e);
        }
        return m;
    }

    /** Vor kurzem abgehakt: bleibt eine Weile durchgestrichen sichtbar */
    static Map<String, Long> kuerzlich(Context c) {
        Map<String, Long> m = new HashMap<>();
        long jetzt = System.currentTimeMillis();
        try {
            JSONObject j = new JSONObject(prefs(c).getString("kuerzlich", "{}"));
            for (Iterator<String> it = j.keys(); it.hasNext(); ) {
                String id = it.next();
                long t = j.getLong(id);
                if (jetzt - t < Logik.KUERZLICH_MS) m.put(id, t);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Liste nicht lesbar", e);
        }
        return m;
    }

    private static String aenderungenJson(Map<String, Logik.Aenderung> m) {
        JSONObject j = new JSONObject();
        try {
            for (Map.Entry<String, Logik.Aenderung> e : m.entrySet()) {
                JSONObject a = new JSONObject();
                a.put("erledigt", e.getValue().erledigt);
                a.put("am", e.getValue().erledigtAm != null ? e.getValue().erledigtAm : JSONObject.NULL);
                a.put("seq", e.getValue().seq);
                j.put(e.getKey(), a);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Änderungen nicht gespeichert", e);
        }
        return j.toString();
    }

    /** Beim Trennen, beim Verbinden und wenn der Lifehub die Verbindung entfernt hat: alles vergessen */
    static void allesLoeschen(Context c) {
        synchronized (LOCK) {
            prefs(c).edit().clear().apply();
            new File(c.getFilesDir(), "aufgaben.json").delete();
            new File(c.getFilesDir(), "termine.json").delete();
        }
    }

    /** Für die Einrichtungsseite: neu zeigen, sobald ein Abgleich Stand oder Fehler ändert */
    static void beobachten(Context c, SharedPreferences.OnSharedPreferenceChangeListener l, boolean an) {
        if (an) prefs(c).registerOnSharedPreferenceChangeListener(l);
        else prefs(c).unregisterOnSharedPreferenceChangeListener(l);
    }

    /* ---------- Dateien ---------- */

    private static JSONObject datei(Context c, String name) {
        File f = new File(c.getFilesDir(), name);
        if (!f.exists()) return null;
        try {
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            Log.w(TAG, name + " nicht lesbar", e);
            return null;
        }
    }

    private static void schreiben(Context c, String name, JSONObject j) {
        File tmp = new File(c.getFilesDir(), name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(j.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            Log.w(TAG, name + " nicht gespeichert", e);
            return;
        }
        if (!tmp.renameTo(new File(c.getFilesDir(), name))) Log.w(TAG, name + " nicht ersetzt");
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("daten", Context.MODE_PRIVATE);
    }
}
