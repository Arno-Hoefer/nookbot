import time
import unittest

from hilfe import s, zuruecksetzen
from nookbot import sitzungen, vorschlaege


class Vorschlaege(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()
        self.gesendet = []
        self.alt_send, self.alt_ask = sitzungen.send, sitzungen.ask_sync
        sitzungen.send = lambda sid, text, as_user=True: self.gesendet.append((sid, text))
        sitzungen.ask_sync = lambda bid, sid, text, timeout=420, as_user=True: self.gesendet.append((sid, text)) or "KEIN_VORSCHLAG"
        self.sid = s.bot_session("reacher")

    def tearDown(self):
        sitzungen.send, sitzungen.ask_sync = self.alt_send, self.alt_ask

    def test_karte_ja_nein(self):
        v = vorschlaege.vorschlag_neu("reacher", "Soll ich die Lieferadresse als Wissensdatei speichern?",
                                      "Wissensdatei adressen.md anlegen und künftig daraus lesen.", sitzung=self.sid)
        karte = next(m for m in s.SESS.get(self.sid)["msgs"] if m.get("art") == "vorschlag")
        self.assertEqual((karte["ref"], karte["status"]), (v["id"], "offen"))
        vorschlaege.entscheiden(v["id"], True)
        self.assertTrue(self.gesendet[-1][1].startswith("Ja, mach das:"))
        self.assertEqual(next(m for m in s.SESS.get(self.sid)["msgs"] if m.get("art") == "vorschlag")["status"], "angenommen")
        with self.assertRaises(ValueError):
            vorschlaege.entscheiden(v["id"], False)  # schon entschieden

    def test_abgelehnt_nicht_wiederholen_und_tageslimit(self):
        v = vorschlaege.vorschlag_neu("reacher", "Soll ich jeden Montag den Umsatzbericht automatisch schicken?",
                                      "Routine montags 8 Uhr mit dem Ablauf Umsatzbericht anlegen.")
        vorschlaege.entscheiden(v["id"], False)
        self.assertEqual(self.gesendet, [])  # Nein schickt nichts an den Bot
        with self.assertRaises(ValueError):
            vorschlaege.vorschlag_neu("reacher", "Soll ich jeden Montag den Umsatzbericht automatisch schicken lassen?",
                                      "Routine montags 8 Uhr mit dem Ablauf Umsatzbericht anlegen.")
        for t, a in (("Soll ich Kundennamen aus Slack als Gedächtnis festhalten?", "merken mit fuer_alle für jeden genannten Kunden"),
                     ("Soll ich bei neuen Rechnungsmails automatisch prüfen lassen?", "ausloeser_anlegen mit Gmail-Suche nach Rechnungen")):
            vorschlaege.vorschlag_neu("reacher", t, a)
        with self.assertRaises(ValueError):
            vorschlaege.vorschlag_neu("reacher", "Noch ein völlig neues Angebot heute", "Etwas völlig Neues tun und zwar sofort")

    def test_signale_und_durchsicht(self):
        block = "Lieferadresse:\nMusterstraße 1\n12345 Musterstadt\nTelefon 0000 000000, bitte immer so eintragen und verwenden."
        tag = 86400
        for i, sid in enumerate([self.sid, s.bot_session("scout")]):
            s.SESS.upsert(sid, {"id": f"u{i}", "role": "user", "text": block, "ts": time.time() - i * tag})
        for i in range(3):
            s.SESS.upsert(self.sid, {"id": f"q{i}", "role": "user", "text": "Wie war der Umsatz gestern?", "ts": time.time() - i * tag})
        s.SESS.save(self.sid)
        s.SESS.save(s.bot_session("scout"))
        arten = sorted(x["art"] for x in vorschlaege.signale())
        self.assertEqual(arten, ["eingefuegt", "frage"])
        self.assertIn("2 neue Muster", vorschlaege.durchsicht())
        self.assertIn("Tägliche Durchsicht", self.gesendet[-1][1])
        self.assertEqual(vorschlaege.durchsicht(), "Nichts Neues.")  # einmal gesehen → kein zweiter Modellaufruf


if __name__ == "__main__":
    unittest.main()
