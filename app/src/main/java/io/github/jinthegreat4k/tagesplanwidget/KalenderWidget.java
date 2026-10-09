package io.github.jinthegreat4k.tagesplanwidget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Widget «Kalender»: Termine von heute und den nächsten 6 Tagen */
public final class KalenderWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Darstellung.kalender(c, ids);
        Planer.start(c);
    }

    @Override
    public void onDisabled(Context c) {
        if (!Planer.widgetsDa(c)) Planer.stoppen(c);
    }
}
