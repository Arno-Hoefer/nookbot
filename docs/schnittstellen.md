# Schnittstellen

## HTTP-API (App und Web)

Basis: `http://127.0.0.1:8795`, im Tailnet über `tailscale serve`. Anmeldung: `Authorization: Bearer <Chat-Token>`.
Antworten sind JSON, Fehler kommen als `{"error": "…"}`.

**Zustand und Ereignisse**

| Methode | Pfad | |
|---|---|---|
| GET | `/api/state` | Bots, Unterhaltungen, Routinen, Auslöser, Erinnerungen, Abläufe, Gedächtnis |
| GET | `/api/events?since=<seq>` | Server-Sent Events (msg, busy, session, routinen, ausloeser, aktivitaet, anruf, arbeit …) |
| GET | `/api/notify?since=<id>` | Meldungen für die Benachrichtigungs-Abfrage der App |
| GET | `/api/suche?q=…` | Suche über alle Unterhaltungen |

**Unterhaltungen und Bots**

| Methode | Pfad | |
|---|---|---|
| POST | `/api/sessions` · GET/DELETE `/api/sessions/<id>` | Unterhaltung anlegen, lesen, löschen |
| POST | `/api/sessions/<id>/senden` | Nachricht an den Bot |
| POST | `/api/sessions/<id>/anhang` | Datei anhängen (Base64), der Pi wandelt in Text |
| POST | `/api/sessions/<id>/ablauf` | Weg aus dem Gespräch als Ablauf-Entwurf |
| POST | `/api/runden` | Runde starten (`frage`, `bots`, `kapitaen`, `gespraech` 0–2) |
| POST/PUT/DELETE | `/api/bots`, `/api/bots/<id>` | Bot anlegen, ändern (auch `rechte`, `regeln`, `stimme`), löschen |
| POST | `/api/bots/<id>/neu` | neue Unterhaltung, die alte geht ins Archiv |
| GET | `/api/modelle`, `/api/rechte`, `/api/zugriffe`, `/api/regeln` | Auswahllisten für die Bot-Einstellungen |

**Automatik**

| Methode | Pfad | |
|---|---|---|
| GET/PUT | `/api/routinen` · POST `/api/routinen/<id>/jetzt` | Routinen (auch `anruf: true`) |
| GET/PUT | `/api/erinnerungen` · POST `/api/erinnerungen/<id>/gezeigt` | Erinnerungen |
| GET/PUT | `/api/ausloeser` · POST `/api/ausloeser/<id>/pruefen` | Auslöser (mail, slack, webseite, paket) |
| GET | `/api/aktivitaet?tage=&bot=&art=&hintergrund=1` | Aktivität |
| GET | `/api/anrufe/warten?seit=<id>&sekunden=50` | Long-Poll für „Für Anrufe erreichbar“ |
| POST | `/api/anrufe/test` | Testanruf |

**Freigaben** (weitergereicht an home-mcp)

| Methode | Pfad | |
|---|---|---|
| GET | `/api/freigaben` | offene und letzte Freigaben |
| POST | `/api/freigaben/<id>` | `{"entscheidung": "ja" \| "nein"}` |
| POST | `/api/freigaben/<id>/rueckgaengig` | ausgeführte Termine/Entwürfe zurücknehmen |

**Dateien, Wissen, Daten**

