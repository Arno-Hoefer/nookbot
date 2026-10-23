# Betrieb

## Einrichten (frischer Pi)

Voraussetzungen: Raspberry Pi 5 (getestet mit 4 GB, Raspberry Pi OS / Debian 12), Tailscale, ein Ollama-Konto.
Die Skripte unter `pi/einrichtung/` der Reihe nach ausführen. Schlüssel werden verdeckt abgefragt.

| Skript | Was |
|---|---|
| `1-picoclaw-installieren.sh` | PicoClaw als eigener Benutzer, Prüfsumme wird geprüft |
| `2-modell-und-websuche.sh` | Ollama-Modell und Websuche, Shell und Skill-Installation aus |
| `3-picoclaw-dienst.sh` | systemd-Dienst, abgeschottet |
| `4-home-mcp-anbinden.sh` | optional: home-mcp für Kalender, Mail, Daten |
| `5-chat-kanal.sh` | WebSocket-Kanal mit Token (zugleich der Schlüssel der App) |
| `6-adapter.sh` | Adapter samt Paket, Vorlage für weitere Bots, Polkit-Regel, Tailnet-Freigabe |
| `7-claude.sh` | optional: Claude als zweiter Anbieter |
| `8-stimmen.sh` | optional: Piper-Stimmen |
| `9-whisper.sh` | optional: Spracherkennung auf dem Pi (Diktat im Web) |
| `10-rechner.sh` | optional: Python-Sandkasten (bubblewrap, pandas, matplotlib, openpyxl) |
| `11-elevenlabs.sh` | optional: Cloud-Stimmen von ElevenLabs |
| `nutzer-setzen.py <Name>` | wie die Bots dich nennen |

## Aktualisieren

Siehe [CLAUDE.md](../CLAUDE.md#auf-den-pi-spielen). Kurz: `nookbot_server.py`, `nookbot/*.py` und `web/index.html` nach `/opt/nookbot`,
dann `systemctl restart nookbot-adapter`. Die Bots laufen dabei weiter. Änderungen an AGENT.md lösen einen kurzen Neustart des
jeweiligen Bots aus.

## Protokolle und Fehlersuche

```bash
journalctl -u nookbot-adapter -n 50          # Adapter
journalctl -u picoclaw@reacher -n 50         # ein Bot
sudo -u picoclaw cat /srv/picoclaw/nookbot/diagnose.json   # Anruf-Protokolle der App (ohne Inhalte)
sudo -u picoclaw cat /srv/picoclaw/nookbot/abstuerze.json  # Absturzberichte der App
```

In der App zeigt **Aktivität**, welcher Bot wann welches Werkzeug benutzt hat. **Einstellungen → Verbindung testen** prüft Pi und Freigaben.

## Kosten

| Dienst | Modell | Anzeige |
|---|---|---|
| Ollama Cloud | Pauschale (Pro) | Statuszeile: genutzt in % |
| Claude | nach Verbrauch, eigener Schlüssel | Statuszeile: Rest in $ (mit Startbetrag/Aufladung) bzw. Monatsverbrauch |
| ElevenLabs | Freitarif 10.000 Zeichen/Monat (~10 min), schnelles Modell `eleven_flash_v2_5` | Statuszeile: Zeichen in % |

Ist das ElevenLabs-Kontingent leer, spricht Piper auf dem Pi. Gleiche Sätze kommen aus dem Zwischenspeicher (`stimmen-cache/`).

## Speicher

Der Adapter darf bis 1,5 GB RAM nutzen (Drop-in `stimmen.conf`). Das reicht für eine geladene Piper-Stimme, whisper.cpp und Python
gleichzeitig. Für bubblewrap gibt es den Drop-in `rechner.conf` (Netlink fürs Loopback im Sandkasten).

## Sicherung

Jede Nacht um 3:45 Uhr sichert `~/bin/pi-backup.sh` den Pi verschlüsselt nach Google Drive (rclone crypt): täglich 7 Tage,
sonntags 4 Wochen, am Monatsersten 6 Monate. Seit 23.10.2026 gehören auch NookBot und home-mcp dazu:

| Was | Wo |
|---|---|
| NookBot: Bots, Verlauf, Routinen, Auslöser, Arbeitsordner, Schlüssel (ohne `stimmen-cache/`) | `/srv/picoclaw` → `nookbot.tar` |
| home-mcp: Nutzer, OAuth-Token, Freigaben | `/srv/home-mcp` → `home-mcp.tar` |
| Dienste und Drop-ins | `/etc/systemd/system/{nookbot-adapter*,picoclaw@,picoclaw-gateway,home-mcp}.service` → `system/` |

- Darin liegen Schlüssel und Token. Deshalb nur über das verschlüsselte Ziel, Google sieht nur verschlüsselte Dateien und Namen.
- Der Code (`/opt/nookbot`, `/opt/home-mcp`) kommt aus Git und wird nicht gesichert.
- **Wiederherstellungstest:** `~/bin/pi-backup-pruefen.sh`. Er holt die neueste Sicherung, packt sie im Testordner aus und prüft
  unter anderem `bots.json`, `config.json`, die Unterhaltungen und `nutzer.json` von home-mcp.
- **Wiederherstellen:** `nookbot.tar` bzw. `home-mcp.tar` nach `/srv` entpacken, Dienst-Dateien zurück, `systemctl daemon-reload`,
  Dienste starten.
- Bleibt die Sicherung aus, warnt die Home-App (`~/pi-backup/status.json`).
- Die Skripte liegen im Home-Repo unter `deploy/backup/`.
