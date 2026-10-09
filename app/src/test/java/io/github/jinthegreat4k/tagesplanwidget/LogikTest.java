package io.github.jinthegreat4k.tagesplanwidget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Erfundene Daten. Heute ist Samstag, 10. Oktober 2026. */
public class LogikTest {
    private static final LocalDate HEUTE = LocalDate.of(2026, 10, 10);

    private static JSONObject aufgabe(String id, String titel, String bereich, String frist) throws Exception {
        JSONObject t = new JSONObject();
        t.put("id", id);
        t.put("title", titel);
        t.put("area", bereich);
        t.put("due", frist != null ? frist : JSONObject.NULL);
        t.put("done", false);
        t.put("next", false);
        t.put("removed", false);
        t.put("src", new JSONObject().put("type", "manual"));
        t.put("created", "2026-10-01T08:00:00.000Z");
        return t;
    }

    private static JSONObject daten() throws Exception {
        JSONArray ts = new JSONArray();
        ts.put(aufgabe("a1", "Steuern", "privat", "2026-10-08"));
        ts.put(aufgabe("a2", "Lager planen", "pfadi", "2026-10-10"));
        ts.put(aufgabe("a3", "Bewerbung nachfassen", "bewerbung", "2026-10-12").put("next", true));
        ts.put(aufgabe("a4", "Roguelike testen", "projekte", null).put("next", true));
        ts.put(aufgabe("a5", "Rechnung Muster AG CHF 42.00 zahlen", "privat", "2026-10-15")
                .put("src", new JSONObject().put("type", "mail").put("kind", "invoice")));
        ts.put(aufgabe("a6", "Idee Velo", "inbox", null));
        ts.put(aufgabe("a7", "Ferien buchen", "privat", "2026-11-01"));
        ts.put(aufgabe("a8", "Notiz ansehen", "inbox", "2026-10-09"));
        ts.put(aufgabe("d1", "Schon erledigt", "privat", "2026-10-01").put("done", true).put("doneAt", "2026-10-05"));
        ts.put(aufgabe("r1", "Entfernt", "privat", "2026-10-01").put("removed", true));
        JSONObject b = new JSONObject().put("date", "2026-10-10").put("probablyPaid", new JSONArray().put(new JSONObject().put("id", "a5")));
        return new JSONObject().put("tasks", ts).put("briefing", b);
    }

    private static List<String> ids(List<Zeile> zs) {
        List<String> out = new ArrayList<>();
        for (Zeile z : zs) out.add(z.art == Zeile.ABSCHNITT ? "#" + z.text + "(" + z.zahl + ")" : z.id);
        return out;
    }

    private static Zeile zeile(List<Zeile> zs, String id) {
        for (Zeile z : zs) if (id.equals(z.id)) return z;
        throw new AssertionError("Zeile fehlt: " + id);
    }

    @Test
    public void aufgabenWieHeuteInDerApp() throws Exception {
        Logik.Ergebnis e = Logik.aufgaben(daten(), new HashMap<>(), Set.of(), HEUTE);
        assertEquals(List.of("#Fokus(3)", "a1", "a2", "a3", "#Auch fällig(1)", "a8", "#Nächste 7 Tage(1)", "a5",
                "#Eingang(1)", "a6", "#Weitere(2)", "a7", "a4"), ids(e.zeilen));
        assertEquals(8, e.offen);
        assertEquals(3, e.faellig);

        Zeile a1 = zeile(e.zeilen, "a1");
        assertEquals("überfällig · Do 8.10.", a1.frist);
        assertEquals(Zeile.FRIST_SPAET, a1.fristArt);
        assertEquals("Privat", a1.info);
        assertEquals("aufgabe=a1", a1.ziel);
        assertEquals("Heute", zeile(e.zeilen, "a2").frist);
        assertEquals(Zeile.FRIST_HEUTE, zeile(e.zeilen, "a2").fristArt);
        assertEquals("Mo 12.10.", zeile(e.zeilen, "a3").frist);
        assertEquals("überfällig · Gestern", zeile(e.zeilen, "a8").frist);
        assertEquals("Eingang", zeile(e.zeilen, "a8").info);
        assertEquals("Privat · Mail · vermutlich bezahlt", zeile(e.zeilen, "a5").info);
        assertEquals("", zeile(e.zeilen, "a6").info); // im Abschnitt Eingang ohne Bereich
        assertEquals("So 1.11.", zeile(e.zeilen, "a7").frist);
        assertEquals("", zeile(e.zeilen, "a4").frist);
    }

    @Test
    public void abgehaktBleibtDurchgestrichenStehen() throws Exception {
        Map<String, Logik.Aenderung> ae = new HashMap<>();
        ae.put("a1", new Logik.Aenderung(true, "2026-10-10", 1));
        Logik.Ergebnis e = Logik.aufgaben(daten(), ae, Set.of("a1"), HEUTE);
        Zeile a1 = zeile(e.zeilen, "a1");
        assertTrue(a1.erledigt);
        assertEquals("erledigt", a1.frist);
        assertEquals("#Fokus(2)", ids(e.zeilen).get(0));
        assertEquals("a1", ids(e.zeilen).get(1));
        assertEquals(7, e.offen);
        assertEquals(2, e.faellig);
    }

    @Test
    public void abgehaktVerschwindetSpaeter() throws Exception {
        Map<String, Logik.Aenderung> ae = new HashMap<>();
        ae.put("a1", new Logik.Aenderung(true, "2026-10-10", 1));
        Logik.Ergebnis e = Logik.aufgaben(daten(), ae, Set.of(), HEUTE);
        assertEquals(List.of("#Fokus(3)", "a2", "a3", "a5"), ids(e.zeilen).subList(0, 4));
    }

