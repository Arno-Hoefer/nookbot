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

## Sicherung – Vorschlag (noch nicht eingerichtet)

Auf dem Pi läuft bereits jede Nacht um 3:45 Uhr `~/bin/pi-backup.sh`. Es sichert verschlüsselt nach Google Drive (rclone crypt)
mit täglicher, wöchentlicher und monatlicher Aufbewahrung und Wiederherstellungstest. Bisher sind darin Home, Orga und Echo-Bot.
**NookBot und home-mcp fehlen noch.**

Vorschlag – das bestehende Skript erweitern, kein zweites System:

| Was | Wo | Größe heute |
|---|---|---|
| NookBot-Daten (ohne `stimmen-cache/`) | `/srv/picoclaw/nookbot` | ~10 MB |
| Bots (Konfiguration, Arbeitsbereich, Wissen, Gedächtnis) | `/srv/picoclaw/bots` | ~2 MB |
| home-mcp (Nutzer, OAuth-Token, Freigaben) | `/srv/home-mcp` | < 1 MB |
| Dienste und Drop-ins | `/etc/systemd/system/{nookbot-adapter,picoclaw@,home-mcp}*` | klein |

- Darin liegen **Schlüssel und Token**. Deshalb nur über das verschlüsselte rclone-Ziel sichern, nie unverschlüsselt.
- Der Code (`/opt/nookbot`, `/opt/home-mcp`) muss nicht mit, er kommt aus Git.
- Wiederherstellen: Archiv entpacken, Ordner zurückkopieren, Dienste neu starten. Den Wiederherstellungstest
  (`pi-backup-pruefen.sh`) um eine Prüfung von `bots.json` und `config.json` ergänzen.
- Den Status meldet Home schon heute, wenn das Backup ausbleibt. Das gälte dann auch für NookBot.
