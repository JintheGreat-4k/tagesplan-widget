package io.github.jinthegreat4k.tagesplanwidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Baut die Widgets aus dem gespeicherten Stand. Netz braucht es dafür nicht. */
final class Darstellung {
    private static final int AUFGABEN = 100, KALENDER = 200;

    private Darstellung() {}

    static void alle(Context c) {
        aufgaben(c, null);
        kalender(c, null);
    }

    static void aufgaben(Context c, int[] ids) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (ids == null) ids = m.getAppWidgetIds(new ComponentName(c, AufgabenWidget.class));
        if (ids == null || ids.length == 0) return;
        try {
            m.updateAppWidget(ids, bauenAufgaben(c));
        } catch (RuntimeException e) {
            Log.e(Speicher.TAG, "Aufgaben-Widget", e);
        }
    }

    static void kalender(Context c, int[] ids) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (ids == null) ids = m.getAppWidgetIds(new ComponentName(c, KalenderWidget.class));
        if (ids == null || ids.length == 0) return;
        try {
            m.updateAppWidget(ids, bauenKalender(c));
        } catch (RuntimeException e) {
            Log.e(Speicher.TAG, "Kalender-Widget", e);
        }
    }

    /* ---------- Aufgaben ---------- */

    static RemoteViews bauenAufgaben(Context c) {
        RemoteViews rv = rahmen(c, AUFGABEN, "Aufgaben", "heute", "neu", "Neue Aufgabe erfassen");
        if (!Konfig.da(c)) return nichtVerbunden(c, rv);
        JSONObject daten = Speicher.aufgaben(c);
        Map<String, Logik.Aenderung> ae = Speicher.aenderungen(c);
        Logik.Ergebnis e = Logik.aufgaben(daten, ae, Logik.menge(Speicher.kuerzlich(c), System.currentTimeMillis()), Logik.heute());

        List<String> st = new ArrayList<>();
        if (daten != null) {
            st.add(e.offen + " offen");
            if (e.faellig > 0) st.add(e.faellig + " fällig");
        }
        if (!ae.isEmpty()) st.add(ae.size() == 1 ? "1 Änderung wartet" : ae.size() + " Änderungen warten");
        st.add(status(c, Speicher.standAufgaben(c)));
        rv.setTextViewText(R.id.status, verbinden(st));

        RemoteViews.RemoteCollectionItems.Builder b = new RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(5);
        for (Zeile z : e.zeilen) b.addItem(z.nummer(), zeile(c, z));
        rv.setRemoteAdapter(R.id.liste, b.build());
        rv.setPendingIntentTemplate(R.id.liste, vorlage(c, AUFGABEN));
        rv.setTextViewText(R.id.leer, daten == null ? leerText(c) : "Keine offenen Aufgaben.");
        rv.setEmptyView(R.id.liste, R.id.leer);
        return rv;
    }

    /* ---------- Kalender ---------- */

    static RemoteViews bauenKalender(Context c) {
        RemoteViews rv = rahmen(c, KALENDER, "Kalender", "kalender", "termin", "Neuen Termin erfassen");
        if (!Konfig.da(c)) return nichtVerbunden(c, rv);
        JSONObject daten = Speicher.termine(c);
        ZonedDateTime jetzt = ZonedDateTime.now(Logik.TZ);
        List<Zeile> zeilen = new ArrayList<>();
        if (daten != null) {
            // Gespeicherter Zeitraum reicht nur bis «bis»: spätere Tage erst nach dem nächsten Laden zeigen
            List<Zeile> alle = Logik.termine(daten.optJSONArray("events"), jetzt.toLocalDate(), LocalTime.of(jetzt.getHour(), jetzt.getMinute()));
            String bis = Logik.str(daten, "bis");
            String tag = "";
            for (Zeile z : alle) {
                if (z.art == Zeile.TAG) tag = z.schluessel.substring(2);
                if (bis == null || tag.compareTo(bis) <= 0) zeilen.add(z);
            }
            zeilen.addAll(Logik.hinweisZeilen(daten.optJSONArray("errors")));
        }
        rv.setTextViewText(R.id.status, verbinden(List.of("Nächste 7 Tage", status(c, Speicher.standTermine(c)))));

        RemoteViews.RemoteCollectionItems.Builder b = new RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(5);
        for (Zeile z : zeilen) b.addItem(z.nummer(), zeile(c, z));
        rv.setRemoteAdapter(R.id.liste, b.build());
        rv.setPendingIntentTemplate(R.id.liste, vorlage(c, KALENDER));
        rv.setTextViewText(R.id.leer, leerText(c));
        rv.setEmptyView(R.id.liste, R.id.leer);
        return rv;
    }

    /* ---------- Teile ---------- */

    private static RemoteViews rahmen(Context c, int art, String titel, String zielKopf, String zielNeu, String neuText) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget);
        rv.setTextViewText(R.id.titel, titel);
        rv.setOnClickPendingIntent(R.id.kopf_text, appOeffnen(c, art + 1, zielKopf));
        rv.setOnClickPendingIntent(R.id.neu, appOeffnen(c, art + 2, zielNeu));
        rv.setContentDescription(R.id.neu, neuText);
        rv.setOnClickPendingIntent(R.id.aktualisieren, PendingIntent.getBroadcast(c, art + 3,
                new Intent(c, AktionReceiver.class).setAction(AktionReceiver.AKTUALISIEREN),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return rv;
    }

    private static RemoteViews nichtVerbunden(Context c, RemoteViews rv) {
        PendingIntent einrichten = PendingIntent.getActivity(c, 7, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // Getrennt: im Lifehub entfernt (oder überall abgemeldet). Aufgaben und Termine sind dann schon gelöscht.
        boolean getrennt = Konfig.getrennt(c);
        rv.setTextViewText(R.id.status, getrennt ? "Getrennt" : "Noch nicht verbunden");
        rv.setViewVisibility(R.id.liste, View.GONE);
        rv.setViewVisibility(R.id.leer, View.VISIBLE);
        rv.setTextViewText(R.id.leer, getrennt ? "Vom Lifehub getrennt. Antippen und neu verbinden."
                : "Antippen, um das Widget mit deinem Tagesplan zu verbinden.");
        rv.setOnClickPendingIntent(R.id.leer, einrichten);
        rv.setOnClickPendingIntent(R.id.kopf_text, einrichten);
        return rv;
    }

    private static RemoteViews zeile(Context c, Zeile z) {
        String pkg = c.getPackageName();
        switch (z.art) {
            case Zeile.ABSCHNITT: {
                RemoteViews r = new RemoteViews(pkg, R.layout.zeile_abschnitt);
                r.setTextViewText(R.id.text, z.text);
                r.setTextViewText(R.id.zahl, z.zahl > 0 ? String.valueOf(z.zahl) : "");
                return r;
            }
            case Zeile.AUFGABE: {
                RemoteViews r = new RemoteViews(pkg, R.layout.zeile_aufgabe);
                CharSequence t = z.text;
                if (z.erledigt) {
                    SpannableString s = new SpannableString(z.text);
                    s.setSpan(new StrikethroughSpan(), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    t = s;
                }
                r.setTextViewText(R.id.titel, t);
                r.setColor(R.id.titel, "setTextColor", z.erledigt ? R.color.ink3 : R.color.ink);
                r.setImageViewResource(R.id.haken, z.erledigt ? R.drawable.ic_haken : R.drawable.ic_kreis);
                r.setContentDescription(R.id.haken, (z.erledigt ? "Wieder öffnen: " : "Abhaken: ") + z.text);
                r.setColor(R.id.punkt, "setColorFilter", bereichFarbe(z.bereich));
                boolean frist = !z.frist.isEmpty(), info = !z.info.isEmpty();
                r.setViewVisibility(R.id.unten, frist || info ? View.VISIBLE : View.GONE);
                r.setViewVisibility(R.id.frist, frist ? View.VISIBLE : View.GONE);
                r.setTextViewText(R.id.frist, z.frist);
                r.setColor(R.id.frist, "setTextColor", z.erledigt ? R.color.ink3
                        : z.fristArt == Zeile.FRIST_SPAET ? R.color.spaet : z.fristArt == Zeile.FRIST_HEUTE ? R.color.heute : R.color.ink2);
                r.setViewVisibility(R.id.info, info ? View.VISIBLE : View.GONE);
                r.setTextViewText(R.id.info, (frist && info ? "· " : "") + z.info);
                r.setOnClickFillInIntent(R.id.haken, new Intent().putExtra("art", "abhaken").putExtra("id", z.id).putExtra("erledigt", !z.erledigt));
                r.setOnClickFillInIntent(R.id.text, new Intent().putExtra("art", "oeffnen").putExtra("ziel", z.ziel));
                return r;
            }
            case Zeile.TAG: {
                RemoteViews r = new RemoteViews(pkg, R.layout.zeile_tag);
                r.setTextViewText(R.id.text, z.text);
                r.setColor(R.id.text, "setTextColor", z.heute ? R.color.akzent : R.color.ink);
                r.setViewVisibility(R.id.frei, z.frei ? View.VISIBLE : View.GONE);
                r.setOnClickFillInIntent(R.id.zeile, new Intent().putExtra("art", "oeffnen").putExtra("ziel", z.ziel));
                return r;
            }
            case Zeile.TERMIN: {
                RemoteViews r = new RemoteViews(pkg, R.layout.zeile_termin);
                r.setTextViewText(R.id.zeit, z.zeit);
                r.setTextViewText(R.id.titel, z.text);
                r.setColor(R.id.titel, "setTextColor", z.vorbei ? R.color.ink3 : R.color.ink);
                r.setColor(R.id.zeit, "setTextColor", z.vorbei ? R.color.ink3 : R.color.ink2);
                r.setViewVisibility(R.id.info, z.info.isEmpty() ? View.GONE : View.VISIBLE);
                r.setTextViewText(R.id.info, z.info);
                r.setColor(R.id.balken, "setColorFilter", z.vorbei ? R.color.linie : kalenderFarbe(z.kalender));
                r.setOnClickFillInIntent(R.id.zeile, new Intent().putExtra("art", "oeffnen").putExtra("ziel", z.ziel));
                return r;
            }
            default: {
                RemoteViews r = new RemoteViews(pkg, R.layout.zeile_fuss);
                r.setTextViewText(R.id.text, z.text);
                return r;
            }
        }
    }

    /** Für alle Zeilen einer Liste. Ein Tipp schickt die Angaben der Zeile an KlickActivity. */
    private static PendingIntent vorlage(Context c, int art) {
        return PendingIntent.getActivity(c, art, new Intent(c, KlickActivity.class),
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent appOeffnen(Context c, int code, String ziel) {
        return PendingIntent.getActivity(c, code, Oeffnen.intent(c, ziel), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static String status(Context c, long stand) {
        if (Speicher.laeuft(c)) return "Aktualisiere …";
        String s = Logik.stand(stand, ZonedDateTime.now(Logik.TZ));
        String f = Speicher.fehler(c);
        if (f == null) return s;
        String ft = Api.wort(f);
        return s.isEmpty() ? ft : ft + " · " + s;
    }

    private static String leerText(Context c) {
        if (Speicher.laeuft(c)) return "Lädt …";
        return Speicher.fehler(c) != null ? "Noch keine Daten. Zum Aktualisieren oben ↻ antippen." : "Lädt …";
    }

    private static String verbinden(List<String> teile) {
        List<String> t = new ArrayList<>();
        for (String s : teile) if (s != null && !s.isEmpty()) t.add(s);
        return String.join(" · ", t);
    }

    private static int bereichFarbe(String b) {
        if (b == null) return R.color.a_inbox;
        switch (b) {
            case "zivi": return R.color.a_zivi;
            case "bewerbung": return R.color.a_bewerbung;
            case "pfadi": return R.color.a_pfadi;
            case "projekte": return R.color.a_projekte;
            case "privat": return R.color.a_privat;
            default: return R.color.a_inbox;
        }
    }

    private static int kalenderFarbe(String k) {
        if (k == null) return R.color.ink3;
        switch (k) {
            case "haupt": return R.color.akzent;
            case "familie": return R.color.a_pfadi;
            case "feiertage": return R.color.heute;
            case "lauener": return R.color.a_privat;
            default: return R.color.ink3;
        }
    }
}
