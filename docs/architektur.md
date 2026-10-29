# Architektur

```
 Android-App (Kotlin/Compose) ─┐                        ┌─▶ PicoClaw „Kapitän“  (je Bot ein Prozess, ~8 MB RAM)
 Web-Oberfläche (Browser) ─────┼─ HTTPS im Tailnet ─▶ Adapter ─┼─▶ PicoClaw weiterer Bot …
                               │   (tailscale serve)   │  :8795 └─▶ …
                               │                       │
                               │                       ├─▶ /mcp/<bot>      eigene Werkzeuge (Routinen, Team, Python, …)
                               │                       ├─▶ /homemcp/<bot>  home-mcp mit den Rechten dieses Bots
                               │                       ├─▶ /anthropic/*    Claude-Proxy (Verbrauch wird gezählt)
                               │                       ├─▶ Phonon / Piper (Stimmen), whisper.cpp (Diktat im Web)
                               │                       ├─▶ Pi-Browser (Seiten lesen, Seitenbilder), bubblewrap (Python)
                               │                       └─▶ ntfy (Benachrichtigungen, optional)
 home-mcp (eigener Server) ◀───┘  Kalender, Mail, Drive/Docs/Sheets, Slack, GitLab, Datenbank, Freigaben
```

- **Die Bots** sind PicoClaw-Prozesse (Go), einer je Bot, als systemd-Dienst `picoclaw@<bot>`. Sie sprechen das Modell an
  (Ollama Cloud oder Claude über den Proxy). Werkzeuge holen sie sich per MCP vom Adapter.
- **Der Adapter** hält je Unterhaltung eine WebSocket-Verbindung zum Bot und speichert jede Nachricht. Die App kann deshalb zu sein.
  Ereignisse gehen per Server-Sent Events an App und Web. Er schreibt AGENT.md, SOUL.md, MEMORY.md und die Skills der Bots.
  Außerdem steuert er Rechte, Regeln, Routinen, Auslöser und Anrufe. Was ein Bot selbst an seinem Wissen oder an Ablauf-Skills
  ändert, übernimmt der Adapter vor jedem Abgleich in seine Ablage (Vergleich mit `.abgleich.json`), statt es zu überschreiben.
