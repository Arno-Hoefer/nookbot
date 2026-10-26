# Sicherheit

## Erreichbarkeit

- Der Adapter lauscht nur auf `127.0.0.1`. Nach außen geht er nur über `tailscale serve` im eigenen Tailnet, ohne öffentlichen Zugang.
- App und Web melden sich mit dem Chat-Token an (`Authorization: Bearer …`). Die Bots bekommen einen eigenen MCP-Token.
- systemd-Dienste sind abgeschottet: `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`, `PrivateDevices`, eingeschränkte
  Adressfamilien, Speichergrenzen.

## Rechte je Bot

- home-mcp vergibt einen Schlüssel für alle Bots. Die Feinsteuerung macht der Adapter:
  - Konten je Dienst (Mail, Kalender, Drive) und „nur Hauptkalender“
  - Slack-Kanäle und Direktnachrichten, GitLab, einzelne Datenbanken, Statistik, Pakete
  - Gesundheit: gehört nie zu „Alles“
- Werkzeuge, die ein Bot nicht darf, sieht er gar nicht (`tools/list` gefiltert). Ruft er sie trotzdem auf, lehnt der Adapter ab.
- **Keine Rechte über Umwege:** `bot_fragen` und `auftraege_verteilen` gehen nur an Bots, deren Rechte eine Teilmenge der eigenen sind.
- Gesundheitsdaten kommen nie ins Gedächtnis und gehen nie an andere Bots.

## Schreiben mit Kontrolle

- Schreibende Werkzeuge von home-mcp legen eine **Freigabe** an. Ausgeführt wird erst nach dem Ja, mit genau den gespeicherten
  Argumenten und den Rechten, die der Bot dann hat. Offene Freigaben verfallen nach 24 Stunden.
- **Regeln je Bot und Aktion:**
  - *frei* entscheidet die Freigabe sofort, höchstens 10 am Tag. Ist das Limit erreicht, bleibt die Aktion offen.
  - *nie* sperrt das Werkzeug.
  - Gäste und Einladungen werden immer gefragt, `termin_gaeste` kann nicht *frei* sein.
- **Rücknahme:** ausgeführte Termine (ohne Absage-Mail) und Entwürfe lassen sich aus der Aktivität löschen.
- Mails werden nie gesendet, nur als Entwurf angelegt.

## Inhalte sind Daten

- Mails, Slack, Webseiten, Dokumente und Anhänge gelten als **Daten, nicht als Anweisungen**. Werkzeugergebnisse sind so gekennzeichnet,
  und die Bots werden in AGENT.md darauf hingewiesen.
- Das Gedächtnis übernimmt nur, was der Nutzer selbst sagt, nie etwas aus Mails oder Webseiten. Jeder Eintrag ist sichtbar
  und lässt sich zurücknehmen.

## Seiten und Netz

- `seite_lesen`, `seite_ansehen` und Auslöser für Webseiten öffnen nur **öffentliche** Adressen. `oeffentlich()` löst den Namen auf
  und sperrt private, lokale und Tailnet-Adressen. Der Pi-Browser prüft das ein zweites Mal.
- Ohne Anmeldung auf fremden Seiten, ohne gespeicherte Logins.

## Python-Sandkasten

`python_ausfuehren` läuft in **bubblewrap**:

- kein Netz (`--unshare-all`, eigenes Loopback)
- nur `/usr`, wenige Dateien aus `/etc` und `/tmp` sind sichtbar, dazu der Arbeitsordner (schreibbar) und die eigenen Anhänge (lesbar)
- keine Konfiguration, keine Schlüssel, keine anderen Bots
- 120 Sekunden, 1,5 GB Adressraum, 50 MB je Datei

Getestet: Netz gesperrt, Konfiguration nicht sichtbar, Endlosschleife nach 2 Minuten beendet.

## Schlüssel

- Nie im Repo. Schlüssel liegen auf dem Pi in Dateien mit Rechten 600, in der App im Android-Keystore.
- Die Einrichtungsskripte fragen Schlüssel verdeckt ab (`read -s`).

## Sprache

- Die Erkennung läuft auf dem Handy (whisper.cpp), das Audio verlässt das Gerät nicht. Im Web geht es bis zum Pi
  und wird nach dem Umwandeln verworfen.
- Phonon läuft auf dem Pi ohne Netz (`HF_HUB_OFFLINE`); das Hugging-Face-Token braucht nur die Einrichtung und wird nicht gespeichert.
- Alle Stimmen laufen auf dem Pi, kein Text geht für die Sprachausgabe nach außen.
- Das Anruf-Protokoll (`/api/diagnose`) enthält Pegel, Zeichenzahlen und Fehler, aber keine Inhalte.
