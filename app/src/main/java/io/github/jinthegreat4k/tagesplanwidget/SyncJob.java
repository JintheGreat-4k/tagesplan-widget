package io.github.jinthegreat4k.tagesplanwidget;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Führt den Abgleich im Hintergrund aus. Android startet ihn nur mit Internet. */
public final class SyncJob extends JobService {
    private static final AtomicInteger LAEUFT = new AtomicInteger();
    private static final AtomicBoolean NOCHMAL = new AtomicBoolean();

    static boolean laeuft() {
        return LAEUFT.get() > 0;
    }

    /** Während eines Abgleichs kam etwas dazu (z. B. nochmals abgehakt): danach gleich nochmals */
    static void nochmal() {
        NOCHMAL.set(true);
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        Context c = getApplicationContext();
        LAEUFT.incrementAndGet();
        new Thread(() -> {
            boolean wartet = false;
            try {
                do {
                    NOCHMAL.set(false);
                    wartet = Sync.ausfuehren(c);
                } while (NOCHMAL.get() && !wartet);
            } catch (RuntimeException e) {
                Log.e(Speicher.TAG, "Abgleich abgebrochen", e);
            } finally {
                boolean danach = LAEUFT.decrementAndGet() == 0 && NOCHMAL.getAndSet(false);
                jobFinished(p, wartet && p.getJobId() == Planer.JOB_JETZT);
                if (danach) Planer.jetzt(c);
            }
        }, "tagesplan-abgleich").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        // Android bricht ab (z. B. Netz weg): Sofort-Abgleich später wiederholen
        return p.getJobId() == Planer.JOB_JETZT;
    }
}