    @Test
    public void wiederGeoeffnetErscheint() throws Exception {
        Map<String, Logik.Aenderung> ae = new HashMap<>();
        ae.put("d1", new Logik.Aenderung(false, null, 2));
        Logik.Ergebnis e = Logik.aufgaben(daten(), ae, Set.of(), HEUTE);
        assertFalse(zeile(e.zeilen, "d1").erledigt);
        assertEquals(9, e.offen);
    }

    @Test
    public void hinweisNurVonHeute() throws Exception {
        JSONObject d = daten();
        d.getJSONObject("briefing").put("date", "2026-10-09");
        Logik.Ergebnis e = Logik.aufgaben(d, new HashMap<>(), Set.of(), HEUTE);
        assertEquals("Privat · Mail", zeile(e.zeilen, "a5").info);
    }

    @Test
    public void ohneDatenLeer() {
        Logik.Ergebnis e = Logik.aufgaben(null, new HashMap<>(), Set.of(), HEUTE);
        assertTrue(e.zeilen.isEmpty());
        assertEquals(0, e.offen);
    }

    private static JSONObject termin(String cal, String calName, String title, boolean allDay, String sd, String st, String ed, String et) throws Exception {
        JSONObject e = new JSONObject();
        e.put("cal", cal);
        e.put("calName", calName);
        e.put("id", title.toLowerCase());
        e.put("title", title);
        e.put("ort", JSONObject.NULL);
        e.put("allDay", allDay);
        e.put("sd", sd);
        e.put("st", st != null ? st : JSONObject.NULL);
        e.put("ed", ed);
        e.put("et", et != null ? et : JSONObject.NULL);
        return e;
    }

    @Test
    public void kalenderSiebenTage() throws Exception {
        JSONArray ev = new JSONArray();
        ev.put(termin("haupt", "Hauptkalender", "Brunch", false, "2026-10-10", "10:00", "2026-10-10", "11:00"));
        ev.put(termin("haupt", "Hauptkalender", "Kino", false, "2026-10-10", "20:00", "2026-10-10", "22:30"));
        ev.put(termin("familie", "Familie", "Herbstlager", true, "2026-10-11", null, "2026-10-13", null));
        ev.put(termin("haupt", "Hauptkalender", "Nachtschicht", false, "2026-10-12", "22:00", "2026-10-13", "06:00"));
        ev.put(termin("haupt", "Hauptkalender", "Party", false, "2026-10-14", "20:00", "2026-10-15", "00:00"));
        ev.put(termin("feiertage", "Feiertage in der Schweiz", "Feiertag", true, "2026-10-16", null, "2026-10-16", null));
        ev.put(termin("haupt", "Hauptkalender", "Später", false, "2026-10-17", "09:00", "2026-10-17", "10:00"));
        ev.put(termin("haupt", "Hauptkalender", "Zahnarzt", false, "2026-10-13", "09:00", "2026-10-13", "10:00").put("ort", "Luzern"));

        List<Zeile> z = Logik.termine(ev, HEUTE, LocalTime.of(12, 0));
        List<String> t = new ArrayList<>();
        for (Zeile x : z) t.add(x.art == Zeile.TAG ? "[" + x.text + (x.frei ? " frei" : "") + "]" : x.zeit + " " + x.text);
        assertEquals(List.of(
                "[Heute · Samstag, 10. Oktober]", "10:00–11:00 Brunch", "20:00–22:30 Kino",
                "[Morgen · Sonntag, 11. Oktober]", "bis Di 13.10. Herbstlager",
                "[Montag, 12. Oktober]", "bis Di 13.10. Herbstlager", "ab 22:00 Nachtschicht",
                "[Dienstag, 13. Oktober]", "ganztägig Herbstlager", "bis 06:00 Nachtschicht", "09:00–10:00 Zahnarzt",
                "[Mittwoch, 14. Oktober]", "20:00–00:00 Party",
                "[Donnerstag, 15. Oktober frei]",
                "[Freitag, 16. Oktober]", "ganztägig Feiertag"), t);
        assertTrue(z.get(1).vorbei);
        assertFalse(z.get(2).vorbei);
        assertTrue(z.get(0).heute);
        assertEquals("Familie", z.get(4).info);
        assertEquals("Luzern", z.get(11).info);
        assertEquals("", z.get(1).info);
        assertEquals("kalender=2026-10-13", z.get(11).ziel);
        // Schlüssel eindeutig (Herbstlager an drei Tagen)
        Set<Long> nummern = new java.util.HashSet<>();
        for (Zeile x : z) assertTrue(nummern.add(x.nummer()));
    }

    @Test
    public void datumTexte() {
        assertEquals("Heute", Logik.tagKurz("2026-10-10", HEUTE));
        assertEquals("Morgen", Logik.tagKurz("2026-10-11", HEUTE));
        assertEquals("Gestern", Logik.tagKurz("2026-10-09", HEUTE));
        assertEquals("Fr 16.10.", Logik.tagKurz("2026-10-16", HEUTE));
        ZonedDateTime jetzt = ZonedDateTime.of(2026, 10, 10, 15, 0, 0, 0, Logik.TZ);
        long ms = ZonedDateTime.of(2026, 10, 10, 14, 32, 0, 0, Logik.TZ).toInstant().toEpochMilli();
        assertEquals("Stand 14:32", Logik.stand(ms, jetzt));
        long gestern = ZonedDateTime.of(2026, 10, 9, 22, 10, 0, 0, Logik.TZ).toInstant().toEpochMilli();
        assertEquals("Stand Fr 9.10. 22:10", Logik.stand(gestern, jetzt));
        assertEquals("", Logik.stand(0, jetzt));
    }
}
