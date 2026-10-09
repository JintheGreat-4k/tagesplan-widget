package io.github.jinthegreat4k.tagesplanwidget;

/** Eine Zeile in einer Widget-Liste. Welche Felder gelten, hängt von der Art ab. */
final class Zeile {
    static final int ABSCHNITT = 0, AUFGABE = 1, TAG = 2, TERMIN = 3, FUSS = 4;
    static final int FRIST_KEINE = 0, FRIST_NORMAL = 1, FRIST_HEUTE = 2, FRIST_SPAET = 3;

    final int art;
    final String schluessel; // für stabile IDs in der Liste
    String text = "";

    // Abschnitt
    int zahl = -1;

    // Aufgabe
    String id;
    String bereich;
    String frist = "";
    int fristArt = FRIST_KEINE;
    String info = "";
    boolean erledigt;

    // Tag
    boolean heute;
    boolean frei;

    // Termin
    String zeit = "";
    boolean vorbei;
    String kalender;

    // Aufgabe, Tag, Termin: Sprungziel in der Web-App (nach dem #)
    String ziel;

    Zeile(int art, String schluessel) {
        this.art = art;
        this.schluessel = schluessel;
    }

    /** Stabile 64-Bit-ID aus dem Schlüssel (FNV-1a) */
    long nummer() {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < schluessel.length(); i++) {
            h ^= schluessel.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