| Methode | Pfad | |
|---|---|---|
| GET/POST | `/api/arbeit` · GET/DELETE `/api/arbeit/<name>` | Arbeitsordner (Bilder inline, sonst als Download) |
| GET/POST/DELETE | `/api/wissen…` | Wissen global und je Bot |
| GET/PUT | `/api/datenbanken` · GET `/api/schema` | Datenbank-Bibliothek (welche Datenbanken home-mcp freigibt: `GET /api/rechte` → `db`; Zugriff je Bot über `PUT /api/bots/<id>` mit `rechte.db`) |
| GET/POST/PUT/DELETE | `/api/gedaechtnis…`, `/api/ablaeufe…` | Gedächtnis, Abläufe |
| GET/PUT | `/api/vorschlaege` | Vorschläge und Einstellungen (`aktiv`, `zeit`) |
| POST | `/api/vorschlaege/<id>` (`entscheidung`: ja/nein, bei Aufräum-Karten optional `nur`: [Schlüssel]) · `/api/vorschlaege/durchsicht` | Vorschlag entscheiden, Durchsicht sofort |
| GET/PUT | `/api/aufraeumen` | Gedächtnis aufräumen: Einstellungen (`aktiv`, `tag` 1–7, `zeit`), letzte Durchsicht, offene Karten |
| POST | `/api/aufraeumen/jetzt` (`bot`: leer = alle, `global`, Bot-id) | Aufräumen sofort (Ergebnis als SSE `aufraeumen`) |
| POST/GET | `/api/gesundheit`, `/api/gesundheit/info` | Health Connect vom Handy |

**Sprache und Verbrauch**

| Methode | Pfad | |
|---|---|---|
| GET | `/api/stimmen` | Stimmen (Phonon, Piper), Aussprache |
| POST | `/api/sprechen` | `{"text","stimme","tempo"}` → WAV |
| POST | `/api/diktat` | Aufnahme (Base64) → Text (whisper.cpp auf dem Pi) |
| PUT | `/api/aussprache` · POST `/api/aussprache/anwenden` | Aussprache-Wörterbuch |
| GET | `/api/guthaben`, `/api/claude` | Verbrauch Ollama, Claude |
| POST | `/api/claude/stand` | Startbetrag und Aufladung für Claude |
| POST | `/api/diagnose`, `/api/absturz` | Protokolle der App (ohne Inhalte) |

## MCP für die Bots

`/mcp/<bot>` (eigene Werkzeuge) und `/homemcp/<bot>` (home-mcp mit den Grenzen dieses Bots). Anmeldung mit dem MCP-Token.

| Werkzeug | Wer | |
|---|---|---|
| `routine_anlegen`, `routinen_liste`, `routine_loeschen` | alle | Zeitpläne (auch `anruf`) |
| `erinnerung_anlegen`, `erinnerungen_liste`, `erinnerung_loeschen` | alle | Erinnerungen |
| `ausloeser_anlegen`, `ausloeser_liste`, `ausloeser_loeschen` | alle | Auslöser |
| `bots_liste`, `bot_fragen` | alle (mit Team) | anderen Bot beauftragen (nur ohne Mehr-Rechte) |
| `auftraege_verteilen`, `bot_anlegen`, `bot_aendern` | Kapitän | Team steuern |
| `merken`, `vergessen`, `ablauf_vorschlagen` | alle | Gedächtnis, Abläufe |
| `vorschlag_machen` | alle (nicht in Team-Aufträgen) | Vorschlag als Karte, umgesetzt erst nach Ja |
| `seite_lesen`, `seite_ansehen` | alle | öffentliche Seiten als Text oder Bild |
| `dokument_ansehen` | alle | PDF-Seiten und Bilder mit einem Bildmodell |
| `python_ausfuehren`, `arbeitsordner`, `datei_ablegen` | alle | Pi-Rechner |
| `pakete`, `paket_hinzufuegen` | mit Recht „Pakete“ | Pakete aus Home |
| `gesundheit` | mit Recht „Gesundheit“ | Tageswerte aus Health Connect |

home-mcp liefert unter anderem `termine`, `termin_anlegen`, `termine_anlegen` (eine Sammelfreigabe), `termin_gaeste`, `mails_suchen`,
`mail_lesen`, `entwurf_anlegen`, `drive_suche`, `drive_lesen`, `sheets_*`, `doc_*`, `slack_*`, `gitlab_*`, `db_abfrage`, `db_schema`,
`statistik`, `freigabe_status`. Welche ein Bot sieht, hängt von seinen Zugriffen und Regeln ab.
