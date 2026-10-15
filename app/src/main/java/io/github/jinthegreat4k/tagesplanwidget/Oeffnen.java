package io.github.jinthegreat4k.tagesplanwidget;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;

import java.util.regex.Pattern;

/** Öffnet den Tagesplan im Lauener Lifehub, wenn möglich direkt die installierte App statt eines Chrome-Tabs. */
final class Oeffnen {
    // Sprungziele, die die Web-App kennt (nach dem #)
    private static final Pattern ZIEL = Pattern.compile("^(heute|neu|termin|kalender(=\\d{4}-\\d{2}-\\d{2})?|aufgabe=[\\w.:@-]{1,200})$");

    private Oeffnen() {}

    static Intent intent(Context c, String ziel) {
        String z = ziel != null && ZIEL.matcher(ziel).matches() ? ziel : "";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(Konfig.APP_URL + (z.isEmpty() ? "" : "#" + z)));
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String pkg = webApk(c, i);
        if (pkg != null) i.setPackage(pkg);
        return i;
    }

    static void starten(Context c, String ziel) {
        try {
            c.startActivity(intent(c, ziel));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(c, "Kein Browser gefunden.", Toast.LENGTH_LONG).show();
        }
    }

    /** Die von Chrome installierte Web-App (Paket org.chromium.webapk…), falls vorhanden */
    private static String webApk(Context c, Intent i) {
        for (ResolveInfo r : c.getPackageManager().queryIntentActivities(i, PackageManager.MATCH_ALL)) {
            String p = r.activityInfo != null ? r.activityInfo.packageName : null;
            if (p != null && p.startsWith("org.chromium.webapk")) return p;
        }
        return null;
    }
}
