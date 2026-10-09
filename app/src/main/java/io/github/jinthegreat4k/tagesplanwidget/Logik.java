package io.github.jinthegreat4k.tagesplanwidget;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Was in den Widgets steht. Ohne Android, damit es sich auf dem PC prüfen lässt. Reihenfolge wie «Heute» in der Web-App. */
final class Logik {
    static final ZoneId TZ = ZoneId.of("Europe/Zurich");
    static final int KALENDER_TAGE = 7;
    static final long KUERZLICH_MS = 30 * 60_000L;

    private static final String[] WD = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};
    private static final String[] WDL = {"Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"};
    private static final String[] MON = {"Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"};
    private static final List<String> BEREICHE = List.of("inbox", "zivi", "bewerbung", "pfadi", "projekte", "privat");
    private static final List<String> BEREICH_NAME = List.of("Eingang", "Zivi", "Bewerbung", "Pfadi", "Projekte", "Privat");
    private static final Pattern KOPIE = Pattern.compile("rechnungskopie", Pattern.CASE_INSENSITIVE);

    private Logik() {}

    /** Im Widget abgehakt oder wieder geöffnet, aber noch nicht beim Skript gespeichert */
    static final class Aenderung {
        final boolean erledigt;
        final String erledigtAm;
        final long seq;

        Aenderung(boolean erledigt, String erledigtAm, long seq) {
            this.erledigt = erledigt;
            this.erledigtAm = erledigtAm;
            this.seq = seq;
        }
    }

    static final class Ergebnis {
        final List<Zeile> zeilen = new ArrayList<>();
        int offen;
        int faellig;
    }

    private static final class Aufgabe {
        String id, titel, bereich, frist, erstellt;
        boolean erledigt, naechster, mail, kopie;
    }

    /* ---------- Aufgaben ---------- */

    /**
     * Offene Aufgaben in Abschnitten: Fokus (3 wichtigste), Auch fällig, Nächste 7 Tage, Eingang, Weitere.
     * Was gerade im Widget abgehakt wurde (kuerzlich), bleibt durchgestrichen an seinem Platz, damit man es zurücknehmen kann.
     */
    static Ergebnis aufgaben(JSONObject daten, Map<String, Aenderung> aenderungen, Set<String> kuerzlich, LocalDate heute) {
        Ergebnis e = new Ergebnis();
        if (daten == null) return e;
        String h = heute.toString();
        Map<String, String> hinweise = hinweise(daten.optJSONObject("briefing"), h);
        List<Aufgabe> alle = new ArrayList<>();
        JSONArray arr = daten.optJSONArray("tasks");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null || t.optBoolean("removed")) continue;
            Aufgabe a = lesen(t);
            if (a == null) continue;
            Aenderung x = aenderungen.get(a.id);
            if (x != null) a.erledigt = x.erledigt;
            if (a.erledigt && !kuerzlich.contains(a.id)) continue;
            alle.add(a);
        }
        Comparator<Aufgabe> nachFrist = (a, b) -> {
            String ad = a.frist != null ? a.frist : "9999-99-99", bd = b.frist != null ? b.frist : "9999-99-99";
            if (!ad.equals(bd)) return ad.compareTo(bd);
            if (a.naechster != b.naechster) return a.naechster ? -1 : 1;
            int c = (a.erstellt != null ? a.erstellt : "").compareTo(b.erstellt != null ? b.erstellt : "");
            return c != 0 ? c : a.id.compareTo(b.id);
        };
        alle.sort(nachFrist);

        Set<String> gesehen = new HashSet<>();
        List<Aufgabe> fokus = new ArrayList<>();
        for (Aufgabe a : alle) if (!a.bereich.equals("inbox") && a.frist != null) fokus.add(a);
        for (Aufgabe a : alle) if (!a.bereich.equals("inbox") && a.frist == null && a.naechster) fokus.add(a);
        if (fokus.size() > 3) fokus = new ArrayList<>(fokus.subList(0, 3));
        for (Aufgabe a : fokus) gesehen.add(a.id);

        List<Aufgabe> faellig = new ArrayList<>(), woche = new ArrayList<>(), eingang = new ArrayList<>(), weitere = new ArrayList<>();
        String inSieben = heute.plusDays(7).toString();
        for (Aufgabe a : alle) {
            if (gesehen.contains(a.id)) continue;
            if (a.frist != null && a.frist.compareTo(h) <= 0) faellig.add(a);
            else if (a.frist != null && a.frist.compareTo(inSieben) <= 0) woche.add(a);
            else if (a.bereich.equals("inbox")) eingang.add(a);
            else weitere.add(a);
        }

        abschnitt(e, "Fokus", fokus, heute, hinweise, false);
        abschnitt(e, "Auch fällig", faellig, heute, hinweise, false);
        abschnitt(e, "Nächste 7 Tage", woche, heute, hinweise, false);
        abschnitt(e, "Eingang", eingang, heute, hinweise, true);
        abschnitt(e, "Weitere", weitere, heute, hinweise, false);

        for (Aufgabe a : alle) {
            if (a.erledigt) continue;
            e.offen++;
            if (a.frist != null && a.frist.compareTo(h) <= 0) e.faellig++;
        }
        return e;
    }

    private static void abschnitt(Ergebnis e, String titel, List<Aufgabe> liste, LocalDate heute, Map<String, String> hinweise, boolean ohneBereich) {
        if (liste.isEmpty()) return;
        Zeile k = new Zeile(Zeile.ABSCHNITT, "s:" + titel);
        k.text = titel;
        k.zahl = 0;
        for (Aufgabe a : liste) if (!a.erledigt) k.zahl++;
        e.zeilen.add(k);
        String h = heute.toString();
        for (Aufgabe a : liste) {
            Zeile z = new Zeile(Zeile.AUFGABE, "a:" + a.id);
            z.id = a.id;
            z.text = a.titel;
            z.bereich = a.bereich;
            z.erledigt = a.erledigt;
            z.ziel = "aufgabe=" + a.id;
            if (a.erledigt) {
                z.frist = "erledigt";
                z.fristArt = Zeile.FRIST_NORMAL;
            } else if (a.frist != null) {
                int c = a.frist.compareTo(h);
                z.frist = (c < 0 ? "überfällig · " : "") + tagKurz(a.frist, heute);
                z.fristArt = c < 0 ? Zeile.FRIST_SPAET : c == 0 ? Zeile.FRIST_HEUTE : Zeile.FRIST_NORMAL;
            }
            List<String> info = new ArrayList<>();
            if (!ohneBereich) info.add(bereichName(a.bereich));
            if (a.mail) info.add("Mail");
            String hw = hinweise.get(a.id);
            if (hw != null && !a.erledigt) info.add(a.kopie && hw.equals("vermutlich bezahlt") ? "vermutlich erledigt" : hw);
            z.info = String.join(" · ", info);
            e.zeilen.add(z);
        }
    }

    private static Aufgabe lesen(JSONObject t) {
        String id = str(t, "id");
        if (id == null) return null;
        Aufgabe a = new Aufgabe();
        a.id = id;
        a.titel = str(t, "title") != null ? str(t, "title") : "(ohne Text)";
        String b = str(t, "area");
        a.bereich = b != null && BEREICHE.contains(b) ? b : "inbox";
        String f = str(t, "due");
        a.frist = f != null && f.matches("\\d{4}-\\d{2}-\\d{2}") ? f : null;
        a.erstellt = str(t, "created");
        a.erledigt = t.optBoolean("done");
        a.naechster = t.optBoolean("next");
        JSONObject src = t.optJSONObject("src");
        a.mail = src != null && "mail".equals(str(src, "type"));
        a.kopie = KOPIE.matcher(a.titel).find();
        return a;
    }

    /** Hinweise aus dem Tagesstart von heute: Stern weg, Rechnung nicht mehr im Posteingang */
    private static Map<String, String> hinweise(JSONObject b, String heute) {
        Map<String, String> m = new HashMap<>();
        if (b == null || !heute.equals(str(b, "date"))) return m;
        JSONArray done = b.optJSONArray("probablyDone");
        for (int i = 0; done != null && i < done.length(); i++) {
            JSONObject p = done.optJSONObject(i);
            if (p != null && str(p, "id") != null) m.put(str(p, "id"), "vermutlich erledigt");
        }
        JSONArray paid = b.optJSONArray("probablyPaid");
        for (int i = 0; paid != null && i < paid.length(); i++) {
            JSONObject p = paid.optJSONObject(i);
            if (p != null && str(p, "id") != null) m.put(str(p, "id"), "vermutlich bezahlt");
        }
        return m;
    }

    static String bereichName(String b) {
        int i = BEREICHE.indexOf(b);
        return i >= 0 ? BEREICH_NAME.get(i) : "Eingang";
    }

    /* ---------- Termine ---------- */

    /** Heute und die nächsten 6 Tage. Jeder Tag hat eine Kopfzeile, auch wenn er frei ist. */
    static List<Zeile> termine(JSONArray events, LocalDate heute, LocalTime jetzt) {
        List<Zeile> out = new ArrayList<>();
        String hm = String.format("%02d:%02d", jetzt.getHour(), jetzt.getMinute());
        List<JSONObject> alle = new ArrayList<>();
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e != null && str(e, "sd") != null && str(e, "ed") != null) alle.add(e);
        }
        Set<String> schluessel = new HashSet<>();
        for (int i = 0; i < KALENDER_TAGE; i++) {
            LocalDate d = heute.plusDays(i);
            String ds = d.toString();
            List<JSONObject> amTag = new ArrayList<>();
            for (JSONObject e : alle) if (str(e, "sd").compareTo(ds) <= 0 && ds.compareTo(letzterTag(e)) <= 0) amTag.add(e);
            amTag.sort((a, b) -> {
                boolean aa = a.optBoolean("allDay"), ba = b.optBoolean("allDay");
                if (aa != ba) return aa ? -1 : 1;
                String as = beginnAm(a, ds), bs = beginnAm(b, ds);
                return as.compareTo(bs);
            });
            Zeile tag = new Zeile(Zeile.TAG, "d:" + ds);
            tag.text = tagTitel(d, heute);
            tag.heute = i == 0;
            tag.frei = amTag.isEmpty();
            tag.ziel = "kalender=" + ds;
            out.add(tag);
            for (JSONObject e : amTag) {
                String cal = str(e, "cal") != null ? str(e, "cal") : "";
                String s = "e:" + ds + ":" + cal + ":" + str(e, "id") + ":" + str(e, "title");
                while (!schluessel.add(s)) s += "+";
                Zeile z = new Zeile(Zeile.TERMIN, s);
                z.text = str(e, "title") != null ? str(e, "title") : "(ohne Titel)";
                z.zeit = zeit(e, ds);
                z.kalender = cal;
                List<String> info = new ArrayList<>();
                if (!cal.equals("haupt") && str(e, "calName") != null) info.add(str(e, "calName"));
                if (str(e, "ort") != null) info.add(str(e, "ort"));
                z.info = String.join(" · ", info);
                z.ziel = "kalender=" + ds;
                String et = str(e, "et");
                z.vorbei = i == 0 && !e.optBoolean("allDay") && letzterTag(e).equals(ds) && et != null && et.compareTo(hm) <= 0;
                out.add(z);
            }
        }
        return out;
    }

    /** Letzter Tag, an dem ein Termin erscheint (bis 00:00 gehört nicht mehr zum nächsten Tag) */
    static String letzterTag(JSONObject e) {
        String sd = str(e, "sd"), ed = str(e, "ed");
        if (!e.optBoolean("allDay") && ed.compareTo(sd) > 0 && "00:00".equals(str(e, "et"))) return LocalDate.parse(ed).minusDays(1).toString();
        return ed;
    }

    private static String beginnAm(JSONObject e, String ds) {
        if (str(e, "sd").compareTo(ds) < 0) return "";
        String st = str(e, "st");
        return st != null ? st : "";
    }

    /** Wie in der App: «ganztägig», «bis Fr 16.10.», «10:00–11:00», «bis 12:00», «ab 22:00» */
    static String zeit(JSONObject e, String ds) {
        String last = letzterTag(e);
        if (e.optBoolean("allDay")) return last.compareTo(ds) > 0 ? "bis " + kurz(LocalDate.parse(last)) : "ganztägig";
        String s = str(e, "sd").compareTo(ds) < 0 ? null : str(e, "st");
        String en = last.compareTo(ds) > 0 ? null : str(e, "et");
        if (s != null && en != null) return s + "–" + en;
        if (en != null) return "bis " + en;
        if (s != null) return "ab " + s;
        return "ganztägig";
    }

    static List<Zeile> hinweisZeilen(JSONArray errors) {
        List<Zeile> out = new ArrayList<>();
        for (int i = 0; errors != null && i < errors.length() && i < 3; i++) {
            String s = errors.optString(i, "");
            if (s.isEmpty()) continue;
            Zeile z = new Zeile(Zeile.FUSS, "f:" + i);
            z.text = s;
            out.add(z);
        }
        return out;
    }

    /* ---------- Datum ---------- */

    static String tagTitel(LocalDate d, LocalDate heute) {
        String vor = d.equals(heute) ? "Heute · " : d.equals(heute.plusDays(1)) ? "Morgen · " : "";
        return vor + WDL[d.getDayOfWeek().getValue() - 1] + ", " + d.getDayOfMonth() + ". " + MON[d.getMonthValue() - 1];
    }

    /** «Heute», «Morgen», «Gestern» oder «Mo 5.10.» */
    static String tagKurz(String ymd, LocalDate heute) {
        LocalDate d = LocalDate.parse(ymd);
        if (d.equals(heute)) return "Heute";
        if (d.equals(heute.plusDays(1))) return "Morgen";
        if (d.equals(heute.minusDays(1))) return "Gestern";
        return kurz(d);
    }

    static String kurz(LocalDate d) {
        return WD[d.getDayOfWeek().getValue() - 1] + " " + d.getDayOfMonth() + "." + d.getMonthValue() + ".";
    }

    /** «Stand 14:32» oder «Stand Fr 9.10. 22:10» */
    static String stand(long ms, ZonedDateTime jetzt) {
        if (ms <= 0) return "";
        ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(TZ);
        String hm = String.format("%02d:%02d", t.getHour(), t.getMinute());
        return "Stand " + (t.toLocalDate().equals(jetzt.toLocalDate()) ? hm : kurz(t.toLocalDate()) + " " + hm);
    }

    static LocalDate heute() {
        return ZonedDateTime.now(TZ).toLocalDate();
    }

    /* ---------- JSON ---------- */

    /** Text oder null (fehlt, JSON-null oder leer) */
    static String str(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return null;
        String s = o.optString(k, "");
        return s.isEmpty() ? null : s;
    }

    static Set<String> menge(Map<String, Long> m, long jetzt) {
        Set<String> s = new LinkedHashSet<>();
        for (Map.Entry<String, Long> e : m.entrySet()) if (jetzt - e.getValue() < KUERZLICH_MS) s.add(e.getKey());
        return Collections.unmodifiableSet(s);
    }
}
