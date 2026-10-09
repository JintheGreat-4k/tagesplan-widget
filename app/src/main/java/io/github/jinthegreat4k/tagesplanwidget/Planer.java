package io.github.jinthegreat4k.tagesplanwidget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.time.ZonedDateTime;

/** Wann die Widgets neu laden: alle 30 Min., nach Antippen sofort, kurz nach Mitternacht. */
final class Planer {
    static final int JOB_REGELMAESSIG = 1, JOB_JETZT = 2;
    private static final long MIN = 60_000L;
    private static final long ALT_MS = 15 * MIN;

    private Planer() {}

    /** Ein Widget wurde hingelegt oder der Launcher fragt nach: Zeitplan sicherstellen, alte Daten auffrischen */
    static void start(Context c) {
        if (!Konfig.da(c)) return;
        regelmaessig(c);
        mitternacht(c);
        long stand = Math.min(Speicher.standAufgaben(c), Speicher.standTermine(c));
        if (System.currentTimeMillis() - stand > ALT_MS) jetzt(c);
    }

    static void regelmaessig(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_REGELMAESSIG) != null) return;
        js.schedule(new JobInfo.Builder(JOB_REGELMAESSIG, new ComponentName(c, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(30 * MIN, 10 * MIN)
                .setPersisted(true)
                .build());
    }

    static void jetzt(Context c) {
        if (!Konfig.da(c)) return;
        if (SyncJob.laeuft()) {
            SyncJob.nochmal();
            return;
        }
        JobScheduler js = c.getSystemService(JobScheduler.class);
        JobInfo.Builder b = new JobInfo.Builder(JOB_JETZT, new ComponentName(c, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        try {
            if (js.schedule(b.setExpedited(true).build()) == JobScheduler.RESULT_SUCCESS) return;
        } catch (RuntimeException e) {
            Log.w(Speicher.TAG, "Sofort-Abgleich nicht möglich", e);
        }
        js.schedule(b.setExpedited(false).build());
    }

    /** Kurz nach Mitternacht neu zeichnen, damit «Heute» stimmt (ungefähr, ohne exakten Wecker) */
    static void mitternacht(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        ZonedDateTime t = ZonedDateTime.now(Logik.TZ).toLocalDate().plusDays(1).atStartOfDay(Logik.TZ).plusMinutes(1);
        am.setAndAllowWhileIdle(AlarmManager.RTC, t.toInstant().toEpochMilli(), mitternachtIntent(c));
    }

    static boolean widgetsDa(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        return m.getAppWidgetIds(new ComponentName(c, AufgabenWidget.class)).length > 0
                || m.getAppWidgetIds(new ComponentName(c, KalenderWidget.class)).length > 0;
    }

    /** Kein Widget mehr auf dem Startbildschirm oder Verbindung getrennt */
    static void stoppen(Context c) {
        c.getSystemService(JobScheduler.class).cancelAll();
        c.getSystemService(AlarmManager.class).cancel(mitternachtIntent(c));
    }

    private static PendingIntent mitternachtIntent(Context c) {
        return PendingIntent.getBroadcast(c, 1, new Intent(c, AktionReceiver.class).setAction(AktionReceiver.MITTERNACHT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
