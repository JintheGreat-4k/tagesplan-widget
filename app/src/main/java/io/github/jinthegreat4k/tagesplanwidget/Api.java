package io.github.jinthegreat4k.tagesplanwidget;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Spricht nur mit dem Lifehub, nie direkt mit dem Google-Skript (POST, JSON, Antwort JSON):
 *   /api/widget/connect     {code, name}  → {ok:true, token}   einmal, mit dem Widget-Code aus dem Tagesplan
 *   /api/widget/plan        {action, …}   → Antwort des Skripts  laden, Termine, abhaken (den Schlüssel setzt der Lifehub)
 *   /api/widget/disconnect  {}            → {ok:true}           «Verbindung trennen»
 * Der Token steht nur in der Kopfzeile Authorization, nie in einer Adresse, einem Anfragetext oder im Protokoll.
 * Weiterleitungen werden nie befolgt. Alles ausser senden() braucht kein Android: JUnit prüft es auf dem PC.
 */
final class Api {
    static final String BASIS = "https://lauener.online";
    // Nur in der Test-Version (Emulator auf GitHub): nachgebildeter Lifehub auf dem Rechner
    static final Pattern TEST_BASIS = Pattern.compile("^http://10\\.0\\.2\\.2:\\d{2,5}$");
    static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    // Wie die Codes im Lifehub (server/second.js): 8 Zeichen ohne 0, 1, I und O
    private static final Pattern CODE = Pattern.compile("^[A-HJ-NP-Z2-9]{8}$");
    static final String NAME = "Widget-App";
    private static final int NAME_MAX = 40;
    private static final int PLAN_MS = 60000, KURZ_MS = 30000;

    static final String GETRENNT = "Die Widget-App ist nicht mehr mit dem Lifehub verbunden. "
            + "Neu verbinden: im Lifehub Tagesplan → Zahnrad → «Widget-App verbinden».";

    static final class Fehler extends Exception {
        final String code;
        final String detail;
        final boolean skript; // es hakt beim Google-Skript hinter dem Lifehub, nicht beim Lifehub selbst
        final int warten;     // locked: Sekunden bis zum nächsten Versuch, 0 = unbekannt

        Fehler(String code, String detail) {
            this(code, detail, false, 0);
        }

        Fehler(String code, String detail, boolean skript, int warten) {
            super(code + (detail != null ? ": " + detail : ""));
            this.code = code;
            this.detail = detail;
            this.skript = skript;
            this.warten = warten;
        }
    }

    private static final class Antwort {
        final int status;
        final String text;

        Antwort(int status, String text) {
            this.status = status;
            this.text = text;
        }
    }

    private Api() {}

    /* ---------- Mit dem Lifehub sprechen ---------- */

    static JSONObject post(Konfig k, String action, JSONObject extra) throws Fehler {
        Antwort a = senden(k.basis + "/api/widget/plan", k.token, anfrage(action, extra), PLAN_MS);
        return auswerten(a.status, a.text);
    }

    /** Widget-Code gegen einen Token tauschen. alterToken (falls schon verbunden): Der Lifehub ersetzt diese Verbindung. */
    static String verbinden(String basis, String code, String name, String alterToken) throws Fehler {
        Antwort a = senden(basis + "/api/widget/connect", alterToken, verbindenAnfrage(code, name), KURZ_MS);
        return token(a.status, a.text);
    }

    /** «Verbindung trennen»: im Lifehub austragen, wenn es geht. Sonst bleibt sie dort in der Liste und lässt sich entfernen. */
    static void trennen(Konfig k) {
        try {
            senden(k.basis + "/api/widget/disconnect", k.token, "{}".getBytes(StandardCharsets.UTF_8), KURZ_MS);
        } catch (Fehler e) {
            // egal: Die App vergisst den Token ohnehin
        }
    }

