package io.github.jinthegreat4k.tagesplanwidget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Widget «Aufgaben»: offene Aufgaben wie unter «Heute» in der App, zum Abhaken */
public final class AufgabenWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Darstellung.aufgaben(c, ids);
        Planer.start(c);
    }

    @Override
    public void onDisabled(Context c) {
        if (!Planer.widgetsDa(c)) Planer.stoppen(c);
    }
}
