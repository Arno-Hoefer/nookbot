# NookBot

**Ein kleines Team aus KI-Bots auf dem eigenen Raspberry Pi – mit eigener Android-App.**
*A small team of AI bots on your own Raspberry Pi, with its own Android app.*

Ein Kapitän-Bot beantwortet Fragen, schaut in Kalender, Mail, Slack, GitLab und Datenbank, recherchiert im Web und gibt
Aufgaben an spezialisierte Bots weiter (Recherche, Gegenposition, Essensplan …). Mehrere Bots können in einer **Runde**
dieselbe Frage beantworten, der Kapitän fasst zusammen. Alles läuft auf einem Raspberry Pi 5 zu Hause, die Modelle kommen
über [Ollama](https://ollama.com) (Cloud), erreichbar nur im eigenen [Tailscale](https://tailscale.com)-Netz.

<p>
  <img src="docs/screenshots/start.png" width="200" alt="Startseite: Kapitän oben, darunter die Bots und Runden">
  <img src="docs/screenshots/chat.png" width="200" alt="Chat mit dem Kapitän: Werkzeuge, Tabelle, Erinnerung">
  <img src="docs/screenshots/runde.png" width="200" alt="Runde: zwei Bots antworten, der Kapitän fasst zusammen">
  <img src="docs/screenshots/bot.png" width="200" alt="Bot-Einstellungen: Name, Rolle, Aussehen, Modell, Persönlichkeit">
</p>
<p>
  <img src="docs/screenshots/start-dunkel.png" width="200" alt="Startseite im Dunkelmodus">
  <img src="docs/screenshots/chat-dunkel.png" width="200" alt="Chat im Dunkelmodus">
</p>

*(Screenshots mit Demo-Daten.)*

## Was es kann

- **Ein Bot, eine Unterhaltung** – jeder Bot hat Name, Rolle, Farbe, Form, Modell, **Persönlichkeit**, **Fokus** und eigenes **Wissen** (Text- und PDF-Dateien; dazu globales Wissen für alle).
- **Der Kapitän legt Bots an**: „Leg einen Bot an, der mir jede Woche Rezepte vorschlägt“ – fertig.
- **Bots arbeiten einander zu** (`bot_fragen`), mit Schutz gegen Endlosketten; **Runden** mit Zusammenfassung.
- **Aufgaben verteilen**: Der Kapitän gibt bis zu vier Bots gleichzeitig je einen Teilauftrag (`auftraege_verteilen`) und fasst die Ergebnisse zusammen – im Chat als Karte mit dem Stand je Bot.
- **Gedächtnis**: Bots merken sich Vorlieben und Absprachen (`merken`/`vergessen`) – nur aus den eigenen Nachrichten des Nutzers, nie aus Mails oder Webseiten. Jeder Eintrag erscheint im Chat (mit „Rückgängig“) und ist in der App bearbeitbar.
- **Abläufe**: „Speicher das als Ablauf“ – der Bot fasst den Weg aus dem Gespräch als Entwurf zusammen; erst nach dem OK in der App wird daraus ein Skill, auf Wunsch auch als Routine geplant.
- **Kontext lesen**: öffentliche Webseiten und PDFs (`seite_lesen`, ohne Anmeldung, nie Adressen aus Heimnetz oder Tailnet; Seiten, die erst im Browser entstehen, über einen Headless-Browser auf dem Pi).
- **Schreiben nur mit Freigabe**: Termine, Einladungen und Mail-Entwürfe werden erst ausgeführt, wenn man in der App zustimmt.
- **Zahlen nur aus SQL**: feste, beschriebene Abfragen (Bereich *Datenbanken* in der App), Rechnen und Prognosen per SQL – das Modell rechnet nicht im Kopf.
- **Routinen** (ein Bot erledigt etwas zu einer Uhrzeit) und **Erinnerungen** (pünktlich als Wecker auf dem Handy) – beides auch vom Bot selbst angelegt.
- **Sprachnotizen**: auf dem Handy aufnehmen und **lokal** transkribieren (whisper.cpp) – das Audio verlässt das Gerät nicht.
- Hell/Dunkel, Schriftgröße, Suche über alle Unterhaltungen, Benachrichtigungen ohne Dauerverbindung.
- **Web-Oberfläche** für den Rechner: dieselben Unterhaltungen im Browser (nur im Tailnet, gleicher Schlüssel wie die App).

## Aufbau

```
App (Kotlin/Compose)  ──HTTPS im Tailnet──▶  nookbot-adapter (Python, nur Standardbibliothek)
                                               │  WebSocket je Unterhaltung
                                               ├──▶ PicoClaw „Kapitän“      (~8 MB RAM)
                                               └──▶ PicoClaw je weiterem Bot (~8 MB RAM)
                                                          │ MCP
                                                          ▼
                                         Zugänge zu Kalender, Mail, Slack, GitLab, Datenbank
                                         (eigener MCP-Server, nicht Teil dieses Repos)
```

- **Bots:** je ein [PicoClaw](https://github.com/sipeed/picoclaw)-Prozess (Go, v0.3.1). Ursprünglich OpenClaw – das brauchte im Leerlauf 1,3 GB RAM, PicoClaw etwa 8 MB.
- **Adapter** (`pi/adapter/`): speichert jede Nachricht (nichts geht verloren, wenn die App zu ist), liefert Ereignisse per Server-Sent Events, verwaltet Bots, Wissen, Datenbank-Bibliothek, Routinen, Runden und Erinnerungen und stellt den Bots eigene MCP-Werkzeuge bereit (`bot_fragen`, `auftraege_verteilen`, `bot_anlegen`, `routine_anlegen`, `erinnerung_anlegen`, `seite_lesen`, `merken`,
  `ablauf_vorschlagen` …). Das Gedächtnis schreibt der Adapter als `memory/MEMORY.md`, bestätigte Abläufe als Skills `ablauf-<id>` –
  PicoClaw liest beides bei jeder Antwort neu, ohne Neustart. Unter `/` liefert er die Web-Oberfläche (`pi/adapter/web/`).
- **App** (`android/`): Kotlin + Jetpack Compose, Schlüssel im Android-Keystore, whisper.cpp per NDK (nur arm64).

### Eigenheiten von PicoClaw, die der Adapter ausgleicht

- Ohne Drossel schickt PicoClaw bei jedem Zeichen den ganzen Text neu – die Verbindung zum Handy erstickt daran (`streaming.throttle_seconds 1`, `min_growth_chars 60`).
- „typing.stop“ kommt auch zwischendurch (Werkzeuge, letzter Text danach) – fertig ist eine Antwort erst, wenn die letzte Nachricht Antworttext ist und 3 s Ruhe herrscht.
- PicoClaws eigener Zeitplaner liefert nur an offene Verbindungen – Zeitpläne laufen deshalb über die Routinen des Adapters.
- PicoClaw verbindet sich nur beim Start mit MCP-Servern – der Adapter lauscht deshalb, bevor er die Bots (neu) startet.

## Einrichten

Voraussetzungen: Raspberry Pi (getestet: Pi 5, 4 GB, Raspberry Pi OS / Debian 12), Tailscale, ein Ollama-Konto.
Die Skripte unter `pi/einrichtung/` in dieser Reihenfolge ausführen (Keys werden verdeckt abgefragt, nie im Repo):

1. `1-picoclaw-installieren.sh` – PicoClaw als eigener Benutzer, Prüfsumme wird geprüft
2. `2-modell-und-websuche.sh` – Ollama-Modell und Brave-Websuche, Shell/Skill-Installation aus
3. `3-picoclaw-dienst.sh` – systemd-Dienst, abgeschottet (`pi/kapitaen/USER.example.md` → `USER.md`)
4. `4-home-mcp-anbinden.sh` – optional: eigener MCP-Server für Kalender/Mail/Daten
5. `5-chat-kanal.sh` – WebSocket-Kanal mit Token (zugleich der Schlüssel der App)
6. `6-adapter.sh` – Adapter, Vorlage für weitere Bots, Polkit-Regel, Tailnet-Freigabe
7. `nutzer-setzen.py <Name>` – wie die Bots dich nennen

App bauen: `android/local.properties.example` nach `local.properties` kopieren, Adressen eintragen, dann
`./gradlew assembleRelease`. Für Kalender, Mail usw. braucht es einen MCP-Server mit Freigabe-Schnittstelle –
ohne ihn laufen Websuche, Wissen, Routinen, Erinnerungen und das Bot-Team trotzdem.

## Sicherheit

Nur im Tailnet erreichbar (kein öffentlicher Zugang), keine Messenger-Kanäle, keine Shell für die Bots, keine fremden Skills.
Inhalte aus Mails, Web und Dateien gelten als Daten, nicht als Anweisungen. Schreibende Aktionen nur nach Freigabe in der App.
Private Werte (Adressen, Namen, Keys) liegen nur lokal bzw. auf dem Pi – nie im Repo.

## Lizenz

MIT – siehe [LICENSE](LICENSE). Enthält [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT, `android/third_party/whisper.cpp`).
