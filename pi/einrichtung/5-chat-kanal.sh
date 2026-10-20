#!/usr/bin/env bash
# NookBot 5/6: Chat-Kanal „pico“ (WebSocket /pico/ws) mit neuem Token einschalten – gedrosselt (max. 1 Update/s),
# sonst schickt PicoClaw bei jedem Zeichen den ganzen Text und die Verbindung reißt ab.
# Der Token ist zugleich der Schlüssel der App zum Adapter. Er wird EINMAL angezeigt.
# Aufruf (im eigenen Terminal): sudo bash 5-chat-kanal.sh
set -euo pipefail

TOKEN=$(python3 -c 'import secrets; print("nb_" + secrets.token_urlsafe(32))')
export TOKEN
python3 - <<'PY'
import json, os, pwd
p = "/srv/picoclaw/.picoclaw/config.json"
c = json.load(open(p))
ch = c["channel_list"]["pico"]
ch["enabled"] = True
ch["allow_from"] = []
st = ch.setdefault("settings", {})
st.update(token=os.environ["TOKEN"], allow_token_query=False, allow_origins=[], max_connections=10)
st["streaming"] = {"enabled": True, "throttle_seconds": 1, "min_growth_chars": 60}
st["read_timeout"] = 120
st["ping_interval"] = 25
for m in c["model_list"]:
    m["streaming"] = {"enabled": True}
c["agents"]["defaults"]["tool_feedback"].update(enabled=True, separate_messages=False)
with open(p + ".tmp", "w") as f:
    json.dump(c, f, indent=2, ensure_ascii=False)
pw = pwd.getpwnam("picoclaw")
os.chown(p + ".tmp", pw.pw_uid, pw.pw_gid)
os.chmod(p + ".tmp", 0o600)
os.replace(p + ".tmp", p)
print("Kanal pico eingeschaltet")
PY
systemctl restart picoclaw-gateway
sleep 4
systemctl is-active picoclaw-gateway
journalctl -u picoclaw-gateway -n 12 --no-pager -o cat | grep -i "pico\|channel\|error" | cut -c1-140 || true

echo
echo "=================  Chat-Token für die NookBot-App (nur jetzt sichtbar)  ================="
echo "$TOKEN"
echo "==========================================================================================="
unset TOKEN
echo "FERTIG – Token in den Passwort-Manager legen und in der App eintragen."
