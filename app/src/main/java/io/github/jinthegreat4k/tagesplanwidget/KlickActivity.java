package io.github.jinthegreat4k.tagesplanwidget;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Antippen einer Zeile im Widget. Zeigt kein Fenster: abhaken und neu zeichnen oder die App öffnen, dann sofort zu. */
public final class KlickActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        String art = i.getStringExtra("art");
        if ("abhaken".equals(art)) {
            String id = i.getStringExtra("id");
            if (id != null && Konfig.da(this)) {
                Speicher.abhaken(this, id, i.getBooleanExtra("erledigt", true));
                Darstellung.aufgaben(this, null);
                Planer.jetzt(this);
            }
        } else if ("oeffnen".equals(art)) {
            Oeffnen.starten(this, i.getStringExtra("ziel"));
        }
        finish();
    }
}
