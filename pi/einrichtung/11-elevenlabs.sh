#!/usr/bin/env bash
# NookBot 11 (optional): Cloud-Stimmen von ElevenLabs (auch mit dem kostenlosen Tarif, ~10 Minuten Sprache im Monat).
# Schlüssel: elevenlabs.io → Profil → API Keys → neuer Schlüssel, Rechte nur „Text to Speech“ und „Voices: Read“ (+ „User: Read“ fürs Kontingent).
# Aufruf: sudo bash 11-elevenlabs.sh   – der Schlüssel wird verdeckt abgefragt und landet nur auf dem Pi.
set -euo pipefail
read -rsp "ElevenLabs-API-Schlüssel: " KEY; echo
[ -n "$KEY" ] || { echo "Kein Schlüssel."; exit 1; }
D=/srv/picoclaw/nookbot
KEY="$KEY" python3 - <<'P'
import json, os
p = "/srv/picoclaw/nookbot/elevenlabs.json"
c = json.load(open(p)) if os.path.exists(p) else {}
c["api_key"] = os.environ["KEY"]
open(p, "w").write(json.dumps(c, indent=1))
P
chown picoclaw:picoclaw $D/elevenlabs.json && chmod 600 $D/elevenlabs.json
code=$(curl -s -o /dev/null -w '%{http_code}' -H "xi-api-key: $KEY" https://api.elevenlabs.io/v1/voices)
echo "Test bei ElevenLabs: HTTP $code (200 = passt)"
systemctl restart nookbot-adapter
echo "Fertig – die ElevenLabs-Stimmen stehen jetzt in der App unter Bot → Stimme."