- **home-mcp** ([eigenes Repo](https://github.com/Arno-Hoefer/home-mcp)) ist ein eigener MCP-Server mit OAuth. Er hat einen Schlüssel für alle Bots. Welcher Bot was darf, regelt
  der Adapter (`/homemcp/<bot>` filtert Werkzeuge, Konten, Kanäle und Datenbanken).

## Module des Adapters (`pi/adapter/nookbot/`)

| Modul | Inhalt |
|---|---|
| `basis` | Version, Pfade, JSON lesen/schreiben, Konfiguration, Nutzername (`%NUTZER%` → `n()`) |
| `ereignisse` | SSE-Ereignisse für App und Web (`EV.emit`) |
| `dienste` | systemd-Aufrufe, ntfy |
| `claude` | Claude-Proxy (OpenAI-Format → Anthropic), Verbrauch, Preise, Guthaben mit Aufladung |
| `gesundheit` | Health Connect: Tageswerte speichern, verdichten, abfragen |
| `stimmen` | Phonon (`ph:`), Piper (`pi:`), Butler-Klang, `sprechbar()`, Aussprache, Diktat (whisper.cpp) |
| `wissen` | Wissen je Bot und global, Anhänge, Dokumente in Text, `dokument_ansehen` (Claude oder Ollama-Bildmodelle) |
| `home` | Rechte je Bot, Katalog von home-mcp, `home_aufruf` mit Grenzen, Regeln und „frei“ |
| `bots` | Bots anlegen und ändern, AGENT.md und SOUL.md, PicoClaw-Konfiguration |
| `sitzungen` | Verlauf (`SESS`), Verbindungen zu den Bots (`LINKS`), Ende einer Antwort erkennen, Wachhund |
| `seiten` | öffentliche Seiten lesen (`oeffentlich()` sperrt Heim- und Tailnetz), Pi-Browser |
| `gedaechtnis` | merken/vergessen, Abläufe → Skills |
| `rechner` | Arbeitsordner, `python_ausfuehren` (bubblewrap), `seite_ansehen`, Regeln je Aktion |
| `automatik` | Routinen, Erinnerungen, Auslöser, Aktivität, Anrufe |
| `team` | Runden (mit Gespräch), Aufträge verteilen |
| `vorschlaege` | Vorschläge der Bots, Muster ohne Modell, tägliche Durchsicht |
| `aufraeumen` | Gedächtnis aufräumen: Kandidaten ohne Modell, „belanglos/widersprüchlich“ sparsam per Modell, Vorschlag statt Löschen |
| `mcp` | MCP-Werkzeuge der Bots (`MCP_TOOLS`, `mcp_rpc`) |
| `schnittstelle` | HTTP-Handler (App, Web, MCP) |
| `start` | `main()`: lauschen, Bots starten, Hintergrund-Threads |

`nookbot_server.py` ist nur der Startpunkt. Beim Import stellt er alle Namen aus dem Paket bereit (Tabelle `HERKUNFT`
in `__init__.py`). Darauf bauen Tests und Werkzeuge.

## Hintergrund-Threads

| Thread | Takt | Aufgabe |
|---|---|---|
| `watchdog` | 2 s | Antworten beenden (Ruhe nach Text), Verbindungsabbrüche, Gedächtnis prüfen, Aufräumen (Anhänge, Aktivität, Piper) |
| `scheduler` | 15 s | Routinen zur Minute starten |
| `waechter` | 30 s | Auslöser im eingestellten Takt prüfen (ohne Modell) |
| `planer` | 30 s | einmal am Tag: Muster suchen, nur bei Neuem den Kapitän nach Vorschlägen fragen |
| `aufraeumer` | 60 s | einmal in der Woche: je Bot Gedächtnis und Wissen durchsehen, Aufräum-Vorschläge anlegen |

## Daten auf dem Pi (`/srv/picoclaw/nookbot/`)

| Datei / Ordner | Inhalt |
|---|---|
| `config.json` | Nutzername, MCP-Token, home-Schlüssel, ntfy, Freigabe-Schlüssel (privat, 600) |
| `bots.json` | Bots mit Rolle, Rechten, Regeln, Stimme |
| `sessions/` | Verlauf je Unterhaltung + `index.json` |
| `routinen.json`, `erinnerungen.json`, `ausloeser.json` (+ `ausloeser/` Seitenstände) | Automatik |
| `aktivitaet.jsonl` | Aktivität, 30 Tage |
| `anrufe.json`, `notify.json` | Warteschlangen für Anrufe und Meldungen |
| `vorschlaege.json` | Vorschläge mit Entscheidung, gesehene Muster |
| `aufraeumen.json` | letzte Durchsicht, behaltene Einträge (nicht wieder vorschlagen), Fingerabdruck der letzten Modell-Bewertung je Bot |
| `gedaechtnis.json`, `ablaeufe.json`, `datenbanken.json`, `aussprache.json` | Gedächtnis, Abläufe, Datenbank-Bibliothek, Aussprache |
| `wissen/` | globales Wissen (je Bot liegt es im Arbeitsbereich des Bots) |
| `arbeit/` | gemeinsamer Arbeitsordner |
| `claude.json`, `claude_verbrauch.json` | Schlüssel und Verbrauch (privat) |
| `gesundheit/` | Tageswerte aus Health Connect |
| `diagnose.json`, `abstuerze.json` | Protokolle der App (ohne Inhalte) |

Die Bots selbst liegen unter `/srv/picoclaw/bots/<bot>/.picoclaw/` (Konfiguration, Arbeitsbereich, Anhänge).

## Die App (`android/`)

- Kotlin + Jetpack Compose, eine Activity mit eigenem Navigationsstapel (`Route`), `NookViewModel` als Zustand.
- Netzwerk: OkHttp, Server-Sent Events (`/api/events`). Benachrichtigungen ohne Dauerverbindung (Abfrage alle ~5 min)
  oder mit „Für Anrufe erreichbar“ (Vordergrund-Dienst, Long-Poll `/api/anrufe/warten`).
- Sprache: whisper.cpp per NDK (`WhisperLib`, arm64), Pegel-Erkennung für Satzende, Vorlesen satzweise vom Pi.
- Anrufe: `Anruf.kt` (Klingeln mit Vollbild-Benachrichtigung, `AnrufDienst` als Vordergrund-Dienst, androidx core-telecom).
- Schlüssel liegen verschlüsselt im Android-Keystore.
