# Splitt-Tool

Teilt große PDFs (bis 1 GB) kapitelweise in Teile, die in Claude hochgeladen werden können.
Seiten werden 1:1 übernommen, nichts wird verändert oder erfunden; jeder Teil wird bitgenau
mit dem Original verglichen.

- **Android-App:** die neueste APK gibt es unter
  Ordner [`dist/`](dist/) (die Datei mit der höchsten Versionsnummer).
  Die App kann sich über den Knopf „Nach Update suchen“ selbst aktualisieren.
- **PC-Werkzeug (Windows/Mac/Linux):** Ordner [`pc-tool/`](pc-tool/README.md).

## Funktionen der App

- PDF wählen (oder aus „Eigene Dateien“ an die App teilen), Zielordner wählen, „Aufteilen“.
- **Vorschau:** zeigt vorher alle erkannten Kapitel und die geplanten Teile mit Seiten und Größe.
- Kapitel aus den Lesezeichen der PDF, sonst aus Überschriften, sonst gleich große Seitenblöcke.
- Grenzen einstellbar (Standard 30 MB / 100 Seiten; Claude erlaubt im Chat max. 500 MB und
  1000 Seiten, Bilder werden nur bis 100 Seiten ausgewertet).
- Ergebnisordner mit den Teilen und `00_UEBERSICHT.md` (Kapitel je Teil + Begleittext für den Chat).

## Bauen

Ohne Gradle, mit den Debian/Ubuntu-Paketen `android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner`:

```
KEYSTORE=/pfad/zum/keystore.jks ./build.sh
```

Neue Version veröffentlichen: `version.txt` erhöhen, dann `./release.sh "Was ist neu"`.
Der Signierschlüssel ist nicht im Repository; Updates müssen immer mit demselben Schlüssel signiert werden.
