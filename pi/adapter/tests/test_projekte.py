import time
import unittest

from hilfe import s, zuruecksetzen
from nookbot import projekte, dienste, rechner, sitzungen


class Projekte(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()

    def test_projekt_plan_status(self):
        p = projekte.anlegen("reacher", "Umzug März", "Alles für den Umzug")
        self.assertEqual(p["art"], "projekt")
        projekte.status_setzen(p["id"], "wartet auf den Vermieter")
        t = projekte.plan_setzen(p["id"], [{"text": "Kartons besorgen"}, {"text": "Strom ummelden", "faellig": "2000-01-01"}, "Halteverbot beantragen"])
        self.assertIn("3 Punkte", t)
        pid = s.SESS.get(p["id"])["plan"][0]["id"]
        projekte.plan_punkt(p["id"], pid, "erledigt")
        projekte.plan_punkt(p["id"], "Halteverbot beantragen", "braucht_dich")
        # Ergänzen behält den Stand
        projekte.plan_setzen(p["id"], ["Kartons besorgen", "Strom ummelden", "Halteverbot beantragen", "Umzugswagen"])
        plan = s.SESS.get(p["id"])["plan"]
        self.assertEqual([x["status"] for x in plan], ["erledigt", "offen", "braucht_dich", "offen"])
        meta = sitzungen.SESS.meta(s.SESS.get(p["id"]))
        self.assertEqual((meta["status"], meta["braucht_dich"]), ("wartet auf den Vermieter", 1))
        self.assertIn("1 braucht dich", meta["plan"])
        lage = projekte.lage()
        self.assertIn("Umzug März", lage)
        self.assertIn("Halteverbot", lage)
        self.assertIn("Strom ummelden", lage)  # überfällig
        with self.assertRaises(ValueError):
            projekte.plan_punkt(p["id"], pid, "vielleicht")

    def test_karten(self):
        sid = s.bot_session("reacher")
        projekte.karte(sid, "Pakete", "2 unterwegs", [{"label": "DHL", "wert": "heute"}])
        projekte.karte(sid, "pakete", "1 unterwegs")  # gleicher Titel → dieselbe Karte
        k = s.SESS.get(sid)["karten"]
        self.assertEqual((len(k), k[0]["inhalt"]), (1, "1 unterwegs"))
        for i in range(3):
            projekte.karte(sid, f"K{i}")
        with self.assertRaises(ValueError):
            projekte.karte(sid, "zu viel")
        projekte.karte_weg(sid, "Pakete")
        self.assertEqual(len(s.SESS.get(sid)["karten"]), 3)
        with self.assertRaises(ValueError):
            projekte.status_setzen(sid, "x")  # Statuszeile nur in Projekten


class RechteAufZeit(unittest.TestCase):
    def test_frei_laeuft_ab(self):
        b = {"id": "reacher", "regeln": {"termin_anlegen": "frei"}, "regeln_bis": {"termin_anlegen": time.time() + 60}}
        self.assertEqual(rechner.regel(b, "termin_anlegen"), "frei")
        b["regeln_bis"]["termin_anlegen"] = time.time() - 1
        self.assertEqual(rechner.regel(b, "termin_anlegen"), "fragen")
        self.assertEqual(rechner.regeln_bis_norm({"termin_anlegen": "frei", "doc_anlegen": "nie"},
                                                 {"termin_anlegen": time.time() + 99, "doc_anlegen": time.time() + 99, "x": "y"}).keys(), {"termin_anlegen"})


class Meldungen(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()
        self.items = lambda: s.read_json(s.path("notify.json"), {"items": []})["items"]

    def test_thema_aus_und_ruhezeit(self):
        dienste.meldungen_setzen({"themen": {"rundblick": False}})
        dienste.push("Reacher", "Rundblick", tags="compass", ntfy=False)
        self.assertEqual(self.items(), [])
        dienste.push("Reacher", "Antwort", ntfy=False)
        self.assertEqual(len(self.items()), 1)
        dienste.meldungen_setzen({"ruhe_an": True, "ruhe_von": "00:00", "ruhe_bis": "23:59"})
        dienste.push("Reacher", "nachts", ntfy=False)
        self.assertEqual(len(self.items()), 1)
        dienste.push("Reacher", "Freigabe wartet", prio="high", ntfy=False)  # kommt immer
        self.assertEqual(len(self.items()), 2)


if __name__ == "__main__":
    unittest.main()
