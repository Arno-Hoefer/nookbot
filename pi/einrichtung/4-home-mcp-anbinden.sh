#!/usr/bin/env bash
# NookBot 4/6: PicoClaw an home-mcp anbinden: fragt den Bot-Schlüssel (hmk_…, Art „für den Bot“) verdeckt ab,
# prüft ihn gegen home-mcp und trägt ihn als MCP-Server „home“ ein.
# Aufruf (in DEINEM Terminal): sudo bash 4-home-mcp-anbinden.sh
set -euo pipefail

read -rsp "home-mcp Bot-Schlüssel (hmk_…): " HMK; echo
case "$HMK" in hmk_*) ;; *) echo "Das ist kein home-mcp-Schlüssel (beginnt mit hmk_)."; exit 1;; esac
export HMK

echo "== Schlüssel prüfen"
python3 - <<'PY'
import json, os, urllib.request
req = urllib.request.Request("http://127.0.0.1:8790/mcp", method="POST",
    data=json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}).encode(),
    headers={"Content-Type": "application/json", "Authorization": "Bearer " + os.environ["HMK"]})
try:
    tools = json.loads(urllib.request.urlopen(req, timeout=10).read())["result"]["tools"]
except Exception as e:
    raise SystemExit(f"home-mcp lehnt den Schlüssel ab ({e}) – ist es der Schlüssel „für den Bot“?")
print(f"ok – {len(tools)} Werkzeuge:", ", ".join(t["name"] for t in tools))
PY

python3 - <<'PY'
import json, os, pwd
p = "/srv/picoclaw/.picoclaw/config.json"
c = json.load(open(p))
c["tools"]["mcp"]["enabled"] = True
c["tools"]["mcp"]["servers"] = {"home": {"enabled": True, "type": "http", "url": "http://127.0.0.1:8790/mcp",
                                         "headers": {"Authorization": "Bearer " + os.environ["HMK"]}}}
with open(p + ".tmp", "w") as f:
    json.dump(c, f, indent=2, ensure_ascii=False)
pw = pwd.getpwnam("picoclaw")
os.chown(p + ".tmp", pw.pw_uid, pw.pw_gid)
os.chmod(p + ".tmp", 0o600)
os.replace(p + ".tmp", p)
print("PicoClaw: MCP-Server „home“ eingetragen")
PY
unset HMK
cd /srv/picoclaw && sudo -u picoclaw env HOME=/srv/picoclaw /opt/picoclaw/picoclaw --no-color mcp test home 2>&1 | tail -1
systemctl restart picoclaw-gateway && sleep 3 && echo "Dienst neu gestartet: $(systemctl is-active picoclaw-gateway)"
echo "FERTIG"
