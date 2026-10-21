#!/usr/bin/env bash
# NookBot 7 (optional): Claude (Anthropic) als zweiten Modell-Anbieter einrichten – danach in der App je Bot wählbar.
# Fragt den API-Schlüssel verdeckt ab und prüft ihn; optional einen Admin-Schlüssel (nur Kosten lesen) für die Ausgaben-Anzeige.
# Beides landet nur in /srv/picoclaw/nookbot/claude.json (chmod 600), nie im Repo und nie in der App.
# Aufruf (im eigenen Terminal): sudo bash 7-claude.sh
set -euo pipefail
F=/srv/picoclaw/nookbot/claude.json

read -rsp "Claude-API-Schlüssel (sk-ant-api…, leer = unverändert): " KEY; echo
read -rsp "Admin-Schlüssel für die Kosten (sk-ant-admin…, optional, leer = ohne): " ADMIN; echo
export KEY ADMIN F

if [ -n "$KEY" ]; then
  echo "== Schlüssel prüfen"
  code=$(curl -s -o /dev/null -w "%{http_code}" https://api.anthropic.com/v1/models -H "x-api-key: $KEY" -H "anthropic-version: 2023-06-01")
  [ "$code" = "200" ] || { echo "Anthropic lehnt den Schlüssel ab (HTTP $code)"; exit 1; }
  echo "ok"
fi
if [ -n "$ADMIN" ]; then
  echo "== Admin-Schlüssel prüfen"
  von=$(date -u +%Y-%m-01T00:00:00Z)
  code=$(curl -s -o /dev/null -w "%{http_code}" "https://api.anthropic.com/v1/organizations/cost_report?starting_at=$von&limit=1" \
    -H "x-api-key: $ADMIN" -H "anthropic-version: 2023-06-01")
  [ "$code" = "200" ] || { echo "Kosten-Abfrage klappt nicht (HTTP $code) – Admin-Schlüssel gibt es nur für Organisationen"; exit 1; }
  echo "ok"
fi

python3 - <<'PY'
import json, os
f = os.environ["F"]
try:
    c = json.load(open(f))
except (OSError, ValueError):
    c = {}
if os.environ["KEY"]:
    c["api_key"] = os.environ["KEY"].strip()
if os.environ["ADMIN"]:
    c["admin_key"] = os.environ["ADMIN"].strip()
os.makedirs(os.path.dirname(f), exist_ok=True)
with open(f + ".tmp", "w") as fh:
    json.dump(c, fh, indent=1)
os.chmod(f + ".tmp", 0o600)
os.replace(f + ".tmp", f)
print("gespeichert:", ", ".join(k for k in ("api_key", "admin_key") if c.get(k)))
PY
chown picoclaw:picoclaw "$F"
systemctl restart nookbot-adapter
echo "Fertig – in der App unter Bot → Modell gibt es jetzt die Claude-Modelle."
