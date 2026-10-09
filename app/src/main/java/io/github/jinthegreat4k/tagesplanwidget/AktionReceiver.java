package io.github.jinthegreat4k.tagesplanwidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Knopf ↻, Wecker kurz nach Mitternacht, Neustart des Handys und Update der App */
public final class AktionReceiver extends BroadcastReceiver {
    static final String AKTUALISIEREN = "io.github.jinthegreat4k.tagesplanwidget.AKTUALISIEREN";
    static final String MITTERNACHT = "io.github.jinthegreat4k.tagesplanwidget.MITTERNACHT";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        if (!Konfig.da(c)) {
            Darstellung.alle(c);
            return;
        }
        switch (a) {
            case AKTUALISIEREN:
                // Sofort zeigen, dass etwas passiert. Der Abgleich startet, sobald Internet da ist.
                Speicher.laeuft(c, true);
                Darstellung.alle(c);
                Planer.jetzt(c);
                break;
            case MITTERNACHT:
                Darstellung.alle(c);
                Planer.mitternacht(c);
                Planer.jetzt(c);
                break;
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                if (!Planer.widgetsDa(c)) return;
                Darstellung.alle(c);
                Planer.regelmaessig(c);
                Planer.mitternacht(c);
                Planer.jetzt(c);
                break;
            default:
                break;
        }
    }
}