    private static Antwort senden(String adresse, String token, byte[] body, int lesenMs) throws Fehler {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(adresse).openConnection();
            c.setInstanceFollowRedirects(false); // eine Weiterleitung ist immer ein Fehler, der Token geht nie anderswohin
            c.setConnectTimeout(20000);
            c.setReadTimeout(lesenMs);
            c.setUseCaches(false);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Antwort(status, in != null ? lesen(in) : "");
        } catch (SocketTimeoutException e) {
            throw new Fehler("timeout", null);
        } catch (UnknownHostException e) {
            throw new Fehler("offline", null);
        } catch (IOException e) {
            throw new Fehler("network", e.getClass().getSimpleName());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String lesen(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > 5_000_000) throw new IOException("Antwort zu gross");
            }
            return b.toString("UTF-8");
        }
    }

    /* ---------- Anfragen (ohne Android) ---------- */

    /** Anfragetext für /api/widget/plan: {action, …extra}. Nie ein Feld «key»: Den Schlüssel des Skripts kennt nur der Lifehub. */
    static byte[] anfrage(String action, JSONObject extra) throws Fehler {
        try {
            JSONObject j = new JSONObject();
            j.put("action", action);
            if (extra != null) {
                for (Iterator<String> it = extra.keys(); it.hasNext(); ) {
                    String name = it.next();
                    if (!name.equals("action") && !name.equals("key")) j.put(name, extra.get(name));
                }
            }
            return j.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new Fehler("bad_request", null);
        }
    }

    static byte[] verbindenAnfrage(String code, String name) throws Fehler {
        try {
            return new JSONObject().put("code", code).put("name", name).toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new Fehler("bad_request", null);
        }
    }

    /**
     * Eingabe oder Link → die 8 Zeichen des Widget-Codes, sonst null. Genau wie normalise im Lifehub: Alles ausserhalb
     * A–Z a–z 2–9 fällt weg (Bindestrich, Leerzeichen, 0, 1), dann Grossbuchstaben, I und O fallen weg.
     */
    static String codeNormal(String eingabe) {
        if (eingabe == null || eingabe.length() > 100) return null;
        String s = eingabe.replaceAll("[^A-Za-z2-9]", "").toUpperCase(Locale.ROOT).replaceAll("[IO]", "");
        return CODE.matcher(s).matches() ? s : null;
    }

    /** «ABCD-EFGH», wie der Lifehub den Code zeigt */
    static String codeZeigen(String code) {
        return code.length() == 8 ? code.substring(0, 4) + "-" + code.substring(4) : code;
    }

    /** Woran die App einen schon benutzten Code wiedererkennt, ohne ihn selbst aufzubewahren */
    static String codeHash(String code) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("widget-code:" + code).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Name der Verbindung in der Liste des Lifehub: «Widget-App · Galaxy S24», ohne Gerätenamen «Widget-App». Höchstens 40 Zeichen. */
    static String name(String geraet) {
        String g = geraet == null ? "" : geraet.replaceAll("\\p{Cc}", "").trim();
        String n = g.isEmpty() ? NAME : NAME + " · " + g;
        if (n.length() <= NAME_MAX) return n;
        int ende = Character.isHighSurrogate(n.charAt(NAME_MAX - 1)) ? NAME_MAX - 1 : NAME_MAX;
        return n.substring(0, ende).trim();
    }

    /** Wohin die App spricht: immer https://lauener.online, nur die Test-Version auch zum nachgebildeten Lifehub */
    static boolean basisErlaubt(String basis, boolean test) {
        return BASIS.equals(basis) || (test && basis != null && TEST_BASIS.matcher(basis).matches());
    }

    /* ---------- Antworten (ohne Android) ---------- */

    /**
     * Antwort von /api/widget/plan → die Antwort des Skripts ({ok:true, …}) oder ein Fehler. Entscheidet nach Status und
     * Feld «error» des Lifehub (server/plan.js): {ok:false} vom Skript bleibt «server» wie bisher, 401 heisst getrennt.
     */
    static JSONObject auswerten(int status, String text) throws Fehler {
        JSONObject j = objekt(text);
        if (status == 200) {
            if (j == null || !(j.opt("ok") instanceof Boolean)) throw new Fehler("bad_response", ausschnitt(status, text));
            if (!j.optBoolean("ok")) {
                String err = Logik.str(j, "error");
                throw new Fehler("server", err != null ? err : "");
            }
            return j;
        }
        if (status == 401) throw new Fehler("abgemeldet", null);
        throw fehler(status, j, text);
    }

    /** Antwort von /api/widget/connect → der Token, oder ein Fehler (wrong_code, locked, …) */
    static String token(int status, String text) throws Fehler {
        JSONObject j = objekt(text);
        if (status == 200) {
            String t = j != null && Boolean.TRUE.equals(j.opt("ok")) ? Logik.str(j, "token") : null;
            if (t == null || !TOKEN.matcher(t).matches()) throw new Fehler("bad_response", "HTTP 200, ohne gültigen Token");
            return t;
        }
        if (status == 403 && j != null && "wrong_code".equals(Logik.str(j, "error"))) throw new Fehler("wrong_code", null);
        // 401 heisst hier nicht «getrennt»: Ein Lifehub ohne die Widget-Wege antwortet so
        throw fehler(status, j, text);
    }

    private static Fehler fehler(int status, JSONObject j, String text) {
        String err = j != null ? Logik.str(j, "error") : null;
        if (status == 429) return new Fehler("locked", null, false, j != null ? j.optInt("retryAfter", 0) : 0);
        if (status == 400) return new Fehler("bad_request", err);
        // Kein JSON: meist eine Fehlerseite unterwegs (Cloudflare 502, 520, 522 …), nicht der Lifehub und nicht das Skript.
        // Mit dem Anfang der Seite, damit man den Grund sieht.
        if (j == null && status >= 500) return new Fehler("bad_response", ausschnitt(status, text));
        if (status == 504 && "plan_timeout".equals(err)) return new Fehler("timeout", null, true, 0);
        if (status == 503 && "plan_unconfigured".equals(err)) return new Fehler("unconfigured", null);
        if (status == 502 && "plan_unreachable".equals(err)) return new Fehler("network", null, true, 0);
        if (status == 502 && "plan_bad_response".equals(err)) return new Fehler("bad_response", skriptDetail(j), true, 0);
        if (status >= 500) return new Fehler("hub", err != null ? err : "HTTP " + status);
        // 3xx (nie befolgt), 401 beim Verbinden, 403, 404, 405, 413, 415, 421, Fehlerseiten unterwegs
        return new Fehler("bad_response", err != null ? "HTTP " + status + ", " + err : ausschnitt(status, text));
    }

    /** {error:'plan_bad_response', status, detail}: Status und Ausschnitt der Antwort von Google (schon bereinigt) */
    private static String skriptDetail(JSONObject j) {
        int s = j.optInt("status", 0);
        String d = Logik.str(j, "detail");
        String out = s > 0 ? "HTTP " + s : "";
        if (d != null) out += (out.isEmpty() ? "" : ": ") + d;
        return out.isEmpty() ? null : out;
    }

    /** Anfang einer Antwort, die kein JSON ist (meist eine Fehlerseite unterwegs), damit man den Grund sieht */
    private static String ausschnitt(int status, String text) {
        if (status >= 300 && status < 400) return "HTTP " + status + ", Weiterleitung";
        String t = text == null ? "" : text.length() > 65536 ? text.substring(0, 65536) : text;
        String kurz = t.replaceAll("(?is)<(style|script)[\\s\\S]*?</\\1>", " ").replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        if (kurz.length() > 160) kurz = kurz.substring(0, 160);
        return "HTTP " + status + (kurz.isEmpty() ? ", leere Antwort" : ": " + kurz);
    }

    private static JSONObject objekt(String text) {
        if (text == null) return null;
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Abhaken, das nie gelingen kann (Aufgabe gelöscht oder vom Lifehub abgelehnt): verwerfen statt endlos wiederholen */
    static boolean verwerfen(Fehler x) {
        return "bad_request".equals(x.code) || ("server".equals(x.code) && x.detail != null && x.detail.contains("nicht gefunden"));
    }

    /* ---------- Texte (ohne Android) ---------- */

    static String text(Fehler e) {
        switch (e.code) {
            case "offline": return "Offline.";
            case "timeout": return e.skript ? "Das Google-Skript antwortet zu langsam." : "Der Lifehub antwortet zu langsam.";
            case "abgemeldet": return GETRENNT;
            case "wrong_code": return "Der Widget-Code stimmt nicht, ist abgelaufen oder wurde schon benutzt. "
                    + "Im Lifehub einen neuen holen: Tagesplan → Zahnrad → «Widget-App verbinden».";
            // Ein neuer Widget-Code im Lifehub hebt die Sperre gleich auf (POST /api/codes/widget setzt den Zähler zurück)
            case "locked": return "Zu viele Fehlversuche. Versuch es in "
                    + (e.warten > 0 && e.warten < 10_000_000 ? dauer(e.warten) : "ein paar Minuten")
                    + " nochmals – oder im Lifehub einen neuen Widget-Code holen: Tagesplan → Zahnrad → «Widget-App verbinden».";
            case "unconfigured": return "Der Tagesplan ist im Lifehub nicht eingerichtet.";
            case "hub": return "Fehler im Lifehub.";
            case "bad_request": return "Der Lifehub hat die Anfrage abgelehnt.";
            case "bad_response": return (e.skript ? "Das Google-Skript antwortet nicht richtig." : "Der Lifehub antwortet nicht richtig.")
                    + (e.detail != null && !e.detail.isEmpty() ? " Antwort: " + e.detail : "");
            case "server": return "Fehler im Google-Skript: " + (e.detail != null && !e.detail.isEmpty() ? e.detail : "unbekannt") + ".";
            default: return e.skript ? "Der Lifehub erreicht das Google-Skript nicht." : "Keine Verbindung zum Lifehub.";
        }
    }

    /** Kurzes Wort in der Statuszeile der Widgets (Speicher.fehler hält nur den Code) */
    static String wort(String code) {
        switch (code) {
            case "offline": return "Offline";
            case "timeout": return "Skript zu langsam";
            case "abgemeldet": return "Getrennt";
            case "bad_response": return "Antwort fehlerhaft";
            case "server": return "Fehler im Skript";
            case "hub": return "Fehler im Lifehub";
            case "unconfigured": return "Lifehub nicht eingerichtet";
            case "bad_request": return "Anfrage abgelehnt";
            default: return "Keine Verbindung";
        }
    }

    /** «30 Sekunden», «2 Minuten», «1 Stunde»: aufgerundet wie im Lifehub, damit die Angabe nie zu kurz ist */
    static String dauer(int sekunden) {
        if (sekunden < 60) return einheit(Math.max(1, sekunden), "Sekunde", "Sekunden");
        if (sekunden < 3600) return einheit((sekunden + 59) / 60, "Minute", "Minuten");
        return einheit((sekunden + 3599) / 3600, "Stunde", "Stunden");
    }

    private static String einheit(int n, String eins, String mehr) {
        return n + " " + (n == 1 ? eins : mehr);
    }
}
