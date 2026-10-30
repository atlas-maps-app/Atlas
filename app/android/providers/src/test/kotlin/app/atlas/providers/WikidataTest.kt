package app.atlas.providers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikidataTest {
    private fun serve(m: Mock) = m.dispatch { req ->
        val ids = req.url.queryParameter("ids").orEmpty()
        when {
            "Q999999999999" in ids -> response(Fixtures.text("wd_missing.json"))
            req.url.queryParameter("props") == "labels|descriptions" -> response(Fixtures.text("wd_labels.json"))
            else -> response(Fixtures.text("wd_entities.json"))
        }
    }

    @Test
    fun brandAndBuildingFactsWithLinkedLabels() = runBlocking {
        Mock().use { m ->
            serve(m)
            val wd = Wikidata(testHttp(), m.url("/w/api.php"))
            val got = wd.entities(listOf("Q1653985", "q1130722"), listOf("nl", "en"))
            val ah = got.getValue("Q1653985")
            assertEquals("Albert Heijn", ah.label(listOf("nl")))
            assertEquals("Nederlandse supermarktketen", ah.description(listOf("nl")))
            assertEquals("Albert Heijn Logo.svg", ah.logo)
            assertEquals("Albert Heijn Logo.svg", ah.pinLogo)
            assertNull(ah.colour)
            assertEquals("1887-05-27", ah.inception.toString())
            assertEquals("https://www.ah.nl/", ah.website)
            assertEquals("Dutch supermarket tycoon (1927–2011)", ah.namedAfter.single().descriptions["en"])
            val kerk = got.getValue("Q1130722")
            assertEquals("1631", kerk.inception.toString())
            assertEquals("Hendrick de Keyser", kerk.architects.single().label(listOf("en")))
            assertEquals("barokarchitectuur", kerk.styles.single().label(listOf("nl")))
            assertEquals("Amsterdam (NL), Westerkerk -- 2015 -- 7186.jpg", kerk.image)
            assertEquals("Westerkerk", kerk.sitelinks["enwiki"])
            val first = m.take()
            assertEquals("wbgetentities", first.url.queryParameter("action"))
            assertEquals("Q1653985|Q1130722", first.url.queryParameter("ids"))
            assertEquals("Q3081413|Q525861|Q840829", m.take().url.queryParameter("ids"))
            // Asked again, the cache answers.
            wd.entity("Q1653985", listOf("nl", "en"))
            assertEquals(2, m.count)
        }
    }

    @Test
    fun anUnknownIdIsDroppedAndTheRestStillCome() = runBlocking {
        Mock().use { m ->
            serve(m)
            val got = Wikidata(testHttp(), m.url("/w/api.php")).entities(listOf("Q1653985", "Q999999999999", "not-a-qid"), resolveLinks = false)
            assertEquals(setOf("Q1653985", "Q1130722"), got.keys)
            assertEquals("Q1653985|Q999999999999", m.take().url.queryParameter("ids"))
            assertFalse(m.take().url.queryParameter("ids")!!.contains("Q999999999999"))
        }
    }

    @Test
    fun timesKeepTheirPrecision() {
        assertEquals("1887-05-27", WikidataTime.parse("+1887-05-27T00:00:00Z", 11).toString())
        assertEquals("1631", WikidataTime.parse("+1631-00-00T00:00:00Z", 9).toString())
        assertEquals("1956-07", WikidataTime.parse("+1956-07-01T00:00:00Z", 10).toString())
        assertEquals("-0044", WikidataTime.parse("-0044-03-15T00:00:00Z", 9).toString())
        assertNull(WikidataTime.parse("soon", 11))
    }

    @Test
    fun commonsUrlsAndLicences() = runBlocking {
        assertEquals("https://commons.wikimedia.org/wiki/Special:FilePath/Albert_Heijn_Logo.svg?width=256", Commons.fileUrl("Albert Heijn Logo.svg", 256))
        assertEquals("https://commons.wikimedia.org/wiki/Special:FilePath/Amsterdam_(NL),_Westerkerk_--_2015_--_7186.jpg", Commons.fileUrl("File:Amsterdam (NL), Westerkerk -- 2015 -- 7186.jpg"))
        Mock().use { m ->
            m.fixture("commons_info.json")
            val files = Commons(testHttp(), m.base).files(listOf("Albert Heijn Logo.svg", "Amsterdam (NL), Westerkerk -- 2015 -- 7186.jpg"))
            val logo = files.getValue("Albert Heijn Logo.svg")
            assertEquals("pd", logo.licenceCode)
            assertTrue(logo.free)
            assertNull(logo.credit)
            assertEquals("image/svg+xml", logo.mime)
            val photo = files.getValue("Amsterdam (NL), Westerkerk -- 2015 -- 7186.jpg")
            assertTrue(photo.free)
            assertEquals("Dietmar Rabich, CC BY-SA 4.0", photo.credit)
            assertFalse(photo.thumbUrl!!.contains("utm_"))
            assertEquals("File:Albert Heijn Logo.svg|File:Amsterdam (NL), Westerkerk -- 2015 -- 7186.jpg", m.take().url.queryParameter("titles"))
            // every picture comes from a host the privacy list names (and switching Commons off holds back)
            for (f in files.values) for (u in listOfNotNull(f.url, f.thumbUrl)) {
                assertTrue(u, java.net.URI(u).host in Commons.INFO.hosts)
            }
        }
    }
}
