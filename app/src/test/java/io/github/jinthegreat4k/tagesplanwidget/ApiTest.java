package io.github.jinthegreat4k.tagesplanwidget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Verbinden mit dem Widget-Code und die Antworten des Lifehub (server/widget.js, server/plan.js). Erfundene Werte. */
public class ApiTest {
    private static final String TOKEN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123-_6"; // 43 Zeichen wie vom Lifehub

    /** Was auswerten wirft, oder ein Testfehler, wenn es nichts wirft */
    private static Api.Fehler planFehler(int status, String text) {
        try {
            Api.auswerten(status, text);
        } catch (Api.Fehler e) {
            return e;
        }
        throw new AssertionError("kein Fehler bei HTTP " + status + ": " + text);
    }

    private static Api.Fehler verbindenFehler(int status, String text) {
        try {
            Api.token(status, text);
        } catch (Api.Fehler e) {
            return e;
        }
        throw new AssertionError("kein Fehler bei HTTP " + status + ": " + text);
    }

    private static JSONObject gesendet(byte[] body) throws Exception {
        return new JSONObject(new String(body, StandardCharsets.UTF_8));
    }

    private static Set<String> felder(JSONObject j) {
        Set<String> s = new HashSet<>();
        for (Iterator<String> it = j.keys(); it.hasNext(); ) s.add(it.next());
        return s;
    }

    /* ---------- Widget-Code ---------- */

    @Test
    public void codeWieImLifehub() {
        assertEquals("ABCDEFGH", Api.codeNormal("ABCD-EFGH"));
        assertEquals("ABCDEFGH", Api.codeNormal("abcd efgh"));
        assertEquals("ABCDEFGH", Api.codeNormal("  abCD-efGH \n"));
        assertEquals("TEST2345", Api.codeNormal("TEST-2345"));
        // Was nicht zum Alphabet gehört, fällt weg wie in server/second.js normalise (0, 1, I, O, Zeichen ausserhalb ASCII)
        assertEquals("ABCDEFGH", Api.codeNormal("ABCD-0EFGH"));
        assertEquals("ABCDEFGH", Api.codeNormal("AB1CD-EFIGH"));
        assertEquals("ABCDEFGH", Api.codeNormal("ABCDÄ-EFGH"));
        assertNull(Api.codeNormal("ABCD-EFG"));
        assertNull(Api.codeNormal("ABCD-EFGHJ"));
        assertNull(Api.codeNormal("ABCD-EFGO"));
        assertNull(Api.codeNormal(""));
        assertNull(Api.codeNormal(null));
        StringBuilder lang = new StringBuilder("ABCD-EFGH");
        while (lang.length() <= 100) lang.append(' ');
        assertNull(Api.codeNormal(lang.toString()));
        assertEquals("ABCD-EFGH", Api.codeZeigen("ABCDEFGH"));
    }

