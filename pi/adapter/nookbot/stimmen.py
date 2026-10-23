"""Stimmen: Piper auf dem Pi, ElevenLabs, Aussprache, Vorlesetext, Diktat (whisper.cpp)."""
import json
import os
import re
import secrets
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from . import basis


# ── Stimmen (Piper auf dem Pi) und Aussprache ─────────────────────
# Piper liegt unter /opt/piper, Stimmen unter /opt/piper/stimmen (einrichtung/8-stimmen.sh). Eine Stimme bleibt geladen
# (Prozess mit --json-input), damit jeder Satz schnell kommt. Vor dem Sprechen ersetzt das Aussprache-Wörterbuch Namen.

PIPER = os.environ.get("NOOKBOT_PIPER", "/opt/piper")
STIMMEN = [  # id, Anzeige, Beschreibung, Sprecher (bei Mehrsprecher-Modellen)
    ("thorsten-high", "Thorsten", "männlich, am natürlichsten", None),
    ("thorsten-medium", "Thorsten (schnell)", "männlich, etwas einfacher, antwortet flotter", None),
    ("thorsten_emotional-medium:neutral", "Thorsten ruhig", "männlich, ruhig", "neutral"),
    ("thorsten_emotional-medium:amused", "Thorsten heiter", "männlich, gut gelaunt", "amused"),
    ("thorsten_emotional-medium:surprised", "Thorsten überrascht", "männlich, lebhaft", "surprised"),
    ("thorsten_emotional-medium:whisper", "Thorsten flüsternd", "männlich, leise", "whisper"),
    ("kerstin-low", "Kerstin", "weiblich", None),
    ("ramona-low", "Ramona", "weiblich", None),
    ("eva_k-x_low", "Eva", "weiblich, einfach", None),
    ("karlsson-low", "Karlsson", "männlich", None),
    ("pavoque-low", "Pavoque", "männlich", None),
]
STANDARD_STIMME = "pi:thorsten-high"
AUSSPRACHE_START = {"Reacher": "Rietscher", "NookBot": "Nuck-Bot", "Sentry": "Sentri", "Scout": "Skaut", "Forge": "Fordsch",
                    "Slack": "Släck", "GitLab": "Git-Läb", "Claude": "Klohd", "Ollama": "Olama", "Raspberry Pi": "Räsbärri Pei",
                    "Pi": "Pei", "Home-MCP": "Hohm M C P", "home-mcp": "Hohm M C P", "EGYM": "Ie-Dschim",
                    "Health Connect": "Hels Konnekt", "Google": "Gugel", "Drive": "Draiw", "Sheets": "Schiets", "Docs": "Docks"}
_piper: dict = {}  # (modell, tempo) → {"p": Popen, "lock": Lock, "zuletzt": t}
_piper_lock = threading.Lock()


def stimmen_liste() -> list:
    da = set(os.listdir(os.path.join(PIPER, "stimmen"))) if os.path.isdir(os.path.join(PIPER, "stimmen")) else set()
    pi = [{"id": "pi:" + i, "name": n, "beschreibung": b} for i, n, b, _ in STIMMEN if f"de_DE-{i.split(':')[0]}.onnx" in da]
    return el_stimmen() + pi


# ── ElevenLabs (Cloud-Stimmen, optional) ────────────────────────────
# Schlüssel in elevenlabs.json (einrichtung/11-elevenlabs.sh). Stimmen-ids „el:<voice_id>“. Sprache spricht das Modell
# mehrsprachig – britische Stimmen behalten beim Deutschen ihren Einschlag. Jeder Satz kostet Zeichen vom Kontingent;
# gleiche Sätze kommen aus dem Zwischenspeicher. Ist das Kontingent leer oder ElevenLabs weg, spricht Thorsten auf dem Pi.

