import json
import os
import unittest

from hilfe import s, zuruecksetzen
from nookbot import mcp, rechner, sitzungen


def rufe(caller, name, args=None):
    r = mcp.mcp_rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": name, "arguments": args or {}}}, caller)
    return r["result"]["content"][0]["text"], r["result"].get("isError")


def werkzeuge(caller):
    return {t["name"] for t in mcp.mcp_rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, caller)["result"]["tools"]}


ECHO = {"id": "echo", "name": "Echo"}  # ein Bot ohne Gesundheitsdaten


class Abschottung(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()
        bl = s.read_json(s.path("bots.json"), [])
        for b in bl:
            if b["id"] == "scout":  # Scout wird zum Gesundheits-Bot (wie Vital)
                b.update(zugriffe=True, rechte=dict(s.RECHTE_LEER, gesundheit=True))
            if b["id"] == "reacher":
                b.update(zugriffe=True, rechte=dict(s.RECHTE_LEER, gesundheit=True))
        s.write_json(s.path("bots.json"), bl)
        self.vital, self.kap = s.bot("scout"), s.bot("reacher")
        self.assertTrue(self.kap.get("kapitaen"))
        self.sv, self.sk = s.bot_session("scout"), s.bot_session("reacher")
        s.SESS.start_turn(self.sv, "scout", von_dir=True)
        s.SESS.start_turn(self.sk, "reacher", von_dir=True)

    def test_kapitaen_nach_abfrage_abgeschottet(self):
        self.assertFalse(rechner.abgeschottet(self.kap, self.sk))
        sitzungen.SESS.rt[self.sk]["gesundheit"] = True  # so setzt es das Werkzeug gesundheit
        self.assertTrue(rechner.abgeschottet(self.kap, self.sk))
        s.SESS.start_turn(self.sk, "reacher", von_dir=True)  # nächster Lauf: wieder frei
        self.assertFalse(rechner.abgeschottet(self.kap, self.sk))

    def test_eigener_arbeitsordner(self):
        privat = rechner.arbeit_dir(self.vital, self.sv)
        gemeinsam = rechner.arbeit_dir()
        self.assertNotEqual(privat, gemeinsam)
        self.assertEqual(rechner.arbeit_dir(ECHO), gemeinsam)
        with open(os.path.join(privat, "schlaf.png"), "wb") as f:
            f.write(b"x")
        rechner._arbeit_meta_setzen(["schlaf.png"], "scout", privat)
        with open(os.path.join(gemeinsam, "liste.txt"), "w") as f:
            f.write("x")
        self.assertEqual([x["name"] for x in rechner.arbeit_liste(ECHO)], ["liste.txt"])  # andere Bots sehen es nicht
        self.assertEqual({x["name"] for x in rechner.arbeit_liste(self.vital, self.sv)}, {"schlaf.png", "liste.txt"})
        app = {x["name"]: x for x in rechner.arbeit_liste()}  # die App sieht alles
        self.assertTrue(app["schlaf.png"]["privat"])
        self.assertEqual(rechner.arbeit_pfad("schlaf.png"), os.path.join(privat, "schlaf.png"))
        t, _ = rufe(ECHO, "arbeitsordner")
        self.assertNotIn("schlaf", t)
        rechner.arbeit_loeschen("schlaf.png")
        self.assertFalse(os.path.exists(os.path.join(privat, "schlaf.png")))
        self.assertEqual(json.load(open(os.path.join(privat, ".meta.json"))), {})


if __name__ == "__main__":
    unittest.main()
