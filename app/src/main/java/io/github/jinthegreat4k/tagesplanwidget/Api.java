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

/** Spricht mit dem Google-Skript wie die Web-App: POST mit text/plain und JSON {key, action, …}. */
final class Api {

    static final class Fehler extends Exception {
        final String code;
        final String detail;

        Fehler(String code, String detail) {
            super(code + (detail != null ? ": " + detail : ""));
            this.code = code;
            this.detail = detail;
        }
    }

    private Api() {}

    static JSONObject post(Konfig k, String action, JSONObject extra) throws Fehler {
        byte[] body;
        try {
            JSONObject j = extra != null ? new JSONObject(extra.toString()) : new JSONObject();
            j.put("key", k.key);
            j.put("action", action);
            body = j.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new Fehler("bad_request", null);
        }
        HttpURLConnection c = null;
        int code;
        String txt;
        try {
            URL url = new URL(k.url);
            c = oeffnen(url);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/plain;charset=utf-8");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            code = c.getResponseCode();
            // Google antwortet mit einer Weiterleitung auf script.googleusercontent.com, dort holt man die Antwort mit GET
            for (int i = 0; i < 5 && code >= 300 && code < 400; i++) {
                String ziel = c.getHeaderField("Location");
                c.disconnect();
                if (ziel == null) throw new Fehler("bad_response", "HTTP " + code + ", Weiterleitung ohne Ziel");
                url = new URL(url, ziel);
                if (!"https".equals(url.getProtocol()) && !BuildConfig.DEBUG) throw new Fehler("bad_response", "Weiterleitung ohne https");
                c = oeffnen(url);
                code = c.getResponseCode();
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            txt = in != null ? lesen(in) : "";
        } catch (SocketTimeoutException e) {
            throw new Fehler("timeout", null);
        } catch (UnknownHostException e) {
            throw new Fehler("offline", null);
        } catch (IOException e) {
            throw new Fehler("network", e.getClass().getSimpleName());
        } finally {
            if (c != null) c.disconnect();
        }
        JSONObject j;
        try {
            j = new JSONObject(txt);
        } catch (JSONException e) {
            // Meist eine Fehlerseite von Google: den Anfang zeigen, damit man den Grund sieht
            String kurz = txt.replaceAll("(?is)<(style|script)[\\s\\S]*?</\\1>", " ").replaceAll("<[^>]+>", " ")
                    .replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
            if (kurz.length() > 160) kurz = kurz.substring(0, 160);
            throw new Fehler("bad_response", "HTTP " + code + (kurz.isEmpty() ? ", leere Antwort" : ": " + kurz));
        }
        if (!j.optBoolean("ok")) {
            String err = j.optString("error", "");
            throw new Fehler("unauthorized".equals(err) ? "unauthorized" : "server", err);
        }
        return j;
    }

    static String text(Fehler e) {
        switch (e.code) {
            case "offline": return "Offline.";
            case "timeout": return "Das Google-Skript antwortet zu langsam.";
            case "unauthorized": return "Der Schlüssel stimmt nicht.";
            case "bad_response": return "Das Google-Skript antwortet nicht richtig." + (e.detail != null ? " Antwort: " + e.detail : "");
            case "server": return "Fehler im Google-Skript: " + (e.detail != null && !e.detail.isEmpty() ? e.detail : "unbekannt") + ".";
            default: return "Keine Verbindung zum Google-Skript.";
        }
    }

    private static HttpURLConnection oeffnen(URL url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setUseCaches(false);
        return c;
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
}
