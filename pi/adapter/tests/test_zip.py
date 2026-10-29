"""Zip-Anhänge entpacken (Zip-Slip, Bomben, Grenzen) und Dateien aus dem Arbeitsordner an drive_hochladen/drive_ordner_paket weitergeben."""
import base64
import io
import json
import os
import stat
import unittest
import zipfile
from unittest import mock

from hilfe import s, zuruecksetzen
from nookbot import home, wissen, rechner


def zip_bytes(eintraege: dict, extra=None) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for n, d in eintraege.items():
            z.writestr(n, d)
        for info, d in (extra or []):
            z.writestr(info, d)
    return buf.getvalue()


def docx(text: str) -> bytes:
    return zip_bytes({"word/document.xml": f'<w:document xmlns:w="w"><w:body><w:p><w:r><w:t>{text}</w:t></w:r></w:p></w:body></w:document>'})


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode()


class ZipEntpacken(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()
        self.b = dict(s.bot("reacher"), zugriffe=True, rechte={"drive": ["a@example.org"]})
        self.ws = s.workspace(self.b)
        self.sid = s.SESS.create("reacher")["id"]

    def test_anhang_wird_entpackt(self):
        data = zip_bytes({"Plan/Übersicht.txt": "Café Pulheim – Konzept", "Plan/Finanzen/zahlen.csv": "Monat;Umsatz\nJan;100\n",
                          "Plan/Antrag.docx": docx("Gründungszuschuss beantragen"), "Plan/foto.jpg": b"\xff\xd8\xff",
                          "__MACOSX/Plan/._Antrag.docx": b"x", "Plan/.DS_Store": b"x", "Plan/leer/": b""})
        a = s.anhang_speichern(self.sid, "unterlagen.zip", b64(data))
        self.assertEqual(a["art"], "zip")
        self.assertEqual(a["dateien"], 4)
        ordner = os.path.join(self.ws, a["ordner"])
        self.assertTrue(os.path.isfile(os.path.join(ordner, "Plan", "Finanzen", "zahlen.csv")))
        self.assertFalse(os.path.exists(os.path.join(ordner, "__MACOSX")))
        self.assertFalse(os.path.exists(os.path.join(ordner, "Plan", ".DS_Store")))
        with open(os.path.join(self.ws, a["text_pfad"]), encoding="utf-8") as f:
            ueb = f.read()
        self.assertIn("Gründungszuschuss beantragen", ueb)  # Word-Text über dok_text
        self.assertIn("| Plan/foto.jpg |", ueb)
        self.assertTrue(os.path.isfile(os.path.join(ordner + ".texte", "Plan", "Antrag.docx.txt")))
        z = s.anhang_zeile(a)
        self.assertIn("4 Dateien entpackt", z)
        self.assertIn("drive_ordner_paket", z)

    def test_zip_slip_und_verknuepfung(self):
        link = zipfile.ZipInfo("Plan/link")
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        data = zip_bytes({"../../boese.txt": "x", "/abs.txt": "x", "C:/win.txt": "x", "Plan/ok.txt": "ok", "Plan/../../../raus.txt": "x"},
                         extra=[(link, "/etc/passwd")])
        ziel = os.path.join(self.ws, "anhaenge", "t")
        erg = wissen.zip_entpacken(data, ziel)
        self.assertEqual([d["pfad"] for d in erg["dateien"]], ["Plan/ok.txt"])
        self.assertEqual(len(erg["uebersprungen"]), 5)
        for n in ("boese.txt", "raus.txt", "abs.txt"):
            for wurzel, _, namen in os.walk(os.path.dirname(os.path.dirname(self.ws))):
                self.assertNotIn(n, namen)
        self.assertFalse(os.path.lexists(os.path.join(ziel, "Plan", "link")))

    def test_bombe_wird_abgelehnt_und_geloescht(self):
        data = zip_bytes({"nullen.bin": b"\0" * 5_000_000})
        with self.assertRaises(ValueError) as e:
            s.anhang_speichern(self.sid, "bombe.zip", b64(data))
        self.assertIn("Zip-Bombe", str(e.exception))
        self.assertEqual(os.listdir(os.path.join(self.ws, "anhaenge")), [])

    def test_grenzen(self):
        data = zip_bytes({f"d{i}.txt": "x" for i in range(5)})
        with mock.patch.object(wissen, "ZIP_MAX_DATEIEN", 4):
            with self.assertRaises(ValueError):
                wissen.zip_entpacken(data, os.path.join(self.ws, "a1"))
        data = zip_bytes({"a.txt": "x" * 600, "b.txt": "y" * 600})
        with mock.patch.object(wissen, "ZIP_MAX_GESAMT", 1000):
            with self.assertRaises(ValueError):
                wissen.zip_entpacken(data, os.path.join(self.ws, "a2"))
        with self.assertRaises(ValueError):
            wissen.zip_entpacken(b"kein zip", os.path.join(self.ws, "a3"))

    def test_aufraeumen_loescht_alte_ordner(self):
        a = s.anhang_speichern(self.sid, "u.zip", b64(zip_bytes({"a.txt": "x"})))
        d = os.path.join(self.ws, "anhaenge")
        for n in os.listdir(d):
            os.utime(os.path.join(d, n), (0, 0))
        s.anhaenge_aufraeumen()
        self.assertEqual(os.listdir(d), [])
        self.assertTrue(a["ordner"])

    def test_zip_aus_arbeitsordner(self):
        with open(os.path.join(rechner.arbeit_dir(), "gross.zip"), "wb") as f:
            f.write(zip_bytes({"x/a.md": "# Hallo"}))
        t = wissen.zip_aus_datei(self.b, "/arbeit/gross.zip")
        self.assertIn("1 Dateien entpackt", t)


class Hochladen(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()
        self.b = dict(s.bot("reacher"), zugriffe=True, rechte={"drive": ["a@example.org"]})
        self.ws = s.workspace(self.b)
        self.ordner = os.path.join(self.ws, "anhaenge", "20261029-plan")
        os.makedirs(os.path.join(self.ordner, "Finanzen"))
        os.makedirs(os.path.join(self.ordner + ".texte"))
        for rel, d in (("Plan.pdf", b"%PDF-1.4"), ("Finanzen/zahlen.xlsx", b"PK\x03\x04"), (".versteckt", b"x")):
            with open(os.path.join(self.ordner, rel), "wb") as f:
                f.write(d)
        with open(os.path.join(self.ordner + ".texte", "Plan.pdf.txt"), "w") as f:
            f.write("t")
        with open(os.path.join(rechner.arbeit_dir(), "bericht.pdf"), "wb") as f:
            f.write(b"%PDF bericht")

    def test_pfade(self):
        self.assertEqual(home.upload_pfad(self.b, "/arbeit/bericht.pdf"), os.path.realpath(os.path.join(rechner.arbeit_dir(), "bericht.pdf")))
        self.assertEqual(home.upload_pfad(self.b, "/anhaenge/20261029-plan/Plan.pdf"), os.path.realpath(os.path.join(self.ordner, "Plan.pdf")))
        self.assertEqual(home.upload_pfad(self.b, "anhaenge/20261029-plan/Plan.pdf"), os.path.realpath(os.path.join(self.ordner, "Plan.pdf")))
        for falsch in ("/etc/passwd", "../../etc/passwd", "/arbeit/../../etc/passwd", "anhaenge/20261029-plan/.versteckt", "", "/arbeit/fehlt.pdf"):
            with self.subTest(pfad=falsch):
                with self.assertRaises(ValueError):
                    home.upload_pfad(self.b, falsch)
        os.symlink("/etc/passwd", os.path.join(rechner.arbeit_dir(), "link"))
        with self.assertRaises(ValueError):
            home.upload_pfad(self.b, "/arbeit/link")

    def test_paket_mit_ordner_pfad(self):
        args = home.uploads_aufloesen(self.b, "drive_ordner_paket", {"name": "Gewerbeplan", "dateien": [
            {"name": "Übersicht", "art": "doc", "text": "# Hallo"},
            {"art": "datei", "pfad": "anhaenge/20261029-plan", "unterordner": "Originale"},
            {"art": "datei", "pfad": "/arbeit/bericht.pdf", "name": "Bericht 2026.pdf"}]})
        l = args["dateien"]
        self.assertEqual(l[0], {"name": "Übersicht", "art": "doc", "text": "# Hallo"})
        rest = {(x["unterordner"] if "unterordner" in x else "", x["name"]): base64.b64decode(x["base64"]) for x in l[1:]}
        self.assertEqual(rest, {("Originale/Finanzen", "zahlen.xlsx"): b"PK\x03\x04", ("Originale", "Plan.pdf"): b"%PDF-1.4",
                                ("", "Bericht 2026.pdf"): b"%PDF bericht"})  # ohne .versteckt und ohne .texte/
        self.assertTrue(all("pfad" not in x for x in l))

    def test_paket_zu_gross(self):
        with mock.patch.object(home, "UPLOAD_MAX", 10):
            with self.assertRaises(ValueError) as e:
                home.uploads_aufloesen(self.b, "drive_ordner_paket", {"name": "X", "dateien": [{"art": "datei", "pfad": "anhaenge/20261029-plan"}]})
        self.assertIn("mehrere", str(e.exception))

    def test_weitergabe_an_home_mcp(self):
        aufrufe = []
        with mock.patch.object(home, "_home_roh", lambda tool, args: (aufrufe.append((tool, dict(args))) or ("Wartet auf Freigabe F-abc123", False))), \
                mock.patch.object(home, "katalog", lambda neu=False: {"konten": {"drive": ["a@example.org"]}}):
            t, err = home.home_aufruf(self.b, "drive_hochladen", {"pfad": "/arbeit/bericht.pdf"})
            self.assertFalse(err, t)
            tool, args = aufrufe[-1]
            self.assertEqual((tool, args["name"], base64.b64decode(args["base64"])), ("drive_hochladen", "bericht.pdf", b"%PDF bericht"))
            self.assertNotIn("pfad", args)
            t, err = home.home_aufruf(self.b, "drive_hochladen", {"pfad": "/etc/passwd"})
            self.assertTrue(err)
            self.assertEqual(len(aufrufe), 1)  # nichts weitergegeben
            ohne = dict(self.b, rechte={"kalender": ["a@example.org"]})
            t, err = home.home_aufruf(ohne, "drive_ordner_paket", {"name": "X", "dateien": [{"art": "datei", "pfad": "/arbeit/bericht.pdf"}]})
            self.assertTrue(err)
            self.assertIn("nicht freigegeben", t)

    def test_werkzeugliste_und_regeln(self):
        r = s.rechte_norm({"drive": ["a@example.org"]})
        for t in ("drive_ordner_anlegen", "drive_hochladen", "drive_ordner_paket"):
            self.assertTrue(s.home_erlaubt(r, t))
            self.assertFalse(s.home_erlaubt(s.rechte_norm({}), t))
            self.assertIn(t, rechner.REGEL_WERKZEUGE)
        liste = {"tools": [{"name": "drive_hochladen", "description": "d", "inputSchema": {"type": "object", "properties": {"name": {}}, "required": ["name"]}},
                           {"name": "drive_ordner_paket", "description": "d", "inputSchema": {"type": "object", "properties": {
                               "dateien": {"type": "array", "items": {"type": "object", "properties": {}, "required": ["name", "art"]}}}}},
                           {"name": "mails_suchen", "description": "d", "inputSchema": {}}]}
        with mock.patch.object(home, "mcp_call", lambda *a, **k: json.dumps(liste)):
            res = home.homemcp_rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, self.b)["result"]["tools"]
        namen = {t["name"]: t for t in res}
        self.assertNotIn("mails_suchen", namen)
        self.assertIn("pfad", namen["drive_hochladen"]["inputSchema"]["properties"])
        self.assertEqual(namen["drive_hochladen"]["inputSchema"]["required"], [])
        self.assertIn("pfad", namen["drive_ordner_paket"]["inputSchema"]["properties"]["dateien"]["items"]["properties"])
        self.assertEqual(liste["tools"][0]["inputSchema"]["required"], ["name"])  # Original unverändert


if __name__ == "__main__":
    unittest.main()