EL_API = "https://api.elevenlabs.io"
EL_MODELL = "eleven_flash_v2_5"  # schnell (~0,4 s je Satz); „eleven_multilingual_v2“ klingt etwas voller, braucht ~1,8 s
_el_cache: dict = {"t": 0, "stimmen": []}
EL_STANDARD = [("George", "JBFqnCBsd6RMkjVDRZzb", "britisch, männlich, warm"), ("Daniel", "onwK4e9ZLuTAKqWW03F9", "britisch, männlich, sachlich"),
               ("Alice", "Xb7hH8MSUJpSbSDYk0k2", "britisch, weiblich")]


def el_conf() -> dict:
    return basis.read_json(basis.path("elevenlabs.json"), {})


def _el(method: str, p: str, body: dict | None = None, roh: bool = False, timeout: int = 30):
    key = el_conf().get("api_key")
    if not key:
        raise ValueError("ElevenLabs ist nicht eingerichtet.")
    req = urllib.request.Request(EL_API + p, data=json.dumps(body).encode() if body is not None else None, method=method,
                                 headers={"xi-api-key": key, "Content-Type": "application/json", "User-Agent": "NookBot/" + basis.VERSION})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = r.read()
    except urllib.error.HTTPError as e:
        txt = e.read()[:300].decode(errors="replace")
        raise ValueError(f"ElevenLabs {e.code}: {txt}") from None
    return data if roh else json.loads(data or b"{}")


BUTLER_KLANG = ("highpass=f=75,equalizer=f=180:t=q:w=1:g=2,equalizer=f=2800:t=q:w=1.5:g=1.5,"
                "aecho=0.9:0.4:14|23:0.06|0.04,loudnorm=I=-16")


def klang(wav: bytes, filt: str) -> bytes:
    """WAV durch ffmpeg schicken (Butler-Klang: wärmer, präsenter, ein Hauch Raum).
    ffmpeg gibt rohe Samples aus und Python schreibt den WAV-Kopf – in eine Pipe kann ffmpeg die Länge nicht eintragen,
    und solche Dateien spielt Android nicht ab."""
    import io
    import wave
    r = subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "wav", "-i", "pipe:0", "-af", filt, "-ar", "22050", "-ac", "1",
                        "-f", "s16le", "pipe:1"], input=wav, capture_output=True, timeout=60)
    if r.returncode or not r.stdout:
        return wav
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(22050)
        w.writeframes(r.stdout)
    return buf.getvalue()


def el_stimmen() -> list:
    """Jede Cloud-Stimme auch als „· Butler“-Variante (…+butler)."""
    return [y for x in _el_stimmen_roh() for y in (x, {"id": x["id"] + "+butler", "name": x["name"].replace(" (ElevenLabs)", " · Butler (ElevenLabs)"),
                                                     "beschreibung": x["beschreibung"].replace(" · Cloud", ", wärmer mit etwas Raum · Cloud")})]


def _el_stimmen_roh() -> list:
    if not el_conf().get("api_key"):
        return []
    if time.time() - _el_cache["t"] > 3600:
        try:
            vs = _el("GET", "/v1/voices").get("voices", [])
            out = []
            for v in vs:
                lab = v.get("labels") or {}
                besch = ", ".join(x for x in (lab.get("accent"), lab.get("gender"), lab.get("descriptive") or lab.get("description"), lab.get("age")) if x)
                out.append({"id": "el:" + v["voice_id"], "name": f"{v.get('name', '?').split(' - ')[0]} (ElevenLabs)",
                            "beschreibung": (besch + " · " if besch else "") + "Cloud, Kontingent"})
            # britische zuerst – passt zum Butler
            out.sort(key=lambda x: (0 if "british" in x["beschreibung"].lower() else 1, x["name"]))
            _el_cache.update(t=time.time(), stimmen=out)
        except Exception as e:  # noqa: BLE001
            basis.log("ElevenLabs Stimmen:", e)
            _el_cache["t"] = time.time() - 3000  # in 10 Minuten noch einmal
            if not _el_cache["stimmen"]:  # Schlüssel ohne „Voices: Read“ – die bekannten Standardstimmen gehen trotzdem
                _el_cache["stimmen"] = [{"id": "el:" + i, "name": f"{nm} (ElevenLabs)", "beschreibung": f"{b} · Cloud, Kontingent"}
                                        for nm, i, b in EL_STANDARD]
    return _el_cache["stimmen"]


