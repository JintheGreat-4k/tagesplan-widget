# Tagesplan Widget

Zwei Startbildschirm-Widgets für Marcs Tagesplan (Android 12 oder neuer). Der Tagesplan selbst läuft seit Version 1.1 im Lauener Lifehub; die Widgets öffnen ihn dort.

- **Kalender:** Termine von heute und den nächsten 6 Tagen. Antippen öffnet den Tag in der App, «+» erfasst einen Termin.
- **Aufgaben:** offene Aufgaben wie unter «Heute» in der App (Fokus, Auch fällig, Nächste 7 Tage, Eingang, Weitere). Der Kreis links hakt ab, der Text öffnet die Aufgabe in der App, «+» erfasst eine neue.

Beide sind standardmässig 4×3 gross und lassen sich auf einem Samsung-Startbildschirm stapeln.

## Wie es funktioniert

Die Widgets holen ihre Daten beim selben Google-Skript wie die Web-App: alle 30 Minuten, nach dem Abhaken und nach ↻ sofort, kurz nach Mitternacht. Adresse und Schlüssel bekommt die App einmal über einen Einrichtungslink (`#u=…&k=…`). Sie liegen nur im privaten Speicher der App auf dem Handy. Abgehaktes ohne Internet wird später gesendet.

In diesem Repo sind keine Daten und keine Schlüssel.

## Installieren

1. Auf dem Handy [die neueste Version](https://github.com/JintheGreat-4k/tagesplan-widget/releases/latest/download/tagesplan-widget.apk) herunterladen.
2. Die Datei öffnen und die Installation erlauben (Android fragt einmal, ob Chrome Apps installieren darf).
3. Den Einrichtungslink in der Widget-App einfügen und «Mit Link verbinden» antippen. Ein Update über eine schon verbundene App behält die Verbindung.

## Bauen

GitHub Actions baut bei jedem Push, prüft die Logik (JUnit) und testet die App in einem Android-Emulator gegen ein nachgebildetes Skript mit erfundenen Daten (`tools/`). Die Bildschirmfotos liegen im Artefakt «bilder». Ein Tag `v…` veröffentlicht die signierte App als Release.
