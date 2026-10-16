# Tagesplan Widget

Zwei Startbildschirm-Widgets für Marcs Tagesplan (Android 12 oder neuer). Der Tagesplan selbst läuft im Lauener Lifehub; die Widgets öffnen ihn dort.

- **Kalender:** Termine von heute und den nächsten 6 Tagen. Antippen öffnet den Tag in der App, «+» erfasst einen Termin.
- **Aufgaben:** offene Aufgaben wie unter «Heute» in der App (Fokus, Auch fällig, Nächste 7 Tage, Eingang, Weitere). Der Kreis links hakt ab, der Text öffnet die Aufgabe in der App, «+» erfasst eine neue.

Beide sind standardmässig 4×3 gross und lassen sich auf einem Samsung-Startbildschirm stapeln.

## Wie es funktioniert

Seit Version 1.2 holen die Widgets ihre Daten über den Lifehub (lauener.online), nie direkt beim Google-Skript: alle 30 Minuten, nach dem Abhaken und nach ↻ sofort, kurz nach Mitternacht. Abgehaktes ohne Internet wird später gesendet.

Zum Verbinden holt man im Lifehub einen Widget-Code (8 Zeichen, 10 Minuten gültig, nur einmal brauchbar). Die App tauscht ihn gegen einen eigenen Zugang, der nur Laden, Termine und Abhaken erlaubt. Dieser liegt nur im privaten Speicher der App auf dem Handy (nicht in der Sicherung, nicht beim Umzug auf ein neues Handy) und lässt sich im Lifehub jederzeit entfernen. Adresse und Schlüssel des Google-Skripts kennt nur der Lifehub.

In diesem Repo sind keine Daten und keine Schlüssel.

## Installieren

1. Auf dem Handy [die neueste Version](https://github.com/JintheGreat-4k/tagesplan-widget/releases/latest/download/tagesplan-widget.apk) herunterladen, die Datei öffnen und installieren (Android fragt einmal, ob Chrome Apps installieren darf). Ist schon eine ältere Version da: «Aktualisieren». Die Widgets auf dem Startbildschirm bleiben.
2. Im Lifehub auf dem Handy: Tagesplan → Zahnrad → «Widget-App verbinden» (fragt vielleicht zuerst nach Passwort und Fingerabdruck) → «Widget-App öffnen» → in der Widget-App «Verbinden».
3. Öffnet sich die Widget-App dabei nicht: sie selbst öffnen («Tagesplan Widget»), den Widget-Code aus dem Lifehub eintippen und «Mit Code verbinden» antippen.

Von Version 1.0 oder 1.1: Die alte Verbindung (Einrichtungslink mit Adresse und Schlüssel des Google-Skripts) gilt nicht mehr und wird beim ersten Start gelöscht. Einmal neu verbinden wie oben.

## Trennen

- **In der Widget-App:** «Verbindung trennen». Die App meldet sich im Lifehub ab (wenn das Handy gerade Internet hat; sonst bleibt die Zeile dort stehen und lässt sich entfernen) und löscht die gespeicherten Aufgaben und Termine sofort. Die Widgets zeigen danach «Noch nicht verbunden».
- **Im Lifehub:** auf der Startseite unter «Anmeldung und Sicherheit» → «Widget-App» → «Entfernen» (z. B. wenn das Handy verloren ist). Abmelden auf allen Geräten, ein neues Passwort oder ein entferntes Gerät trennen die Widget-App ebenfalls. Das Handy merkt es beim nächsten Abgleich: meist innerhalb von 30 Minuten, sofort nach ↻. Auch die Widget-App gleicht beim Öffnen ab, wenn der letzte Abgleich über 2 Minuten her ist. Dann zeigen die Widgets «Getrennt» und die gespeicherten Aufgaben und Termine werden gelöscht. Bis dahin zeigen sie noch den letzten Stand.

Neu verbinden wie unter «Installieren», Schritt 2 oder 3. Das Feld für den Widget-Code steht in der Widget-App auch, solange sie sich noch für verbunden hält.

## Bauen

GitHub Actions baut bei jedem Push, prüft die Logik (JUnit) und testet die App in einem Android-Emulator gegen einen nachgebildeten Lifehub mit erfundenen Daten (`tools/`): verbinden mit einem Test-Code, abhaken und wieder öffnen, im Lifehub entfernen, neu verbinden, in der App trennen. Die Bildschirmfotos liegen im Artefakt «bilder». Ein Tag `v…` veröffentlicht die signierte App als Release.