_el_stand: dict = {"t": 0, "v": None}


def el_kontingent(frisch: bool = False) -> dict | None:
    """Verbrauch bei ElevenLabs – 2 Minuten zwischengespeichert (die App fragt bei jedem Laden)."""
    if not el_conf().get("api_key"):
        return None
    if not frisch and time.time() - _el_stand["t"] < 120:
        return _el_stand["v"]
    _el_stand.update(t=time.time(), v=_el_kontingent())
    return _el_stand["v"]


def _el_kontingent() -> dict | None:
    try:
        o = _el("GET", "/v1/user/subscription")
        return {"verbraucht": o.get("character_count"), "grenze": o.get("character_limit"), "tarif": o.get("tier"),
                "neu_ab": o.get("next_character_count_reset_unix")}
    except Exception as e:  # noqa: BLE001
        return {"fehler": str(e)[:200]}


def el_sprechen(text: str, voice: str, tempo: float = 1.0) -> bytes:
    import hashlib as _h
    import io
    import wave
    c = el_conf()
    modell = c.get("modell") or EL_MODELL
    d = basis.path("stimmen-cache")
    os.makedirs(d, exist_ok=True)
    fp = os.path.join(d, _h.sha1(f"{voice}|{modell}|{tempo:.2f}|{text}".encode()).hexdigest() + ".wav")
    if os.path.exists(fp):
        os.utime(fp)
        return open(fp, "rb").read()
    body = {"text": text, "model_id": modell,
            "voice_settings": {"stability": float(c.get("stabilitaet", 0.55)), "similarity_boost": 0.75, "style": float(c.get("stil", 0.15)),
                               "use_speaker_boost": True, "speed": min(max(tempo, 0.7), 1.2)}}
    pcm = _el("POST", f"/v1/text-to-speech/{urllib.parse.quote(voice)}?output_format=pcm_22050", body, roh=True, timeout=60)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(22050)
        w.writeframes(pcm)
    data = buf.getvalue()
    with open(fp, "wb") as f:
        f.write(data)
    dateien = sorted((os.path.join(d, x) for x in os.listdir(d)), key=os.path.getmtime)
    for alt in dateien[:-300]:  # Zwischenspeicher klein halten
        os.remove(alt)
    return data


def aussprache() -> dict:
    d = basis.read_json(basis.path("aussprache.json"), None)
    if d is None:
        d = dict(AUSSPRACHE_START)
        basis.write_json(basis.path("aussprache.json"), d)
    return d


_TAGE = {"Mo": "Montag", "Di": "Dienstag", "Mi": "Mittwoch", "Do": "Donnerstag", "Fr": "Freitag", "Sa": "Samstag", "So": "Sonntag"}
_MONATE = ["Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"]
_KUERZEL = [(r"z\.\s?B\.", "zum Beispiel"), (r"\bca\.", "circa"), (r"\bbzw\.", "beziehungsweise"), (r"\bggf\.", "gegebenenfalls"),
            (r"\busw\.", "und so weiter"), (r"\binkl\.", "inklusive"), (r"\bzzgl\.", "zuzüglich"), (r"\bd\.\s?h\.", "das heißt"),
            (r"\bu\.\s?a\.", "unter anderem"), (r"\bNr\.", "Nummer"), (r"\bStd\.", "Stunden"), (r"\bMin\.", "Minuten"),
            (r"\bTel\.", "Telefon"), (r"\bvs\.", "gegen")]


