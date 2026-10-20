package de.hoefer.nookbot

import de.hoefer.nookbot.data.Abfrage
import de.hoefer.nookbot.data.Datenbank
import de.hoefer.nookbot.data.Guthaben
import de.hoefer.nookbot.data.Msg
import de.hoefer.nookbot.data.Routine
import de.hoefer.nookbot.data.freigabeIds
import de.hoefer.nookbot.data.toolLabel
import de.hoefer.nookbot.data.toolLine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test fun toolLines() {
        assertEquals("Kalender gelesen · Im Web gesucht", toolLine("mcp_home_termine,web_search"))
        assertEquals("GitLab gelesen", toolLabel("mcp_home_gitlab_issues"))
        assertEquals("Anderen Bot gefragt", toolLabel("mcp_nookbot_bot_fragen"))
        assertEquals("unbekannt_xy", toolLabel("unbekannt_xy"))
    }

    @Test fun findsFreigaben() {
        assertEquals(listOf("F-7d2de4"), freigabeIds("Wartet auf deine Freigabe (F-7d2de4): Termin …, nochmal F-7d2de4"))
        assertEquals(emptyList<String>(), freigabeIds("F-xyz123 ist keine"))
    }

    @Test fun msgFromAdapter() {
        val m = Msg.from(JSONObject("""{"id":"summary","role":"bot","text":"x","ts":1760000000.5,"bot":"reacher","zusammenfassung":true}"""))
        assertEquals(1760000000500, m.ts)
        assertTrue(m.zusammenfassung)
        assertEquals(null, Msg.from(JSONObject("""{"id":"u1","role":"user","text":"hi","ts":1}""")).bot)
    }

    @Test fun abfrageParameterRoundtrip() {
        val q = Abfrage("P1", "Profit", "von=current_date - 7, :land=DE, ohne", "SELECT 1")
        val back = Abfrage.from(q.json())
        assertEquals("von=current_date - 7, land=DE, ohne", back.parameter)
    }

    @Test fun importEchoVorlage() {
        val v = """{"name":"Kennzahlen","quellen":[
            {"id":"profit","art":"sql","verbindung":"Analytics","sql":"SELECT 1","beschreibung":"P1 · Profit gestern, MTD"},
            {"id":"kal","art":"kalender"},
            {"id":"umsatz","art":"sql","verbindung":"Analytics","sql":"SELECT 2","beschreibung":"R1 · Umsatz"}]}"""
        val dbs = Datenbank.import(v, "heroku")
        assertEquals(1, dbs.size)
        assertEquals("Analytics", dbs[0].titel)
        assertEquals("heroku", dbs[0].name)
        assertEquals(listOf("P1 – Profit gestern", "R1 – Umsatz"), dbs[0].abfragen.map { it.name })
        // eigener Export geht 1:1 zurück
        assertEquals(dbs, Datenbank.import(Datenbank.listJson(dbs).toString()))
    }

    @Test fun guthaben() {
        val g = Guthaben.from(JSONObject("""{"included":{"balance_usd":59.32,"allowance_usd":60,"period":{"until":"2026-11-19T12:58:08Z"}},"purchased":{"balance_usd":0}}"""))!!
        assertEquals(1, g.genutztProzent)
        assertEquals("2026-11-19", g.bis)
    }

    @Test fun routineJson() {
        val r = Routine("r1", "Morgen", "reacher", "Was steht an?", "07:45", listOf(1, 2, 3, 4, 5), true, 0)
        assertEquals(r, Routine.from(r.json()))
    }
}
