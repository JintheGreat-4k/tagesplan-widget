package io.github.jinthegreat4k.tagesplanwidget;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.Map;

/** Ein Abgleich über den Lifehub: zuerst Abgehaktes senden, dann Aufgaben und Termine laden. Läuft nie im Haupt-Thread. */
final class Sync {
    private static final Object LOCK = new Object();
    private static final String ABGEMELDET = "abgemeldet";

    private Sync() {}

    /**
     * true, wenn Änderungen wegen eines Netzfehlers noch warten (dann später nochmals versuchen).
     * Nach jeder Antwort prüft er, ob noch derselbe Token gespeichert ist: Wurde während des Abgleichs getrennt (oder neu
     * verbunden), speichert er nichts mehr, fragt nichts mehr an und stellt keinen Wecker.
     */
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
                        if (!Konfig.gilt(c, k.token)) break;
                        Speicher.aenderungFertig(c, e.getKey(), a.seq, r.optJSONObject("task"));
                    } catch (Api.Fehler x) {
                        // Aufgabe gibt es nicht mehr (z. B. in der Tabelle gelöscht) oder der Lifehub lehnt ab: nicht endlos wiederholen
                        if (Api.verwerfen(x)) {
                            Log.w(Speicher.TAG, "Änderung verworfen: " + x.code);
                            Speicher.aenderungFertig(c, e.getKey(), a.seq, null);
                            continue;
                        }
                        fehler = x.code;
                        // Getrennt: nichts wiederholen. Sonst (Netz, Skript) später nochmals senden.
                        wartet = !ABGEMELDET.equals(x.code);
                        break;
                    }
                }
                if (fehler == null && Konfig.gilt(c, k.token)) {
                    try {
                        JSONObject r = Api.post(k, "load", null);
                        if (Konfig.gilt(c, k.token)) Speicher.aufgabenSetzen(c, r.optJSONObject("data"));
                    } catch (Api.Fehler x) {
                        fehler = x.code;
                    }
                    if (!ABGEMELDET.equals(fehler) && Konfig.gilt(c, k.token)) {
                        LocalDate h = Logik.heute();
                        String von = h.toString(), bis = h.plusDays(Logik.KALENDER_TAGE - 1).toString();
                        JSONObject req = new JSONObject();
                        req.put("von", von);
                        req.put("bis", bis);
                        try {
                            JSONObject r = Api.post(k, "termine", req);
                            if (Konfig.gilt(c, k.token)) Speicher.termineSetzen(c, von, bis, r);
                        } catch (Api.Fehler x) {
                            if (fehler == null || ABGEMELDET.equals(x.code)) fehler = x.code;
                        }
                    }
                }
            } catch (JSONException e) {
                fehler = "bad_request";
            } finally {
                if (fehler != null) Log.w(Speicher.TAG, "Abgleich: " + fehler);
                if (ABGEMELDET.equals(fehler) && Konfig.abmelden(c, k.token)) {
                    // Im Lifehub entfernt, überall abgemeldet oder Passwort geändert: Token, Aufgaben und Termine weg
                    Speicher.allesLoeschen(c);
                    Planer.stoppen(c);
                    Darstellung.alle(c);
                } else if (!Konfig.gilt(c, k.token)) {
                    // Während des Abgleichs getrennt oder neu verbunden: Der alte Token zählt nicht mehr. Nichts mehr
                    // speichern, keinen Wecker stellen, nicht wiederholen. Getrennt: was trotzdem noch geschrieben wurde,
                    // gleich wieder löschen (neu verbunden: Das Verbinden hat schon aufgeräumt, der neue Abgleich lädt).
                    if (!Konfig.da(c)) Speicher.allesLoeschen(c);
                    Darstellung.alle(c);
                    wartet = false;
                } else {
                    // «abgemeldet» kommt hier nicht an (dann gilt der Token nicht mehr); sicherheitshalber nie als Fehler
                    Speicher.fehler(c, ABGEMELDET.equals(fehler) ? null : fehler);
                    Speicher.laeuft(c, false);
                    Darstellung.alle(c);
                    Planer.mitternacht(c);
                }
            }
            return wartet;
        }
    }
}