def sprechbar(text: str) -> str:
    """Text so aufbereiten, wie man ihn vorliest: Wochentage, Datum, Uhrzeit, Abkürzungen, Zeichen."""
    def uhr(h: str, m: str) -> str:
        return f"{int(h)} Uhr" + ("" if m == "00" else f" {int(m)}")
    t = text
    # Zeitspannen und Uhrzeiten (06:30–13:15 Uhr → 6 Uhr 30 bis 13 Uhr 15)
    t = re.sub(r"\b([01]?\d|2[0-3]):([0-5]\d)\s*[–-]\s*([01]?\d|2[0-3]):([0-5]\d)(\s*Uhr)?\b",
               lambda m: f"{uhr(m[1], m[2])} bis {uhr(m[3], m[4])}", t)
    t = re.sub(r"\b([01]?\d|2[0-3]):([0-5]\d)(\s*Uhr)?\b", lambda m: uhr(m[1], m[2]), t)
    # Wochentag-Kürzel nur direkt vor einem Datum (sonst bleibt „So“ ein „so“)
    t = re.sub(r"\b(Mo|Di|Mi|Do|Fr|Sa|So)\.?,?\s+(?=\d{1,2}\.)", lambda m: _TAGE[m[1]] + ", ", t)
    # Datum 14.11.(2026) → 14. November (2026)
    t = re.sub(r"\b(\d{1,2})\.(\d{1,2})\.(\d{4}|\d{2})?(?!\d)",
               lambda m: f"{int(m[1])}. {_MONATE[int(m[2]) - 1]}" + (f" {m[3] if len(m[3]) == 4 else '20' + m[3]}" if m[3] else "")
               if 1 <= int(m[2]) <= 12 and 1 <= int(m[1]) <= 31 else m[0], t)
    t = re.sub(r"\b((?:" + "|".join(_MONATE) + r")(?: \d{4})?)(?! \d{4}\b) (?=[A-Za-zÄÖÜäöü0-9])", r"\1, ", t)  # kleine Pause nach dem Datum
    for muster, voll in _KUERZEL:
        t = re.sub(muster, voll, t)
    t = re.sub(r"\s*€", " Euro", t)
    t = re.sub(r"\s*%", " Prozent", t)
    t = t.replace("&", " und ").replace(" – ", ", ").replace(" - ", ", ")
    return re.sub(r"\s{2,}", " ", t).strip()


def aussprache_anwenden(text: str) -> str:
    for wort, laut in sorted(aussprache().items(), key=lambda x: -len(x[0])):  # längere zuerst („Raspberry Pi“ vor „Pi“)
        text = re.sub(r"(?<![\w-])" + re.escape(wort) + r"(?![\w-])", laut, text, flags=re.IGNORECASE)
    return text


def _piper_prozess(modell: str, tempo: float) -> dict:
    with _piper_lock:
        key = (modell, tempo)
        pr = _piper.get(key)
        if pr and pr["p"].poll() is None:
            pr["zuletzt"] = time.time()
            return pr
        for k in list(_piper):  # nur eine Stimme warm halten – Speicher auf dem Pi
            _piper.pop(k)["p"].kill()
        p_ = subprocess.Popen([os.path.join(PIPER, "piper"), "-m", os.path.join(PIPER, "stimmen", f"de_DE-{modell}.onnx"),
                               "--json-input", "--length_scale", f"{tempo:.2f}", "--sentence_silence", "0.15"],
                              stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, cwd="/tmp", text=True, bufsize=1)
        pr = {"p": p_, "lock": threading.Lock(), "zuletzt": time.time()}
        _piper[key] = pr
        return pr


