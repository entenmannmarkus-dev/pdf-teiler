# Claude PDF-Splitter

Teilt große PDF-Dateien (getestet mit fast 500 MB, ausgelegt für 1000 MB und mehr) in
Teile auf, die du einzeln in einen Claude-Chat oder ein Claude-Projekt hochladen kannst.

- **Kapitelweise:** Die Teile werden an Kapitelgrenzen geschnitten. Die Kapitel kommen aus
  den Lesezeichen (Inhaltsverzeichnis) der PDF. Fehlen die, sucht das Tool Überschriften wie
  „Kapitel 3 …“ im Text. Gibt es auch die nicht, teilt es in gleich große Seitenblöcke.
- **Beschreibung:** Jeder Dateiname nennt Teilnummer, Seiten und Kapitel, z. B.
  `Buch_Teil03von12_S181-260_Kapitel_4_Die_Reise.pdf`. Dazu kommen `00_UEBERSICHT.md`
  (Tabelle aller Teile mit Kapiteln und fertigen Begleittexten zum Einfügen in den Chat) und
  `00_uebersicht.json`. Titel, Betreff und Lesezeichen jeder Teil-PDF nennen ebenfalls die Kapitel.
- **Inhalt bleibt unverändert:** Seiten werden 1:1 kopiert. Texte, Bilder und Schriften werden
  weder neu komprimiert noch verkleinert, umgeschrieben oder ergänzt. Nach dem Schreiben vergleicht
  das Tool jede Seite jedes Teils bitgenau mit dem Original (Prüfsummen aller Inhalts-, Bild- und
  Schriftdaten) und prüft, dass jede Seite genau einmal und in der richtigen Reihenfolge vorkommt.
  Das Ergebnis steht in der Übersicht.
- **Bildlastige Bücher:** Die Größe jedes Teils wird aus den tatsächlich benötigten Bildern und
  Schriften berechnet; gemeinsam genutzte Objekte zählen nur einmal. Ist eine einzelne Seite allein
  größer als die Grenze, bleibt sie trotzdem unverändert (eigener Teil) und es gibt eine Warnung.
- **Speicherschonend:** Die Quelldatei wird nie komplett in den Arbeitsspeicher geladen.
  Test: 459 MB Bilderbuch, 400 Seiten → 22 Teile in 6 Sekunden, max. ca. 240 MB RAM.

## Welche Grenzen gelten bei Claude?

