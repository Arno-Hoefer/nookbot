"""Grundlagen: Pfade, JSON-Dateien, Konfiguration des Hauptbots, Nutzername, Protokoll."""
import json
import os
import re
import secrets
import threading
import time


VERSION = "0.17.1"
BASE = os.environ.get("NOOKBOT_HOME", "/srv/picoclaw/nookbot")
MAIN_HOME = os.environ.get("NOOKBOT_MAIN_HOME", "/srv/picoclaw")
BOTS_HOME = os.path.join(MAIN_HOME, "bots")
PORT = int(os.environ.get("NOOKBOT_PORT", "8795"))
HOME_MCP = "http://127.0.0.1:8790/mcp"
WISSEN_MAX = 300_000
WISSEN_ENDUNGEN = (".md", ".txt", ".csv", ".json", ".sql", ".yaml", ".yml")
_lock = threading.RLock()


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def path(*p) -> str:
    return os.path.join(BASE, *p)


def read_json(p, default):
    try:
        with open(p, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def write_json(p, d, mode=0o600):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p + ".tmp", "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=1)
    os.chmod(p + ".tmp", mode)
    os.replace(p + ".tmp", p)


def slug(s: str) -> str:
    s = s.lower().translate(str.maketrans({"ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss"}))
    return re.sub(r"[^a-z0-9]+", "-", s).strip("-")[:24] or "bot"


# ── Konfiguration des Hauptbots (Quelle für Token und Schlüssel – nichts davon geht an die App) ──

def main_config() -> dict:
    return read_json(os.path.join(MAIN_HOME, ".picoclaw", "config.json"), {})


def pico_token() -> str:
    return main_config().get("channel_list", {}).get("pico", {}).get("settings", {}).get("token", "")


def homemcp_key() -> str:
    """Der eine home-mcp-Schlüssel (Bot „nookbot“). Liegt in config.json; früher in der Konfiguration des Kapitäns – einmal übernommen."""
    c = read_json(path("config.json"), {})
    if c.get("home_key"):
        return c["home_key"]
    h = main_config().get("tools", {}).get("mcp", {}).get("servers", {}).get("home", {}).get("headers", {})
    k = h.get("Authorization", "").removeprefix("Bearer ").strip()
    if k.startswith("hmk_"):
        c["home_key"] = k
        write_json(path("config.json"), c)
    return k if k.startswith("hmk_") else ""


def ollama() -> tuple[str, str]:
    m = (main_config().get("model_list") or [{}])[0]
    return (m.get("api_base") or "https://ollama.com/v1").rstrip("/"), (m.get("api_keys") or [""])[0]


def nutzer() -> str:
    """Wie die Bots den Menschen nennen – aus config.json ("nutzer"), nicht im Code."""
    return str(conf().get("nutzer") or "der Nutzer")


def n(text: str) -> str:
    return text.replace("%NUTZER%", nutzer())


def conf() -> dict:
    c = read_json(path("config.json"), {})
    if not c.get("mcp_token"):
        c["mcp_token"] = "nbm_" + secrets.token_urlsafe(32)
        write_json(path("config.json"), c)
    return c
