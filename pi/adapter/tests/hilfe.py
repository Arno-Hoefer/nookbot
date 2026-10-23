"""Gemeinsame Testumgebung: Adapter mit Daten in einem Temp-Ordner, ohne Pi (systemctl, home-mcp, Netz abgeschaltet)."""
import json
import os
import shutil
import sys
import tempfile
import time

TMP = tempfile.mkdtemp(prefix="nookbot-test-")
NB, MAIN = os.path.join(TMP, "nb"), os.path.join(TMP, "main")
os.environ.update(NOOKBOT_HOME=NB, NOOKBOT_MAIN_HOME=MAIN, NOOKBOT_PORT="18999")
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import nookbot_server as s  # noqa: E402

TOKEN = "test-token-lokal"
KATALOG = {"konten": {"mail": ["a@example.org", "b@example.org"], "kalender": ["a@example.org"], "drive": ["a@example.org"]},
           "db": ["heroku"], "gitlab": True, "statistik": True, "slack": True, "t": 9e12,
           "kanaele": [{"label": "#allgemein", "id": "C1", "dm": False}, {"label": "#vertrieb", "id": "C2", "dm": False},
                       {"label": "@anna", "id": "D1", "dm": True}]}

# Alles, was den Pi bräuchte, abklemmen
s.unit_active = lambda u: True
s.unit = lambda verb, u: "ok"
s.restart_bot = lambda b: None
s.katalog = lambda neu=False: KATALOG


def zuruecksetzen(bots_extra: bool = True):
    """Frischer Datenstand: Kapitän Reacher + Scout, leere Sitzungen, Konfiguration mit Nutzername."""
    shutil.rmtree(NB, ignore_errors=True)
    shutil.rmtree(MAIN, ignore_errors=True)
    os.makedirs(os.path.join(NB, "sessions"))
    os.makedirs(os.path.join(NB, "ausloeser"))
    s.write_json(os.path.join(MAIN, ".picoclaw", "config.json"),
                 {"channel_list": {"pico": {"settings": {"token": TOKEN}}},
                  "model_list": [{"model_name": "m", "model": "glm-5.3", "api_base": "http://127.0.0.1:9/v1", "api_keys": ["x"]}],
                  "agents": {"defaults": {"model_name": "m"}}})
    s.SESS.cache.clear()
    s.SESS.rt.clear()
    liste = [dict(s.DEFAULT_REACHER, erstellt=time.time(), home=MAIN)]
    if bots_extra:
        liste.append({"id": "scout", "name": "Scout", "rolle": "Recherche", "farbe": "#2FBF71", "form": "wolke", "kapitaen": False,
                      "zugriffe": False, "persoenlichkeit": "", "fokus": "Recherche", "home": os.path.join(MAIN, "bots", "scout"),
                      "unit": "x", "port": 18801, "erstellt": time.time()})
    s.write_json(s.path("bots.json"), liste)
    s.write_json(s.path("config.json"), {"nutzer": "Arno", "mcp_token": "nbm_test"})


def lies(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)
