package io.github.jinthegreat4k.tagesplanwidget;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.Map;

/** Ein Abgleich: zuerst Abgehaktes senden, dann Aufgaben und Termine laden. Läuft nie im Haupt-Thread. */
final class Sync {
    private static final Object LOCK = new Object();

    private Sync() {}

    /** true, wenn Änderungen wegen eines Netzfehlers noch warten (dann später nochmals versuchen) */
    static boolean ausfuehren(Context c) {
        synchronized (LOCK) {
            Konfig k = Konfig.lesen(c);
            if (k == null) return false;
            Speicher.laeuft(c, true);
            Darstellung.alle(c);
            String fehler = null;
            boolean wartet = false;
            try {
                for (Map.Entry<String, Logik.Aenderung> e : Speicher.aenderungen(c).entrySet()) {
                    Logik.Aenderung a = e.getValue();
                    JSONObject f = new JSONObject();
                    f.put("done", a.erledigt);
                    f.put("doneAt", a.erledigtAm != null ? a.erledigtAm : JSONObject.NULL);
                    JSONObject req = new JSONObject();
                    req.put("id", e.getKey());
                    req.put("fields", f);
                    try {
                        JSONObject r = Api.post(k, "update", req);
                        Speicher.aenderungFertig(c, e.getKey(), a.seq, r.optJSONObject("task"));
                    } catch (Api.Fehler x) {
                        // Aufgabe gibt es nicht mehr (z. B. in der Tabelle gelöscht): nicht endlos wiederholen
                        if ("server".equals(x.code) && x.detail != null && x.detail.contains("nicht gefunden")) {
                            Log.w(Speicher.TAG, "Änderung verworfen: " + x.detail);
                            Speicher.aenderungFertig(c, e.getKey(), a.seq, null);
                            continue;
                        }
                        fehler = x.code;
                        wartet = true;
                        break;
                    }
                }
                if (!wartet) {
                    try {
                        JSONObject r = Api.post(k, "load", null);
                        Speicher.aufgabenSetzen(c, r.optJSONObject("data"));
                    } catch (Api.Fehler x) {
                        fehler = x.code;
                    }
                    if (!"unauthorized".equals(fehler)) {
                        LocalDate h = Logik.heute();
                        String von = h.toString(), bis = h.plusDays(Logik.KALENDER_TAGE - 1).toString();
                        JSONObject req = new JSONObject();
                        req.put("von", von);
                        req.put("bis", bis);
                        try {
                            Speicher.termineSetzen(c, von, bis, Api.post(k, "termine", req));
                        } catch (Api.Fehler x) {
                            if (fehler == null) fehler = x.code;
                        }
                    }
                }
            } catch (JSONException e) {
                fehler = "bad_request";
            } finally {
                if (fehler != null) Log.w(Speicher.TAG, "Abgleich: " + fehler);
                Speicher.fehler(c, fehler);
                Speicher.laeuft(c, false);
                Darstellung.alle(c);
                Planer.mitternacht(c);
            }
            return wartet;
        }
    }
}
