import unittest

from hilfe import s, zuruecksetzen


def werkzeuge(caller):
    return {t["name"] for t in s.mcp_rpc({"id": 1, "method": "tools/list"}, caller)["result"]["tools"]}


class Mcp(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()

    def test_sichtbarkeit(self):
        kap = werkzeuge(s.bot("reacher"))
        self.assertTrue({"bot_anlegen", "auftraege_verteilen", "bots_liste", "pakete", "dokument_ansehen", "python_ausfuehren"} <= kap)
        self.assertNotIn("gesundheit", kap)  # nie über „Alles“
        scout = werkzeuge(s.bot("scout"))
        self.assertFalse({"bot_anlegen", "bot_aendern", "auftraege_verteilen", "pakete", "gesundheit"} & scout)
        self.assertIn("bot_fragen", scout)
        mit_ges = werkzeuge(dict(s.bot("scout"), zugriffe=True, rechte={"gesundheit": True}, merken=False))
        self.assertIn("gesundheit", mit_ges)
        self.assertNotIn("merken", mit_ges)

    def test_allein_kein_team(self):
        zuruecksetzen(bots_extra=False)
        self.assertFalse({"bots_liste", "bot_fragen", "auftraege_verteilen"} & werkzeuge(s.bot("reacher")))

    def test_routine_anlegen_und_liste(self):
        r = s.mcp_rpc({"id": 2, "method": "tools/call", "params": {"name": "routine_anlegen", "arguments": {
            "name": "Morgen", "auftrag": "Was steht an?", "zeit": "07:45", "in_diesem_chat": False}}}, s.bot("scout"))["result"]
        self.assertFalse(r["isError"])
        self.assertEqual([x["name"] for x in s.routinen()], ["Morgen"])
        r = s.mcp_rpc({"id": 3, "method": "tools/call", "params": {"name": "routine_anlegen", "arguments": {
            "name": "Kaputt", "auftrag": "x", "zeit": "25:00"}}}, s.bot("scout"))["result"]
        self.assertTrue(r["isError"])
        liste = s.mcp_rpc({"id": 4, "method": "tools/call", "params": {"name": "routinen_liste", "arguments": {}}}, s.bot("scout"))
        self.assertIn("Morgen", liste["result"]["content"][0]["text"])

    def test_bot_anlegen_nur_kapitaen(self):
        r = s.mcp_rpc({"id": 5, "method": "tools/call", "params": {"name": "bot_anlegen", "arguments": {
            "name": "Koch", "rolle": "Rezepte", "fokus": "Essen"}}}, s.bot("scout"))["result"]
        self.assertTrue(r["isError"])

    def test_ausloeser_per_mcp(self):
        r = s.mcp_rpc({"id": 6, "method": "tools/call", "params": {"name": "ausloeser_anlegen", "arguments": {
            "name": "Preis", "quelle": "webseite", "suche": "https://example.com/", "auftrag": "Sag Bescheid.", "in_diesem_chat": False}}},
            s.bot("scout"))["result"]
        self.assertFalse(r["isError"], r)
        self.assertEqual(s.ausloeser()[0]["bot"], "scout")


if __name__ == "__main__":
    unittest.main()