Laut [Claude-Hilfe „Upload files to Claude“](https://support.claude.com/en/articles/8241126-upload-files-to-claude) (Stand November 2026):

| | Grenze |
|---|---|
| Datei im Chat | bis 500 MB, bis 20 Dateien pro Chat, PDFs bis 1000 Seiten |
| Datei im Projekt | bis 30 MB pro Datei |
| Bilder/Grafiken in PDFs | werden **nur bei PDFs mit höchstens 100 Seiten** ausgewertet, darüber nur Text |
| Kontextfenster | alles Hochgeladene muss zusätzlich in Claudes Kontext (Token) passen |

**Standard des Tools: höchstens 30 MB und 100 Seiten pro Teil** (mit 5 % Sicherheitsabstand
zur MB-Grenze). Damit passt jeder Teil in Chats *und* Projekte, und Claude sieht auch die Bilder.
Alle Grenzen sind einstellbar, falls Claude sie ändert.

Tipp: Lade für gute Antworten nur 1–2 Teile pro Chat hoch. Bei sehr textreichen Büchern kann
man zusätzlich `--max-token` setzen (z. B. 120000), dann werden die Teile auch nach geschätzter
Textmenge begrenzt.

## Installation

Benötigt wird Python 3.9 oder neuer (Windows: von [python.org](https://www.python.org/downloads/),
beim Installieren „Add Python to PATH“ anhaken).

```
pip install -r requirements.txt
```

Das einzige Zusatzpaket ist `pypdf`. Die Oberfläche nutzt das in Python enthaltene tkinter
(unter Linux ggf. `sudo apt install python3-tk`).

## Benutzung

### Ohne Kommandozeile (Windows)

- **`Oberflaeche_starten.bat`** doppelklicken → PDF auswählen → „Aufteilen“.
- Oder eine PDF mit der Maus auf **`PDF_hier_draufziehen.bat`** ziehen. Die Teile landen im
  Ordner `<Dateiname>_teile` neben der PDF.

Mac/Linux: `./oberflaeche_starten.sh`

### Kommandozeile

```
python pdf_splitter.py MeinBuch.pdf
python pdf_splitter.py MeinBuch.pdf -o D:\Teile --max-mb 25 --max-seiten 80
python pdf_splitter.py MeinBuch.pdf --nur-analyse          # nur Kapitel anzeigen
python pdf_splitter.py MeinBuch.pdf --ein-kapitel-pro-teil # Kapitel nie zusammenlegen
```

| Option | Bedeutung | Standard |
|---|---|---|
| `-o, --ausgabe` | Ausgabeordner | `<name>_teile` neben der PDF |
| `--max-mb` | max. Dateigröße pro Teil | 30 |
| `--max-seiten` | max. Seiten pro Teil | 100 |
| `--max-token` | max. geschätzte Token pro Teil (liest Text, etwas langsamer) | aus |
| `--sicherheit` | Abstand zur MB-Grenze (0.05 = 5 %) | 0.05 |
| `--kapitel-ebene` | welche Lesezeichen-Ebene als Kapitel gilt (1 = oberste) | automatisch |
| `--ein-kapitel-pro-teil` | jedes Kapitel in eigene Datei(en) | aus |
| `--keine-erkennung` | keine Überschriftensuche, wenn Lesezeichen fehlen | aus |
| `--ohne-lesezeichen` | keine Lesezeichen in die Teile schreiben | aus |
| `--ohne-pruefung` | bitgenauen Vergleich mit dem Original überspringen | aus |
| `--passwort` | Passwort für geschützte PDFs | – |
| `--nur-analyse` | nur Kapitelliste anzeigen, nichts schreiben | – |

## So wird aufgeteilt

1. Kapitel ermitteln (Lesezeichen → Überschriften → Seitenblöcke). Seiten vor dem ersten Kapitel
   heißen „Vorspann (Seiten vor dem ersten Kapitel)“. Kapitelnamen werden nur aus der PDF
   übernommen, nie erfunden.
2. Jede Seite einmal analysieren: welche Bilder, Schriften und Inhalte sie braucht und wie groß sie sind.
3. Kapitel, die zu groß sind, werden zuerst an ihren Unterkapiteln geteilt, sonst in gleichmäßige
   Abschnitte („Kapitel 4 (Abschnitt 2/3)“).
4. Kleine aufeinanderfolgende Kapitel werden zu einem Teil zusammengefasst, solange alle Grenzen
   eingehalten sind.
5. Teile schreiben, Größe nachmessen (falls doch zu groß: automatisch neu aufteilen), jede Seite
   mit dem Original vergleichen, Übersicht schreiben.

Die Reihenfolge der Seiten bleibt immer erhalten; jede Seite steht in genau einem Teil.

## Grenzen des Tools

- Eine **einzelne Seite**, die größer als die MB-Grenze ist (z. B. ein riesiger Scan), kann nicht
  ohne Veränderung verkleinert werden. Sie wird unverändert als eigener Teil gespeichert und in der
  Übersicht als Warnung gemeldet. Im Chat (bis 500 MB) lässt sie sich trotzdem hochladen.
- Bei **reinen Scans ohne Text und ohne Lesezeichen** gibt es keine Kapitelnamen. Dann wird in
  gleich große Seitenblöcke geteilt.
- Formularfelder und Links, die auf andere Teile verweisen, funktionieren im Teil nicht mehr; der
  sichtbare Seiteninhalt bleibt gleich.

## Selbsttest

```
python -m unittest discover -s tests
```
