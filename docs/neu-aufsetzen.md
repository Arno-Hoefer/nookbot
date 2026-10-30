# Neu aufsetzen (frischer Pi oder Wiederherstellung)

Schritt für Schritt von einem leeren Raspberry Pi bis zum laufenden NookBot – in der richtigen Reihenfolge und mit einem
Prüfbefehl nach jedem Schritt. Gibt es eine Sicherung, gleich zu [Wiederherstellen](#wiederherstellen-aus-der-sicherung) springen.

Platzhalter: `<pi>.<tailnet>.ts.net` ist der Tailscale-Name des Pi, `<repo>` der Klon dieses Repos auf deinem Rechner.

## Voraussetzungen

| Was | Wozu |
|---|---|
| Raspberry Pi 5 (getestet: 4 GB), Raspberry Pi OS Lite 64 bit / Debian 12 bookworm, arm64 | Python 3.11 ist dabei, der Adapter braucht nichts weiter |
| Benutzer mit `sudo`, SSH-Zugang, Zeitzone `Europe/Berlin` (`sudo timedatectl set-timezone Europe/Berlin`) | Routinen und Erinnerungen laufen nach der Ortszeit des Pi |
| `curl`, `git`, `python3` (`sudo apt-get install -y curl git`) | die Einrichtungsskripte |
| [Tailscale](https://tailscale.com) auf dem Pi (`tailscale up`), HTTPS-Zertifikate im Tailnet eingeschaltet | `tailscale serve` für App und Web |
| Ollama-Konto mit API-Schlüssel (Cloud) | Modelle der Bots |
| optional: [home-mcp](https://github.com/Arno-Hoefer/home-mcp) auf demselben Pi (`127.0.0.1:8790`) | Kalender, Mail, Drive, Slack, GitLab, Datenbank, Freigaben |

Belegte Ports (alle nur auf `127.0.0.1`):

| Port | Dienst |
|---|---|
| 18790 | PicoClaw des Kapitäns (`picoclaw-gateway`) |
| ab 18791 | weitere Bots (`picoclaw@<id>`), vergibt der Adapter |
| 8795 | Adapter (`nookbot-adapter`) |
| 8797 | ntfy (optional) |
| 8790 | home-mcp (eigenes Repo) |
| 8789 | Pi-Browser (optional, nicht in diesem Repo – ohne ihn liest `seite_lesen` nur direkt per HTTP, `seite_ansehen` geht nicht) |

Nach außen geht nur `tailscale serve` (nur Tailnet, kein Funnel):

| Tailnet | → lokal |
|---|---|
| `https://<pi>.<tailnet>.ts.net:8444` | Adapter `127.0.0.1:8795` (App und Web) – richtet `6-adapter.sh` ein |
| `https://<pi>.<tailnet>.ts.net:8445` | ntfy `127.0.0.1:8797` (optional) – siehe Schritt 12 |

## Secrets – was du woher brauchst

Nichts davon kommt ins Repo. Die Skripte fragen verdeckt ab und schreiben in Dateien mit Rechten 600 (Besitzer `picoclaw`).

| Secret | Woher | Wo es landet |
|---|---|---|
| Ollama-API-Schlüssel | ollama.com → Settings → Keys | `/srv/picoclaw/.picoclaw/config.json` (`model_list[].api_keys`) – Schritt 2 |
| Brave-Search-Schlüssel | brave.com/search/api | dieselbe Datei (`tools.web.brave.api_keys`) – Schritt 2 |
| home-mcp-Schlüssel **„Werkzeuge (Bot)“** (`hmk_…`) | home-mcp-Verwaltung → Bot `nookbot` → Schlüssel erzeugen | Schritt 4; der Adapter übernimmt ihn beim Start nach `/srv/picoclaw/nookbot/config.json` (`home_key`) |
| home-mcp-Schlüssel **„Freigaben (App)“** (`hmk_…`) | dieselbe Seite, zweiter Schlüssel | App (Einstellungen) und für Freigaben im Web `config.json` → `freigabe_key` – Schritt 12 |
| Chat-Token (`nb_…`) | erzeugt `5-chat-kanal.sh`, wird **einmal** angezeigt | `config.json` von PicoClaw (`channel_list.pico.settings.token`); in App und Web eintragen |
| MCP-Token der Bots (`nbm_…`) | erzeugt der Adapter selbst | `/srv/picoclaw/nookbot/config.json` (`mcp_token`) – nichts zu tun |
| Claude-API-Schlüssel (optional, + Admin-Schlüssel für Kosten) | console.anthropic.com | `/srv/picoclaw/nookbot/claude.json` – Schritt 7 |
| Hugging-Face-Token, Read (optional) | huggingface.co → Settings → Access Tokens, vorher Zugang zu `gradium/phonon-40m-de` anfordern | nur während Schritt 11, wird nicht gespeichert |
| ntfy-Zugangstoken (optional) | `ntfy token add …` auf dem Pi | `config.json` → `ntfy.token` – Schritt 12 |

## Schritt für Schritt

Erst den Code auf den Pi bringen (von deinem Rechner aus):

```bash
cd <repo>/pi
scp -r einrichtung kapitaen <pi>:/tmp/
ssh <pi> 'rm -rf /tmp/nookbot-adapter && mkdir -p /tmp/nookbot-adapter'
scp -r adapter/nookbot_server.py adapter/wsclient.py adapter/nookbot adapter/web <pi>:/tmp/nookbot-adapter/
```

Den Kapitän vorher anpassen: `pi/kapitaen/USER.example.md` nach `USER.md` kopieren und ausfüllen (Name, Ort, Ton).
`USER.md` ist privat und steht in `.gitignore`. Alle weiteren Befehle laufen **auf dem Pi**, Skripte mit Schlüssel-Abfrage
**in einem eigenen Terminal** (nicht über ein Werkzeug, das die Eingabe mitschreibt).

### 1. PicoClaw installieren

```bash
sudo bash /tmp/einrichtung/1-picoclaw-installieren.sh
```

Legt den Systembenutzer `picoclaw` (`/srv/picoclaw`, 700) an, lädt PicoClaw **v0.3.1** (fest, Prüfsumme wird geprüft) nach
`/opt/picoclaw`, legt mit `picoclaw onboard` Konfiguration und Arbeitsbereich an und installiert die Aufrufhilfe `pico`.
Andere PicoClaw-Versionen sind nicht getestet – der Adapter gleicht Eigenheiten genau dieser Version aus.

```bash
pico --no-color version | grep -i picoclaw        # → picoclaw 0.3.1
sudo ls -la /srv/picoclaw/.picoclaw/              # config.json (600) und workspace/
```

### 2. Modell und Websuche

```bash
sudo bash /tmp/einrichtung/2-modell-und-websuche.sh     # fragt Ollama- und Brave-Schlüssel ab
```

Modelle `kapitaen` (glm-5.3) und `flash`, Brave-Suche, Shell (`exec`), Skill-Installation und Heartbeat aus, Gateway auf `127.0.0.1:18790`.

```bash
sudo python3 -c 'import json; c=json.load(open("/srv/picoclaw/.picoclaw/config.json")); print([(m["model_name"], m["model"]) for m in c["model_list"]], c["tools"]["exec"]["enabled"])'
# → [('kapitaen', 'glm-5.3'), ('flash', 'glm-5.3-flash')] False
```

### 3. Kapitän und Dienst `picoclaw-gateway`

```bash
sudo bash /tmp/einrichtung/3-picoclaw-dienst.sh     # erwartet /tmp/kapitaen/{AGENT,SOUL,USER}.md
```

Legt riskante Skills nach `/srv/picoclaw/skills-aus/` (nicht gelöscht) und startet den abgeschotteten Dienst (400 MB Grenze).

```bash
systemctl is-active picoclaw-gateway && curl -fsS http://127.0.0.1:18790/health   # → active, {"status":"ok",…}
```

### 4. home-mcp anbinden (optional)

Nur wenn home-mcp schon auf dem Pi läuft (`curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8790/mcp` liefert einen HTTP-Code, nicht `000`). In der Verwaltung von home-mcp einen
Bot `nookbot` anlegen, Rechte anhaken, Schlüssel **„Werkzeuge (Bot)“** erzeugen.

```bash
sudo bash /tmp/einrichtung/4-home-mcp-anbinden.sh   # prüft den Schlüssel gegen home-mcp, zeigt die Werkzeuge
```

Ab Schritt 6 übernimmt der Adapter den Schlüssel in seine `config.json` und leitet alle Bots über `/homemcp/<bot>` (Rechte je Bot).

```bash
pico --no-color mcp test home | tail -1
```

### 5. Chat-Kanal

```bash
sudo bash /tmp/einrichtung/5-chat-kanal.sh
```

Zeigt den Chat-Token **einmal** – sofort in den Passwort-Manager. Er ist der Schlüssel von App und Web.

```bash
journalctl -u picoclaw-gateway -n 20 --no-pager -o cat | grep -i pico
```

### 6. Adapter

```bash
sudo bash /tmp/einrichtung/6-adapter.sh             # erwartet /tmp/nookbot-adapter/
```

Installiert `poppler-utils` (PDF-Text und -Seitenbilder), den Code nach `/opt/nookbot`, `/srv/picoclaw/nookbot` und
`/srv/picoclaw/bots` (700), die Vorlage `picoclaw@.service` für weitere Bots (ohne `[Install]` – der Adapter startet sie),
die Polkit-Regel `/etc/polkit-1/rules.d/50-nookbot.rules` (Benutzer `picoclaw` darf **nur** `picoclaw-gateway` und
`picoclaw@*` starten, stoppen, neu starten), den Dienst `nookbot-adapter` und `tailscale serve` auf `:8444`.

```bash
curl -fsS http://127.0.0.1:8795/ping                       # → {"ok": true, "dienst": "nookbot", "version": "…"}
tailscale serve status | grep -A1 8444                      # → proxy http://127.0.0.1:8795
sudo -u picoclaw systemctl --no-ask-password restart picoclaw-gateway && echo "Polkit ok"
```

Im Browser im Tailnet: `https://<pi>.<tailnet>.ts.net:8444/` → Chat-Token eingeben → der Kapitän antwortet.

Zustand mit Token, ohne ihn anzuzeigen:

```bash
T=$(sudo python3 -c 'import json; print(json.load(open("/srv/picoclaw/.picoclaw/config.json"))["channel_list"]["pico"]["settings"]["token"])')
curl -fsS -H "Authorization: Bearer $T" http://127.0.0.1:8795/api/state | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["version"], len(d["bots"]), "Bots | laufend:", [s["id"] for s in d["sessions"] if s.get("laeuft")], "| ntfy:", d["ntfy"], "| Freigaben im Web:", d["freigaben_web"])'
unset T
```

### 7. Claude (optional)

```bash
sudo bash /tmp/einrichtung/7-claude.sh
sudo ls -la /srv/picoclaw/nookbot/claude.json      # → -rw------- picoclaw picoclaw
```

### 8. Piper-Stimmen (optional)

```bash
sudo bash /tmp/einrichtung/8-stimmen.sh
ls /opt/piper/stimmen/*.onnx | wc -l               # → 8
```

### 9. whisper.cpp fürs Diktat im Web (optional, ~5 min Bauzeit)

```bash
sudo bash /tmp/einrichtung/9-whisper.sh
ls -la /opt/nookbot/whisper/                       # whisper-cli, ggml-small-q5_1.bin (190085487 Bytes)
systemctl cat nookbot-adapter | grep MemoryMax     # → zuletzt MemoryMax=1500M (Drop-in stimmen.conf)
```

Schritt 8 setzt den Drop-in `stimmen.conf` auf 900 MB, Schritt 9 auf 1500 MB. Wer 9 auslässt, behält 900 MB.

### 10. Python-Sandkasten (optional)

```bash
sudo bash /tmp/einrichtung/10-rechner.sh
systemctl cat nookbot-adapter | grep -A1 rechner.conf   # → RestrictAddressFamilies=… AF_NETLINK
command -v bwrap pdftotext pdftoppm
```

### 11. Phonon-Stimmen (optional, nur privat)

```bash
sudo bash /tmp/einrichtung/11-phonon.sh            # fragt das Hugging-Face-Token ab, speichert es nicht
ls /opt/phonon/stimmen | wc -l                     # → 13
/opt/phonon/ptts --version                         # → ptts 0.4.2
```

### 12. ntfy und Freigaben im Web (optional)

Ohne ntfy holt die App Meldungen trotzdem alle paar Minuten ab. ntfy macht sie sofort, z. B. über die ntfy-App.

ntfy als Debian-Paket nach [docs.ntfy.sh/install](https://docs.ntfy.sh/install/) installieren (getestet: 2.28.0, arm64).
`/etc/ntfy/server.yml`:

```yaml
base-url: "https://<pi>.<tailnet>.ts.net:8445"
listen-http: "127.0.0.1:8797"
behind-proxy: true
cache-file: "/var/cache/ntfy/cache.db"
cache-duration: "48h"
auth-file: "/var/lib/ntfy/user.db"
auth-default-access: "deny-all"
enable-signup: false
enable-login: true
```

```bash
sudo systemctl enable --now ntfy && sudo systemctl restart ntfy
sudo tailscale serve --bg --https=8445 http://127.0.0.1:8797
sudo ntfy user add nookbot-pi   && sudo ntfy access nookbot-pi nookbot write-only   # der Adapter sendet
sudo ntfy user add handy        && sudo ntfy access handy nookbot read-only         # die ntfy-App liest
sudo ntfy token add nookbot-pi                                                      # Token für den Adapter
```

ntfy-Token und (falls home-mcp läuft) den home-mcp-Schlüssel **„Freigaben (App)“** in die Konfiguration des Adapters schreiben –
leere Eingabe lässt den Wert unverändert:

```bash
sudo python3 - <<'PY'
import getpass, json, os, pwd
p = "/srv/picoclaw/nookbot/config.json"
c = json.load(open(p))
t = getpass.getpass("ntfy-Token (tk_…, leer = unverändert): ").strip()
if t:
    c["ntfy"] = {"url": "http://127.0.0.1:8797", "topic": "nookbot", "token": t}
k = getpass.getpass("home-mcp-Schlüssel „Freigaben (App)“ (hmk_…, leer = unverändert): ").strip()
if k:
    c["freigabe_key"] = k
with open(p + ".tmp", "w") as f:
    json.dump(c, f, indent=1)
pw = pwd.getpwnam("picoclaw")
os.chown(p + ".tmp", pw.pw_uid, pw.pw_gid)
os.chmod(p + ".tmp", 0o600)
os.replace(p + ".tmp", p)
print("gespeichert:", ", ".join(x for x in ("ntfy", "freigabe_key") if c.get(x)))
PY
sudo systemctl restart nookbot-adapter
```

Prüfen: `/api/state` (siehe Schritt 6) zeigt `ntfy: True` und `Freigaben im Web: True`; `sudo ntfy access` listet beide Nutzer.

### 13. Name des Nutzers

```bash
sudo python3 /tmp/einrichtung/nutzer-setzen.py <Name>    # wie die Bots dich nennen
sudo systemctl restart nookbot-adapter
```

### 14. App

`android/local.properties.example` → `android/local.properties`, darin `nookbot.server=https://<pi>.<tailnet>.ts.net:8444` und
`nookbot.mcp` (Adresse von home-mcp) eintragen, `./gradlew assembleRelease`. In der App Chat-Token und den Freigaben-Schlüssel
eintragen, dann **Einstellungen → Verbindung testen**.

### 15. Sicherung einrichten

Die nächtliche Sicherung gehört zum Home-Repo (`deploy/backup/pi-backup-einrichten.sh`, `pi-backup.sh`). Sie sichert
`/srv/picoclaw` (ohne `stimmen-cache/` und `pycache-mpl/`) und die Dienst-Dateien samt Drop-ins – siehe [Betrieb → Sicherung](betrieb.md#sicherung).

## Was am Ende laufen muss

```bash
systemctl is-active picoclaw-gateway nookbot-adapter          # → active active
systemctl list-units --no-pager 'picoclaw@*'                   # je weiterem Bot ein Dienst (legt der Adapter an)
systemctl cat nookbot-adapter --no-pager | grep -E '^# |MemoryMax|RestrictAddress'
sudo ls /etc/polkit-1/rules.d/                                  # → 50-nookbot.rules
tailscale serve status
```

## Wiederherstellen aus der Sicherung

Statt Neuanlage: Bots, Verlauf, Routinen, Auslöser, Gedächtnis, Arbeitsordner **und alle Schlüssel** stecken in `nookbot.tar`.
Die Programme unter `/opt` sind nicht gesichert, sie kommen wieder aus den Skripten.

1. Voraussetzungen wie oben, Tailscale mit **demselben Namen** (sonst stimmen App-Adresse und ntfy-`base-url` nicht).
2. Code nach `/tmp` kopieren (siehe oben), dann `sudo bash /tmp/einrichtung/1-picoclaw-installieren.sh` – legt den
   Benutzer `picoclaw` an. `onboard` läuft dabei, das wird gleich überschrieben.
3. Sicherung holen und auspacken (rclone mit dem Ziel `pibackup:` einrichten, Schlüssel der Verschlüsselung aus dem Passwort-Manager):

   ```bash
   rclone lsf pibackup:taeglich | sort | tail -1                # neueste: pi-backup-<datum>.tar.gz
   mkdir -p ~/wiederherstellen && cd ~/wiederherstellen
   rclone copy pibackup:taeglich/pi-backup-<datum>.tar.gz .
   tar xzf pi-backup-<datum>.tar.gz                             # → nookbot.tar, home-mcp.tar, system/ …
   sudo systemctl stop nookbot-adapter picoclaw-gateway 2>/dev/null
   sudo rm -rf /srv/picoclaw && sudo tar -C /srv -xpf nookbot.tar
   sudo chown -R picoclaw:picoclaw /srv/picoclaw && sudo chmod 700 /srv/picoclaw   # UID kann auf dem neuen Pi anders sein
   ```

4. Dienst-Dateien zurück (darin auch die Drop-ins `stimmen.conf`, `rechner.conf`):

   ```bash
   sudo cp -a system/picoclaw-gateway.service system/picoclaw@.service system/nookbot-adapter.service system/nookbot-adapter.service.d /etc/systemd/system/
   sudo systemctl daemon-reload && sudo systemctl enable picoclaw-gateway nookbot-adapter
   ```

5. **Schritte 2–5, 7 und 13 auslassen** – Ollama/Brave, Kapitän, home-mcp-Schlüssel, Chat-Token, Claude und Nutzername
   stecken schon in der Sicherung. Schritt 3 würde außerdem `USER.md` überschreiben.
6. `sudo bash /tmp/einrichtung/6-adapter.sh` – Code, Polkit-Regel, `tailscale serve :8444`. Die Daten bleiben unberührt.
7. Je nach Bedarf 8, 9, 10, 11 (Programme unter `/opt`; Phonon braucht das Hugging-Face-Token erneut).
8. ntfy: Benutzerdatenbank und `server.yml` sind **nicht** in der Sicherung. Schritt 12 wiederholen; nur das ntfy-Token neu
   eintragen (Freigaben-Schlüssel leer lassen), in der ntfy-App neu anmelden.
9. home-mcp aus `home-mcp.tar` nach dessen eigener Anleitung zurückholen, **bevor** der Adapter startet – dann bleiben
   `home_key` und `freigabe_key` gültig.
10. Prüfen wie unter [Was am Ende laufen muss](#was-am-ende-laufen-muss), dann `/api/state`: die gewohnte Zahl Bots.
    Der Adapter startet alle `picoclaw@<id>` selbst.

Den Wiederherstellungstest ohne echten Ernstfall macht `pi-backup-pruefen.sh` (Home-Repo).

## Fallstricke

- **PicoClaw bleibt auf v0.3.1.** Streaming-Drossel, Ende-Erkennung und MCP-Verhalten sind auf diese Version abgestimmt.
  Eine neue Version erst ausprobieren, nie einfach `1-picoclaw-installieren.sh` mit anderer `PICO_VER` laufen lassen.
- **Adapter nicht neu starten, während Bots antworten oder eine Runde läuft** – die Antwort bricht ab. Vorher mit
  `/api/state` (Schritt 6) nachsehen: `laufend: []`. Der Adapter selbst verschiebt Neustarts einzelner Bots, bis sie fertig sind.
- **Neuer home-mcp-Schlüssel:** Der Adapter liest zuerst `home_key` aus `/srv/picoclaw/nookbot/config.json`. Nach
  `4-home-mcp-anbinden.sh` mit neuem Schlüssel deshalb `home_key` dort löschen (oder ersetzen) und den Adapter neu starten,
  sonst bleibt der alte, widerrufene Schlüssel aktiv.
- **Phonon** (Gradium, deutsche 40M-Modelle) steht unter **CC BY-NC 4.0** – nur privat, nicht für Kunden oder gewerblich.
  Fällt Phonon aus oder fehlt, spricht Piper.
- **Speicher:** Ohne die Drop-ins aus 8/9 hat der Adapter 900 MB; Stimme (~300 MB) und whisper (~500 MB) zusammen brauchen den 1500-MB-Drop-in.
- **bubblewrap** braucht `AF_NETLINK` im Adapter (Drop-in `rechner.conf`), sonst scheitert jeder Python-Aufruf beim Loopback.
- **Skripte mit Schlüssel-Abfrage** nur im eigenen Terminal laufen lassen, nicht über eine Fernsteuerung, die mitschreibt.
- **Rechte:** Alles unter `/srv/picoclaw` gehört `picoclaw`. Wer dort als root Dateien anlegt (z. B. beim Zurückkopieren),
  danach `chown -R picoclaw:picoclaw /srv/picoclaw`.
- **`tailscale serve` nur mit `--https=<port>`**, nie `tailscale funnel` für NookBot – der Adapter gehört nicht ins Internet.
