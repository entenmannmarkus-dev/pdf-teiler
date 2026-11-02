# Teams-Chat im Splitt-Tool einrichten

Der Teams-Chat in der App nutzt Microsoft Graph. Das geht nur mit einem **Arbeits- oder Schulkonto**
(Microsoft 365), nicht mit privatem Teams. Die App speichert kein Passwort, nur das Anmelde-Token auf dem Tablet.

## 1. App bei Microsoft registrieren (einmalig, ca. 5 Minuten)

Du brauchst dafür Zugriff auf das Microsoft-Entra-Portal. Fehlt dir das, schick diese Anleitung an deine IT.

1. In Chrome <https://entra.microsoft.com> öffnen und mit dem Arbeitskonto anmelden.
2. **Anwendungen → App-Registrierungen → Neue Registrierung**.
   - Name: `Splitt-Tool`
   - Unterstützte Kontotypen: **Nur Konten in diesem Organisationsverzeichnis**
   - Umleitungs-URI: leer lassen → **Registrieren**.
3. Auf der Übersichtsseite die **Anwendungs-ID (Client-ID)** kopieren. Optional auch die **Verzeichnis-ID (Mandanten-ID)**.
4. **Authentifizierung** → ganz unten **Öffentliche Clientflows zulassen: Ja** → Speichern.
5. **API-Berechtigungen → Berechtigung hinzufügen → Microsoft Graph → Delegierte Berechtigungen**:
   `User.Read`, `Chat.ReadWrite`, `offline_access` → Hinzufügen.
6. Wenn dein Unternehmen es verlangt: **Administratorzustimmung erteilen** (macht die IT).

## 2. In der App

1. **Teams-Chat öffnen** → Anwendungs-ID (und ggf. Verzeichnis-ID) einfügen → **Speichern**.
2. **Anmelden** → **Code kopieren und Microsoft in Chrome öffnen** → Code einfügen, mit dem Arbeitskonto anmelden.
3. Zurück in die App: Deine Chats erscheinen. Antippen zum Lesen, unten schreiben und **Senden**.
   Neue Nachrichten werden alle 10 Sekunden geladen, solange der Chat offen ist.

Dateien (die PDF-Teile) schickst du nach dem Aufteilen mit **„An Teams senden“**.

## Typische Meldungen

- *Unternehmen muss den Zugriff freigeben* → Schritt 1.6 durch die IT.
- *Öffentliche Clientflows zulassen* → Schritt 1.4.
- *Anwendungs-ID ist falsch* → ID aus Schritt 1.3 prüfen bzw. Verzeichnis-ID eintragen.