def sprechen(text: str, stimme: str, tempo: float = 1.0) -> bytes:
    """Ein Stück Text (am besten ein Satz) mit einer Pi- oder ElevenLabs-Stimme → WAV-Bytes."""
    if stimme.startswith("el:"):
        t = sprechbar(" ".join(str(text).split()))[:1200]  # kein Aussprache-Wörterbuch: englische Namen spricht die Stimme selbst richtig
        if not t:
            raise ValueError("Kein Text.")
        try:
            vid, _, k = stimme[3:].partition("+")
            w = el_sprechen(t, vid, float(tempo or 1.0))
            return klang(w, BUTLER_KLANG) if k == "butler" else w
        except Exception as e:  # noqa: BLE001
            basis.log("ElevenLabs → Thorsten:", e)  # Kontingent leer oder offline – dann eben der Pi
            stimme = STANDARD_STIMME
    sid = stimme.removeprefix("pi:") or STANDARD_STIMME.removeprefix("pi:")
    eintrag = next((x for x in STIMMEN if x[0] == sid), None)
    if not eintrag:
        raise ValueError("Diese Stimme gibt es nicht.")
    modell, _, sprecher = sid.partition(":")
    tempo = min(max(float(tempo or 1.0), 0.6), 1.6)
    text = aussprache_anwenden(sprechbar(" ".join(str(text).split())))[:1200]
    if not text:
        raise ValueError("Kein Text.")
    ziel = f"/tmp/stimme-{secrets.token_hex(6)}.wav"
    zeile = {"text": text, "output_file": ziel}
    if sprecher:
        cfg = basis.read_json(os.path.join(PIPER, "stimmen", f"de_DE-{modell}.onnx.json"), {})
        zeile["speaker_id"] = cfg.get("speaker_id_map", {}).get(sprecher, 0)
    for versuch in (1, 2):
        pr = _piper_prozess(modell, 1.0 / tempo)  # length_scale: größer = langsamer
        with pr["lock"]:
            try:
                pr["p"].stdin.write(json.dumps(zeile, ensure_ascii=False) + "\n")
                pr["p"].stdin.flush()
                antwort = pr["p"].stdout.readline()
            except (BrokenPipeError, OSError):
                antwort = ""
        if antwort.strip() and os.path.exists(ziel):
            with open(ziel, "rb") as f:
                data = f.read()
            os.remove(ziel)
            return data
        pr["p"].kill()  # hängt oder ist abgestürzt → einmal neu starten
    raise ValueError("Die Sprachausgabe auf dem Pi antwortet nicht.")


def piper_aufraeumen():
    with _piper_lock:
        for k, pr in list(_piper.items()):
            if time.time() - pr["zuletzt"] > 900:
                _piper.pop(k)["p"].kill()


# ── Diktat für die Web-Oberfläche (whisper.cpp auf dem Pi) ─────────
# Die App erkennt Sprache auf dem Handy; der Browser schickt die Aufnahme hierher. Audio bleibt nur bis zum Ende im Speicher-Ordner.

WHISPER_BIN = "/opt/nookbot/whisper/whisper-cli"
WHISPER_MODELL = "/opt/nookbot/whisper/ggml-small-q5_1.bin"
_whisper_lock = threading.Lock()


def diktat(b64: str) -> str:
    import base64
    import tempfile
    if not (os.path.exists(WHISPER_BIN) and os.path.exists(WHISPER_MODELL)):
        raise ValueError("Spracherkennung ist auf dem Pi nicht eingerichtet (einrichtung/8-whisper.sh).")
    daten = base64.b64decode(b64 or "")
    if not 1000 < len(daten) < 30_000_000:
        raise ValueError("Aufnahme zu kurz oder zu lang.")
    with tempfile.TemporaryDirectory() as tmp, _whisper_lock:
        roh, wav = os.path.join(tmp, "a.bin"), os.path.join(tmp, "a.wav")
        with open(roh, "wb") as f:
            f.write(daten)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-i", roh, "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", wav], check=True, timeout=120)
        r = subprocess.run([WHISPER_BIN, "-m", WHISPER_MODELL, "-l", "de", "-t", "3", "-nt", "-np", "-f", wav,
                            # Namen vorab nennen – sonst wird aus „Reacher“ schnell „Ritsche“
                            "--prompt", "NookBot, " + ", ".join([basis.nutzer()] + [x["name"] for x in bots.bots()]) + "."],
                           capture_output=True, text=True, timeout=600)
        if r.returncode:
            raise ValueError("Spracherkennung fehlgeschlagen: " + r.stderr[-200:])
    text = re.sub(r"[\[(\*][^\])\*]{1,30}[\])\*]", "", r.stdout)
    return re.sub(r"\s+", " ", text).strip()


from . import bots  # noqa: E402
