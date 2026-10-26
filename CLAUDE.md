# CLAUDE.md – NookBot

Kontext für Claude-Sitzungen in diesem Repo. Ausführliche Doku: [docs/](docs/README.md).

## Was ist das?

NookBot ist ein kleines Team aus KI-Bots auf einem Raspberry Pi 5 zu Hause, mit eigener Android-App und Web-Oberfläche.
Je Bot läuft ein [PicoClaw](https://github.com/sipeed/picoclaw)-Prozess. Dazwischen sitzt der **Adapter** (Python, nur Standardbibliothek).
Er speichert den Verlauf und steuert Rechte, Regeln, Routinen, Auslöser, Anrufe und Stimmen.
Kalender, Mail, Drive, Slack, GitLab und Datenbank kommen über einen eigenen MCP-Server (home-mcp). Der liegt in einem eigenen Repo: [home-mcp](https://github.com/Arno-Hoefer/home-mcp).
Erreichbar ist alles nur im eigenen Tailscale-Netz.

## Aufbau

```
pi/adapter/nookbot_server.py   Startpunkt (systemd) – lädt das Paket, re-exportiert alle Namen
pi/adapter/nookbot/            der Adapter in Modulen (Ladereihenfolge in __init__.py):
  basis, ereignisse, dienste       Pfade/JSON/Konfiguration, SSE-Ereignisse, systemd/ntfy
  claude, gesundheit, stimmen      Claude-Proxy + Verbrauch, Health Connect, Phonon/Piper/Vorlesetext/Diktat
  wissen, home, bots               Wissen/Anhänge/Dokumente ansehen, Rechte + home-mcp-Proxy, Bots + AGENT.md/SOUL.md
  sitzungen, seiten, gedaechtnis   Verlauf + Bot-Verbindungen, Seiten lesen, Gedächtnis + Abläufe
  rechner, automatik, team         Arbeitsordner/Python/Seitenbild/Regeln, Routinen/Erinnerungen/Auslöser/Aktivität/Anrufe, Runden/Aufträge
  vorschlaege                      Vorschläge der Bots, Muster ohne Modell, tägliche Durchsicht
  mcp, schnittstelle, start        MCP-Werkzeuge der Bots, HTTP-Handler, main()
pi/adapter/web/index.html      Web-Oberfläche (eine Datei, ohne Build)
pi/adapter/tests/              unittest (ohne Pi): python3 -m unittest discover -s tests
pi/einrichtung/                Einrichtungsskripte 1–11 für einen frischen Pi
pi/kapitaen/                   AGENT.md/SOUL.md-Vorlagen des Kapitäns (USER.md ist privat, gitignored)
android/                       App (Kotlin, Jetpack Compose, whisper.cpp per NDK)
docs/                          Doku + Screenshots (nur Demo-Daten)
```

## Regeln für Änderungen

- **Das Repo ist öffentlich.** Nie private Werte committen: Tailnet-Namen, IPs aus dem Heimnetz, Mail-Adressen, echte Namen, Schlüssel und Token.
  Adressen der App stehen in `android/local.properties` (gitignored, Vorlage `local.properties.example`). Den Nutzernamen der Bots
  setzt `nutzer-setzen.py` in die Konfiguration auf dem Pi. Im Code steht der Platzhalter `%NUTZER%`, ersetzt wird er mit `n()`.
- **Adapter nur mit Standardbibliothek.** Keine pip-Abhängigkeiten. Der Pi läuft mit Python 3.11.
- **Vor jedem Push** nach Schlüsseln und privaten Werten suchen. Screenshots nur mit Demo-Daten.
- **Schreibendes nie ohne Kontrolle:** Neue Werkzeuge, die etwas verändern, laufen über die Freigaben von home-mcp
  (oder sind lokal und harmlos). Neue home-mcp-Schreibwerkzeuge in `rechner.REGEL_WERKZEUGE` aufnehmen.
- **Rechte je Bot:** Jedes neue home-mcp-Werkzeug braucht einen Eintrag in `home.ART` oder `home_erlaubt`. Werkzeuge, die
  nur bestimmte Bots sehen sollen, in `mcp.mcp_rpc` (tools/list) filtern.
- **Gesundheitsdaten** gehören nie zu „Alles“, kommen nie ins Gedächtnis und gehen nie an andere Bots.
- **Tests** laufen lassen: `cd pi/adapter && python3 -m unittest discover -s tests`. Für neue Funktionen einen Test dazuschreiben.
- **Arbeitsbereich der Bots:** Der Adapter baut `wissen/` und `skills/ablauf-*` bei jedem Start neu auf. Änderungen der Bots daran
  übernimmt er vorher in seine Ablage – neue Dinge, die Bots selbst schreiben dürfen, genauso absichern, sonst gehen sie beim Neustart verloren.
- Texte, Bezeichner und Kommentare auf Deutsch, knapp. Kommentare erklären das *Warum*.
- App-Version in `android/app/build.gradle.kts` (versionCode + versionName) und `basis.VERSION` im Adapter gemeinsam hochzählen.

## Auf den Pi spielen

Der Adapter liegt auf dem Pi unter `/opt/nookbot/`, seine Daten unter `/srv/picoclaw/nookbot/`. Er läuft als Dienst
`nookbot-adapter` unter dem Benutzer `picoclaw`.

```bash
# vom Rechner aus (Zugang zum Pi über Tailscale/SSH), dann auf dem Pi:
sudo install -m 644 nookbot_server.py wsclient.py /opt/nookbot/
sudo install -d -m 755 /opt/nookbot/nookbot && sudo install -m 644 nookbot/*.py /opt/nookbot/nookbot/
sudo install -m 644 web/index.html /opt/nookbot/web/
sudo systemctl restart nookbot-adapter && journalctl -u nookbot-adapter -n 20
```

Vor größeren Änderungen `/opt/nookbot` sichern (`tar czf /root/nookbot-vor-….tgz -C /opt nookbot`).

## App bauen

```bash
cd android && ./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew assemblePruefung                   # wie Release, aber mit Klartext-HTTP – für den Emulator gegen einen lokalen Test-Adapter
```

Release mit R8: Für JNI-Rückrufe (WhisperLib) gibt es Keep-Regeln in `proguard-rules.pro`. Nach Änderungen dort die Release-APK
wirklich ausprobieren. Die Version im APK prüfen: `aapt2 dump badging …apk | head -1`.

## Lokaler Test-Adapter

Die Tests in `pi/adapter/tests/hilfe.py` zeigen das Muster: `NOOKBOT_HOME`, `NOOKBOT_MAIN_HOME` und `NOOKBOT_PORT` auf ein
temporäres Verzeichnis setzen, dann `unit`, `katalog`, `modelle` usw. ersetzen. So laufen App (Build `pruefung`, Emulator →
`http://10.0.2.2:<port>`) und Web gegen Demo-Daten, ohne Pi.
