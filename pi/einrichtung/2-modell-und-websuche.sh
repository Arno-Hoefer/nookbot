#!/usr/bin/env bash
# NookBot 2/6: PicoClaw für Ollama Cloud (Modell glm-5.3) und Brave-Websuche einrichten und absichern.
# Fragt die Keys verdeckt ab – sie landen nur in den Konfigurationen auf dem Pi (chmod 600).
# Aufruf (im eigenen Terminal, fragt die Keys verdeckt ab): sudo bash 2-modell-und-websuche.sh
set -euo pipefail

read -rsp "Ollama-API-Key: " OLLAMA_KEY; echo
read -rsp "Brave-Search-Key: " BRAVE_KEY; echo
export OLLAMA_KEY BRAVE_KEY

echo "== Modelle bei Ollama Cloud"
MODELS=$(curl -fsS -H "Authorization: Bearer $OLLAMA_KEY" https://ollama.com/v1/models) || { echo "Key falsch oder Ollama nicht erreichbar"; exit 1; }
export MODELS
python3 - <<'PY'
import json, os
ids = sorted(m["id"] for m in json.loads(os.environ["MODELS"])["data"])
print(", ".join(ids))
PY

python3 - <<'PY'
import json, os, pwd

ids = [m["id"] for m in json.loads(os.environ["MODELS"])["data"]]
def pick(*wants):
    for w in wants:
        for i in ids:
            if i == w or i.split(":")[0] == w:
                return i
    raise SystemExit(f"Kein Modell aus {wants} gefunden")
MAIN = pick("glm-5.3", "glm-5.1", "glm-5")
FLASH = pick("glm-5.3-flash", "glm-5.3", "glm-5")
print("Hauptmodell:", MAIN, "| schnell:", FLASH)
ok, bk = os.environ["OLLAMA_KEY"], os.environ["BRAVE_KEY"]

def save(path, data, user):
    with open(path + ".tmp", "w") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
    pw = pwd.getpwnam(user)
    os.chown(path + ".tmp", pw.pw_uid, pw.pw_gid)
    os.chmod(path + ".tmp", 0o600)
    os.replace(path + ".tmp", path)

# ---------- PicoClaw ----------
p = "/srv/picoclaw/.picoclaw/config.json"
c = json.load(open(p))
d = c["agents"]["defaults"]
d.update(model_name="kapitaen", provider="ollama", restrict_to_workspace=True, allow_read_outside_workspace=False)
c["model_list"] = [
    {"model_name": "kapitaen", "provider": "ollama", "model": MAIN, "api_base": "https://ollama.com/v1",
     "api_keys": [ok], "request_timeout": 300},
    {"model_name": "flash", "provider": "ollama", "model": FLASH, "api_base": "https://ollama.com/v1",
     "api_keys": [ok], "request_timeout": 300},
]
t = c["tools"]
t["exec"]["enabled"] = False
t["cron"]["allow_command"] = False
t["install_skill"]["enabled"] = False
t["find_skills"]["enabled"] = False
for r in t["skills"]["registries"].values():
    r["enabled"] = False
w = t["web"]
w["provider"], w["prefer_native"] = "brave", False
w["sogou"]["enabled"] = False
w["brave"].update(enabled=True, api_keys=[bk], max_results=5)
c["heartbeat"]["enabled"] = False            # im Test keine selbstständigen Modellaufrufe
c["gateway"].update(host="127.0.0.1", port=18790)
save(p, c, "picoclaw")
print("PicoClaw konfiguriert")

PY
unset OLLAMA_KEY BRAVE_KEY MODELS
echo "FERTIG"
