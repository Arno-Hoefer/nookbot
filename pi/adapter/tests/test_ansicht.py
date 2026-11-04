import unittest

from hilfe import s, zuruecksetzen
from nookbot import sitzungen, ereignisse


class Ansicht(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()
        self.sid = s.bot_session("reacher")
        for i in range(100):
            s.SESS.upsert(self.sid, {"id": f"m{i}", "role": "thought" if i % 2 else "bot", "text": "x" * 5000, "ts": i})

    def test_letzte_und_gekuerzt(self):
        a = sitzungen.SESS.ansicht(s.SESS.get(self.sid), 80)
        self.assertEqual((len(a["msgs"]), a["aelter"], a["msgs"][-1]["id"]), (80, 20, "m99"))
        g = [m for m in a["msgs"] if m["role"] == "thought"]
        self.assertTrue(all(m["gekuerzt"] and len(m["text"]) < 1600 for m in g))
        self.assertTrue(all(len(m["text"]) == 5000 for m in a["msgs"] if m["role"] == "bot"))
        self.assertEqual(len(s.SESS.get(self.sid)["msgs"][-1]["text"]), 5000)  # auf dem Pi bleibt alles
        self.assertEqual(sitzungen.SESS.ansicht(s.SESS.get(self.sid))["aelter"], 0)

    def test_ereignis_gekuerzt(self):
        _, e = ereignisse.EV.buf[-1]
        self.assertEqual((e["m"]["id"], e["m"].get("gekuerzt")), ("m99", True))


if __name__ == "__main__":
    unittest.main()