    @Test
    public void benutzterCodeNurAlsHash() {
        String h = Api.codeHash("ABCDEFGH");
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]{64}"));
        assertEquals(h, Api.codeHash("ABCDEFGH"));
        assertFalse(h.equals(Api.codeHash("ABCDEFGJ")));
        assertFalse(h.contains("ABCDEFGH"));
    }

    @Test
    public void nameImLifehub() {
        assertEquals("Widget-App", Api.name(null));
        assertEquals("Widget-App", Api.name("  \t "));
        assertEquals("Widget-App · Galaxy S24", Api.name("Galaxy S24"));
        assertEquals("Widget-App · Galaxy S24", Api.name("Galaxy\u0007 S24\n"));
        String lang = Api.name("Ein sehr langer Gerätename, den jemand eingetippt hat");
        assertEquals(40, lang.length());
        assertTrue(lang.startsWith("Widget-App · Ein sehr langer"));
        // Nie ein halbes Zeichen am Schluss (Emoji = zwei UTF-16-Einheiten, die erste wäre das 40. Zeichen)
        assertEquals("Widget-App · 12345678901234567890123456", Api.name("12345678901234567890123456😀"));
    }

    @Test
    public void nurZumLifehub() {
        assertEquals("https://lauener.online", Api.BASIS);
        assertTrue(Api.basisErlaubt("https://lauener.online", false));
        assertTrue(Api.basisErlaubt("https://lauener.online", true));
        // Nachgebildeter Lifehub nur in der Test-Version
        assertFalse(Api.basisErlaubt("http://10.0.2.2:8080", false));
        assertTrue(Api.basisErlaubt("http://10.0.2.2:8080", true));
        for (String b : new String[]{"http://lauener.online", "https://lauener.online/", "https://lauener.online.example.ch",
                "https://example.ch", "http://10.0.2.2:8080/", "http://10.0.2.2:8", "http://10.0.2.20:8080", "https://10.0.2.2:8080", "", null}) {
            assertFalse(String.valueOf(b), Api.basisErlaubt(b, true));
        }
    }

    /* ---------- Anfragen ---------- */

    @Test
    public void anfrageOhneSchluessel() throws Exception {
        JSONObject load = gesendet(Api.anfrage("load", null));
        assertEquals(Set.of("action"), felder(load));
        assertEquals("load", load.getString("action"));

        JSONObject termine = gesendet(Api.anfrage("termine", new JSONObject().put("von", "2026-10-10").put("bis", "2026-10-16")));
        assertEquals(Set.of("action", "von", "bis"), felder(termine));
        assertEquals("termine", termine.getString("action"));
        assertEquals("2026-10-10", termine.getString("von"));
        assertEquals("2026-10-16", termine.getString("bis"));

        // Wieder öffnen: doneAt null muss als null ankommen (der Lifehub erlaubt done und doneAt, sonst nichts)
        JSONObject f = new JSONObject().put("done", false).put("doneAt", JSONObject.NULL);
        JSONObject update = gesendet(Api.anfrage("update", new JSONObject().put("id", "t_steuern").put("fields", f)));
        assertEquals(Set.of("action", "id", "fields"), felder(update));
        assertEquals("update", update.getString("action"));
        assertEquals("t_steuern", update.getString("id"));
        assertEquals(Set.of("done", "doneAt"), felder(update.getJSONObject("fields")));
        assertFalse(update.getJSONObject("fields").getBoolean("done"));
        assertTrue(update.getJSONObject("fields").isNull("doneAt"));

        // Ein Feld «key» oder ein zweites «action» geht nie hinaus
        JSONObject boese = gesendet(Api.anfrage("load", new JSONObject().put("key", "geheim").put("action", "einstellungen")));
        assertEquals(Set.of("action"), felder(boese));
        assertEquals("load", boese.getString("action"));
    }

    @Test
    public void verbindenSchicktCodeUndName() throws Exception {
        JSONObject j = gesendet(Api.verbindenAnfrage("TEST2345", "Widget-App · Pixel 6"));
        assertEquals(Set.of("code", "name"), felder(j));
        assertEquals("TEST2345", j.getString("code"));
        assertEquals("Widget-App · Pixel 6", j.getString("name"));
    }

    /* ---------- Antworten von /api/widget/plan ---------- */

    @Test
    public void planOk() throws Exception {
        JSONObject j = Api.auswerten(200, "{\"ok\":true,\"data\":{\"tasks\":[],\"briefing\":null}}");
        assertTrue(j.getBoolean("ok"));
        assertTrue(j.getJSONObject("data").has("tasks"));
    }

    @Test
    public void planFehlerDesSkripts() {
        Api.Fehler e = planFehler(200, "{\"ok\":false,\"error\":\"Aufgabe nicht gefunden\"}");
        assertEquals("server", e.code);
        assertEquals("Aufgabe nicht gefunden", e.detail);
        assertTrue(Api.verwerfen(e));
        assertEquals("Fehler im Google-Skript: Aufgabe nicht gefunden.", Api.text(e));

        Api.Fehler ohne = planFehler(200, "{\"ok\":false}");
        assertEquals("server", ohne.code);
        assertFalse(Api.verwerfen(ohne));
        assertEquals("Fehler im Google-Skript: unbekannt.", Api.text(ohne));
    }

    @Test
    public void planGetrennt() {
        for (String text : new String[]{"{\"error\":\"unauthenticated\"}", "", "<html>401</html>"}) {
            Api.Fehler e = planFehler(401, text);
            assertEquals("abgemeldet", e.code);
            assertFalse(Api.verwerfen(e));
            assertEquals("Die Widget-App ist nicht mehr mit dem Lifehub verbunden. Neu verbinden: im Lifehub Tagesplan → Zahnrad → «Widget-App verbinden».",
                    Api.text(e));
            assertEquals("Getrennt", Api.wort(e.code));
        }
    }

    @Test
    public void planFehlerDesLifehub() {
        Api.Fehler e = planFehler(400, "{\"error\":\"bad_request\"}");
        assertEquals("bad_request", e.code);
        assertTrue(Api.verwerfen(e)); // abgelehntes Abhaken nicht endlos wiederholen
        assertEquals("Der Lifehub hat die Anfrage abgelehnt.", Api.text(e));

        e = planFehler(503, "{\"error\":\"plan_unconfigured\"}");
        assertEquals("unconfigured", e.code);
        assertEquals("Der Tagesplan ist im Lifehub nicht eingerichtet.", Api.text(e));
        assertEquals("Lifehub nicht eingerichtet", Api.wort(e.code));

        e = planFehler(504, "{\"error\":\"plan_timeout\"}");
        assertEquals("timeout", e.code);
        assertTrue(e.skript);
        assertEquals("Das Google-Skript antwortet zu langsam.", Api.text(e));
        assertEquals("Skript zu langsam", Api.wort(e.code));
        // Nur plan_timeout heisst «Skript zu langsam»; eine andere Antwort des Lifehub mit 504 ist ein Fehler im Lifehub
        assertEquals("hub", planFehler(504, "{\"error\":\"server_error\"}").code);

        e = planFehler(502, "{\"error\":\"plan_unreachable\"}");
        assertEquals("network", e.code);
        assertEquals("Der Lifehub erreicht das Google-Skript nicht.", Api.text(e));
        assertEquals("Keine Verbindung", Api.wort(e.code));

        e = planFehler(502, "{\"error\":\"plan_bad_response\",\"status\":200,\"detail\":\"Seite nicht gefunden\"}");
        assertEquals("bad_response", e.code);
        assertTrue(e.skript);
        assertEquals("Das Google-Skript antwortet nicht richtig. Antwort: HTTP 200: Seite nicht gefunden", Api.text(e));
        assertEquals("HTTP 302", planFehler(502, "{\"error\":\"plan_bad_response\",\"status\":302,\"detail\":\"\"}").detail);

        for (int s : new int[]{500, 503}) {
            e = planFehler(s, "{\"error\":\"" + (s == 500 ? "server_error" : "misconfigured") + "\"}");
            assertEquals("hub", e.code);
            assertEquals("Fehler im Lifehub.", Api.text(e));
            assertEquals("Fehler im Lifehub", Api.wort(e.code));
        }

        e = planFehler(429, "{\"error\":\"locked\",\"retryAfter\":120}");
        assertEquals("locked", e.code);
        assertEquals(120, e.warten);
    }

    @Test
    public void planAntwortFehlerhaft() {
        // Kein JSON, kein «ok» oder ein Status, den die Widget-Wege nie geben: «antwortet nicht richtig»
        Api.Fehler e = planFehler(200, "<html><head><style>p{}</style></head><body>Bitte warten …</body></html>");
        assertEquals("bad_response", e.code);
        assertFalse(e.skript);
        assertEquals("HTTP 200: Bitte warten …", e.detail);
        assertEquals("Der Lifehub antwortet nicht richtig. Antwort: HTTP 200: Bitte warten …", Api.text(e));
        assertEquals("Antwort fehlerhaft", Api.wort(e.code));

        assertEquals("bad_response", planFehler(200, "{\"tasks\":[]}").code);
        assertEquals("bad_response", planFehler(200, "").code);
        assertEquals("HTTP 200, leere Antwort", planFehler(200, "").detail);
        assertEquals("HTTP 302, Weiterleitung", planFehler(302, "").detail);
        assertEquals("HTTP 403, forbidden_origin", planFehler(403, "{\"error\":\"forbidden_origin\"}").detail);
        for (int s : new int[]{301, 302, 307, 403, 404, 405, 413, 415, 421}) {
            assertEquals("HTTP " + s, "bad_response", planFehler(s, "{\"error\":\"x\"}").code);
        }
    }

    @Test
    public void fehlerseiteUnterwegsIstKeinFehlerDesSkripts() {
        // 5xx ohne JSON (Fehlerseite von Cloudflare, auch ein 504): «Der Lifehub antwortet nicht richtig.» mit dem Grund,
        // nie «Das Google-Skript antwortet zu langsam.» und nie nur «Fehler im Lifehub.»
        for (int s : new int[]{500, 502, 503, 504, 520, 522}) {
            Api.Fehler e = planFehler(s, "<html><head><title>x</title></head><body>Bad gateway</body></html>");
            assertEquals("HTTP " + s, "bad_response", e.code);
            assertFalse(e.skript);
            assertEquals("HTTP " + s + ": x Bad gateway", e.detail);
            assertEquals("Der Lifehub antwortet nicht richtig. Antwort: HTTP " + s + ": x Bad gateway", Api.text(e));
            assertEquals("Antwort fehlerhaft", Api.wort(e.code));
        }
        Api.Fehler e = planFehler(504, "<html>Gateway Timeout</html>");
        assertEquals("bad_response", e.code);
        assertEquals("Der Lifehub antwortet nicht richtig. Antwort: HTTP 504: Gateway Timeout", Api.text(e));
        assertEquals("HTTP 503, leere Antwort", planFehler(503, "").detail);

        // Beim Verbinden gleich (dort ruft der Lifehub das Skript gar nicht auf)
        e = verbindenFehler(504, "<html>Gateway Timeout</html>");
        assertEquals("bad_response", e.code);
        assertFalse(e.skript);
        assertEquals("Der Lifehub antwortet nicht richtig. Antwort: HTTP 504: Gateway Timeout", Api.text(e));
        assertEquals("HTTP 502: Bad gateway", verbindenFehler(502, "<html>Bad gateway</html>").detail);
    }

    /* ---------- Antworten von /api/widget/connect ---------- */

    @Test
    public void verbindenGibtToken() throws Exception {
        assertEquals(TOKEN, Api.token(200, "{\"ok\":true,\"token\":\"" + TOKEN + "\"}"));
        // Ohne ok:true oder mit einem Token in falscher Form: nichts speichern, und der Token steht nie in der Meldung
        for (String text : new String[]{"{\"token\":\"" + TOKEN + "\"}", "{\"ok\":true,\"token\":\"" + TOKEN + "=\"}",
                "{\"ok\":true,\"token\":\"kurz\"}", "{\"ok\":true}", "{\"ok\":\"true\",\"token\":\"" + TOKEN + "\"}"}) {
            Api.Fehler e = verbindenFehler(200, text);
            assertEquals("bad_response", e.code);
            assertFalse(Api.text(e).contains(TOKEN));
        }
    }

    @Test
    public void verbindenAbgelehnt() {
        Api.Fehler e = verbindenFehler(403, "{\"error\":\"wrong_code\"}");
        assertEquals("wrong_code", e.code);
        assertEquals("Der Widget-Code stimmt nicht, ist abgelaufen oder wurde schon benutzt. "
                + "Im Lifehub einen neuen holen: Tagesplan → Zahnrad → «Widget-App verbinden».", Api.text(e));

        // Gesperrt: warten, oder gleich weiter mit einem neuen Widget-Code (der hebt die Sperre im Lifehub auf)
        String neuerCode = " nochmals – oder im Lifehub einen neuen Widget-Code holen: Tagesplan → Zahnrad → «Widget-App verbinden».";
        e = verbindenFehler(429, "{\"error\":\"locked\",\"retryAfter\":120}");
        assertEquals("locked", e.code);
        assertEquals("Zu viele Fehlversuche. Versuch es in 2 Minuten" + neuerCode, Api.text(e));
        assertEquals("Zu viele Fehlversuche. Versuch es in 2 Minuten nochmals – oder im Lifehub einen neuen Widget-Code holen: "
                + "Tagesplan → Zahnrad → «Widget-App verbinden».", Api.text(e));
        assertEquals("Zu viele Fehlversuche. Versuch es in 30 Sekunden" + neuerCode, Api.text(verbindenFehler(429, "{\"error\":\"locked\",\"retryAfter\":30}")));
        assertEquals("Zu viele Fehlversuche. Versuch es in 1 Stunde" + neuerCode, Api.text(verbindenFehler(429, "{\"error\":\"locked\",\"retryAfter\":3600}")));
        assertEquals("Zu viele Fehlversuche. Versuch es in ein paar Minuten" + neuerCode, Api.text(verbindenFehler(429, "{\"error\":\"locked\"}")));
        assertEquals("Zu viele Fehlversuche. Versuch es in ein paar Minuten" + neuerCode, Api.text(verbindenFehler(429, "<html>Rate limited</html>")));

        // 401 heisst beim Verbinden nicht «getrennt» (ein Lifehub ohne die Widget-Wege antwortet so)
        assertEquals("bad_response", verbindenFehler(401, "{\"error\":\"unauthenticated\"}").code);
        assertEquals("bad_response", verbindenFehler(403, "{\"error\":\"forbidden_origin\"}").code);
        assertEquals("bad_request", verbindenFehler(400, "{\"error\":\"bad_request\"}").code);
        assertEquals("hub", verbindenFehler(503, "{\"error\":\"misconfigured\"}").code);
        assertEquals("hub", verbindenFehler(500, "{\"error\":\"server_error\"}").code);
    }

    /* ---------- Texte ---------- */

    @Test
    public void textUndWortFuerJedenFehler() {
        String[] codes = {"offline", "timeout", "abgemeldet", "wrong_code", "locked", "unconfigured", "hub", "bad_request",
                "bad_response", "server", "network", "unauthorized"};
        for (String c : codes) {
            String t = Api.text(new Api.Fehler(c, null));
            assertFalse(c, t.isEmpty());
            assertFalse(c, t.contains("Schlüssel"));
            assertFalse(c, Api.wort(c).contains("Schlüssel"));
        }
        assertEquals("Offline", Api.wort("offline"));
        assertEquals("Skript zu langsam", Api.wort("timeout"));
        assertEquals("Fehler im Skript", Api.wort("server"));
        assertEquals("Anfrage abgelehnt", Api.wort("bad_request"));
        assertEquals("Keine Verbindung", Api.wort("network"));
        assertEquals("Keine Verbindung", Api.wort("unauthorized")); // Rest von 1.1
        assertEquals("Keine Verbindung zum Lifehub.", Api.text(new Api.Fehler("network", "ConnectException")));
        assertEquals("Der Lifehub antwortet zu langsam.", Api.text(new Api.Fehler("timeout", null)));
    }

    @Test
    public void dauerWieImLifehub() {
        assertEquals("1 Sekunde", Api.dauer(0));
        assertEquals("1 Sekunde", Api.dauer(1));
        assertEquals("30 Sekunden", Api.dauer(30));
        assertEquals("1 Minute", Api.dauer(60));
        assertEquals("2 Minuten", Api.dauer(61));
        assertEquals("1 Stunde", Api.dauer(3600));
        assertEquals("2 Stunden", Api.dauer(3601));
    }

    @Test
    public void codeOhneAusnahme() {
        // Seltsame Eingaben im Feld werfen nie
        for (String s : new String[]{"\u0000", "ÄÖÜÄ-éèàç", "ABCD-EFGH-IJKL", "😀😀😀😀😀😀😀😀"}) {
            try {
                Api.codeNormal(s);
            } catch (RuntimeException e) {
                fail(s + ": " + e);
            }
        }
    }
}
