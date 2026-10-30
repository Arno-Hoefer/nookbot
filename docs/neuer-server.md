# Neuer Server mit home-mcp und NookBot – Teil 2: NookBot

Für jemanden, der **auf einem eigenen, leeren Server** NookBot betreiben will – mit eigenen Konten, ohne etwas von einer
bestehenden Installation zu übernehmen.

**Vorher Teil 1: home-mcp** (`NEUER-SERVER.md` im Repo [home-mcp](https://github.com/Arno-Hoefer/home-mcp)). Danach habt
ihr: Debian-12-Server im eigenen Tailnet (MagicDNS + HTTPS an), home-mcp läuft, Bot `nookbot` mit den Schlüsseln
**„Werkzeuge (Bot)“** und **„Freigaben (App)“**. NookBot geht auch ohne home-mcp – dann Schritt 4 auslassen, ohne Mail,
Kalender, Drive und Freigaben.

Platzhalter: `server` = SSH-Alias, `<name>.<tailnet>.ts.net` = Tailscale-Name des Servers, `<repo>` = Klon dieses Repos.
Ausführliche Hintergründe, Prüfbefehle und Wiederherstellung: [neu-aufsetzen.md](neu-aufsetzen.md).

## Ziel

| | |
|---|---|
| Dienste | `picoclaw-gateway` (Kapitän, `127.0.0.1:18790`), `picoclaw@<bot>` (weitere Bots, ab 18791), `nookbot-adapter` (`127.0.0.1:8795`), optional `ntfy` (`127.0.0.1:8797`) |
| Erreichbar | **nur im Tailnet**: `https://<name>.<tailnet>.ts.net:8444` (App + Web), optional `:8445` (ntfy). Kein Funnel. |
| Daten | `/srv/picoclaw` (gehört `picoclaw`) |

## Was ihr braucht

| Was | Woher | Pflicht |
|---|---|---|
| Ollama-API-Schlüssel | ollama.com → Settings → Keys (Cloud-Modelle, Standard `glm-5.3`) | ja |
| Brave-Search-Schlüssel | brave.com/search/api | für Websuche (leer lassen = ohne) |
| home-mcp-Schlüssel „Werkzeuge (Bot)“ + „Freigaben (App)“ | Teil 1, Schritt 8 | mit home-mcp |
| Claude-API-Schlüssel | console.anthropic.com | optional (Claude als Modell je Bot) |
| Android-Handy im selben Tailnet (Tailscale-App) | – | für die App |

Alle Schlüssel fragen die Skripte verdeckt ab. Skripte mit Abfrage **im eigenen Terminal** ausführen, nie über einen Chat.

## 1. Code auf den Server

```bash
cd <repo>/pi
cp kapitaen/USER.example.md kapitaen/USER.md      # ausfüllen: wie der Kapitän euch nennt, Ort, Ton – bleibt privat
scp -r einrichtung kapitaen server:/tmp/
ssh server 'rm -rf /tmp/nookbot-adapter && mkdir -p /tmp/nookbot-adapter'
scp -r adapter/nookbot_server.py adapter/wsclient.py adapter/nookbot adapter/web server:/tmp/nookbot-adapter/
ssh -t server                                       # alles Weitere auf dem Server
```

## 2. Pflichtschritte

| # | Befehl | Was passiert | Prüfen |
|---|---|---|---|
| 1 | `sudo bash /tmp/einrichtung/1-picoclaw-installieren.sh` | Benutzer `picoclaw`, PicoClaw **v0.3.1** (arm64 oder x86_64, Prüfsumme), Grundkonfiguration | `pico --no-color version` → 0.3.1 |
| 2 | `sudo bash /tmp/einrichtung/2-modell-und-websuche.sh` | fragt Ollama- und Brave-Schlüssel ab | `sudo grep -c api_keys /srv/picoclaw/.picoclaw/config.json` |
| 3 | `sudo bash /tmp/einrichtung/3-picoclaw-dienst.sh` | Kapitän (AGENT/SOUL/USER.md), Dienst `picoclaw-gateway` | `curl -fsS http://127.0.0.1:18790/health` |
| 4 | `sudo bash /tmp/einrichtung/4-home-mcp-anbinden.sh` | fragt Schlüssel **„Werkzeuge (Bot)“** ab, prüft ihn gegen home-mcp | `pico --no-color mcp test home \| tail -1` |
| 5 | `sudo bash /tmp/einrichtung/5-chat-kanal.sh` | erzeugt den **Chat-Token** `nb_…` – wird **einmal** angezeigt, sofort in den Passwort-Manager | `journalctl -u picoclaw-gateway -n 20 -o cat` |
| 6 | `sudo bash /tmp/einrichtung/6-adapter.sh` | Adapter, Polkit-Regel, `tailscale serve` auf `:8444` | `curl -fsS http://127.0.0.1:8795/ping` |
| 13 | `sudo python3 /tmp/einrichtung/nutzer-setzen.py <Name> && sudo systemctl restart nookbot-adapter` | wie die Bots euch nennen | – |

Danach im Browser **im Tailnet**: `https://<name>.<tailnet>.ts.net:8444/` → Chat-Token eingeben → der Kapitän antwortet.

## 3. Optionale Schritte

| # | Befehl | Wofür |
|---|---|---|
| 7 | `sudo bash /tmp/einrichtung/7-claude.sh` | Claude als Modell je Bot, Monatsbudget |
| 8 | `sudo bash /tmp/einrichtung/8-stimmen.sh` | Vorlesen mit Piper (frei, lokal) |
| 9 | `sudo bash /tmp/einrichtung/9-whisper.sh` | Diktat im Web (~5 min Bauzeit, braucht ~500 MB RAM) |
| 10 | `sudo bash /tmp/einrichtung/10-rechner.sh` | Python-Sandkasten für Auswertungen |
| 11 | `sudo bash /tmp/einrichtung/11-phonon.sh` | bessere Stimmen – **Lizenz CC BY-NC: nur privat, nicht im Firmeneinsatz** |
| 12 | ntfy nach [neu-aufsetzen.md → Schritt 12](neu-aufsetzen.md#12-ntfy-und-freigaben-im-web-optional) | sofortige Benachrichtigungen; dort auch den Schlüssel **„Freigaben (App)“** eintragen, damit Freigaben im Web gehen |

Ohne Schritt 12 trägt man den Freigaben-Schlüssel nur in der App ein; die App holt Meldungen dann alle paar Minuten ab.

## 4. App

Die APK (`de.hoefer.nookbot`) installieren, Tailscale-App auf dem Handy im selben Tailnet anmelden. In der App
**Einstellungen**:

- Server: `https://<name>.<tailnet>.ts.net:8444`, Chat-Token aus Schritt 5
- home-mcp: `https://<name>.<tailnet>.ts.net`, Schlüssel **„Freigaben (App)“**
- **Verbindung testen** – beide grün

Wer die App selbst baut: `android/local.properties.example` → `local.properties`, dort dieselben Adressen als Vorgabe.

## 5. Abschluss-Check

```bash
systemctl is-active picoclaw-gateway nookbot-adapter home-mcp     # → active active active
tailscale serve status                                            # 443 (Funnel, home-mcp), 8444 (NookBot), ggf. 8445
sudo ls /etc/polkit-1/rules.d/                                    # → 50-nookbot.rules
sudo find /srv/picoclaw ! -user picoclaw | head                   # muss leer sein
```

In der App: Kapitän fragen „Welche Termine habe ich morgen?“ → er nutzt home-mcp; „Leg mir einen Entwurf an …“ →
eine Freigabe-Karte erscheint.

## Sicherung

Bots, Verlauf, Routinen, Gedächtnis und alle Schlüssel liegen in `/srv/picoclaw`, home-mcp in `/srv/home-mcp`. Beides
regelmäßig verschlüsselt sichern, z. B.:

```bash
sudo tar -C /srv --exclude=picoclaw/nookbot/stimmen-cache -cpf - picoclaw home-mcp | gpg -c > server-$(date +%F).tar.gpg
```

Zurückspielen: [neu-aufsetzen.md → Wiederherstellen](neu-aufsetzen.md#wiederherstellen-aus-der-sicherung) (Skript 1
zuerst, dann auspacken, `chown -R picoclaw:picoclaw /srv/picoclaw`, Schritte 2–5 auslassen).

## Was ohne Weiteres fehlt

- **`seite_ansehen`** (Webseite als Bild) und das Lesen von JavaScript-Seiten brauchen einen lokalen Browser-Dienst auf
  `127.0.0.1:8789`, der nicht in diesem Repo ist. Ohne ihn liest `seite_lesen` Seiten direkt per HTTP.

## Fallstricke

- **PicoClaw bleibt auf v0.3.1** – der Adapter ist auf genau diese Version abgestimmt.
- **Adapter nicht neu starten, während Bots antworten** – vorher `/api/state` prüfen, siehe [neu-aufsetzen.md](neu-aufsetzen.md#6-adapter).
- **Neuer home-mcp-Schlüssel:** `home_key` in `/srv/picoclaw/nookbot/config.json` löschen, sonst bleibt der alte aktiv.
- **Nie `tailscale funnel` für NookBot** – nur `tailscale serve --https=8444`. Und nie `tailscale serve reset`, das löscht auch den Funnel von home-mcp.
- **Rechte:** Alles unter `/srv/picoclaw` gehört `picoclaw`; nach Handarbeit als root `chown -R picoclaw:picoclaw /srv/picoclaw`.
