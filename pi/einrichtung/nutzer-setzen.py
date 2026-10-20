#!/usr/bin/env python3
"""Wie die Bots den Menschen nennen (steht nur auf dem Pi, nicht im Code). Aufruf: sudo python3 nutzer-setzen.py <Name>"""
import json, os, pwd, sys
p = "/srv/picoclaw/nookbot/config.json"
c = json.load(open(p)) if os.path.exists(p) else {}
c["nutzer"] = sys.argv[1].strip()
with open(p + ".tmp", "w") as f:
    json.dump(c, f, indent=1)
pw = pwd.getpwnam("picoclaw")
os.chown(p + ".tmp", pw.pw_uid, pw.pw_gid)
os.chmod(p + ".tmp", 0o600)
os.replace(p + ".tmp", p)
print("nutzer =", c["nutzer"])
