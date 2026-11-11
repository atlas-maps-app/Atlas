package app.atlas.providers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikipediaTest {
    /** Serves the recorded articles: the English and the Dutch Westerkerk, and a page that does not exist. */
    private fun serve(m: Mock) = m.dispatch { req ->
        val title = req.url.queryParameter("titles").orEmpty()
        when {
            req.url.encodedPath.startsWith("/nl/") -> response(Fixtures.text("wikipedia_westerkerk_nl.json"))
            title == "Westerkerk" -> response(Fixtures.text("wikipedia_westerkerk_en.json"))
            else -> response(Fixtures.text("wikipedia_missing.json"))
        }
    }

    @Test
    fun anArticleReadsAsTextWithItsSections() = runBlocking {
        Mock().use { m ->
            serve(m)
            val wp = Wikipedia(testHttp()) { lang -> m.url("/$lang/w/api.php") }
            val a = wp.article("en", "Westerkerk")!!
            assertEquals("Westerkerk", a.title)
            assertEquals("Church in Amsterdam", a.description)
            assertTrue(a.lead.first().startsWith("The Westerkerk"))
            val titles = a.sections.map { it.title }
            assertEquals("History", titles.first())
            assertTrue("Anne Frank" in titles && "Rembrandt" in titles)
            // what a reader of a place does not want is left out
            for (t in listOf("See also", "Bibliography", "References", "External links")) assertTrue(t, t !in titles)
            assertTrue(a.sections.all { it.paragraphs.isNotEmpty() })
            assertEquals("https://en.wikipedia.org/wiki/Westerkerk", a.url)
            assertTrue(a.thumbnail!!.startsWith("https://thumb.wikimedia.org/"))
            assertEquals("2026-10-20T22:02:54Z", a.edited)
            assertEquals("Westerkerk (Amsterdam)", a.languages["nl"])
            assertTrue(a.words > 1000)
            // the same in Dutch, its own sections and links left out
            val nl = wp.article("nl", "Westerkerk (Amsterdam)")!!
            assertEquals("kerkgebouw in Amsterdam", nl.description)
            assertTrue(nl.sections.none { it.title in setOf("Zie ook", "Externe links", "Bibliografie") })
            assertEquals("Architectuur", nl.sections.first().title)
            // a page that does not exist is none
            assertNull(wp.article("en", "No such page Atlas xyz"))
        }
    }

    @Test
    fun aPlacesArticleIsReadInTheReadersLanguage() = runBlocking {
        Mock().use { m ->
            serve(m)
            val wp = Wikipedia(testHttp()) { lang -> m.url("/$lang/w/api.php") }
            // the tag names the Dutch article; an English reader gets the English one through its language links
            val en = wp.of(mapOf("wikipedia" to "nl:Westerkerk (Amsterdam)"), listOf("en", "nl"))!!
            assertEquals("en" to "Westerkerk", en.lang to en.title)
            val nl = wp.of(mapOf("wikipedia" to "nl:Westerkerk (Amsterdam)"), listOf("nl", "en"))!!
            assertEquals("nl", nl.lang)
            assertEquals("nl" to "Westerkerk (Amsterdam)", Wikipedia.ref("nl:Westerkerk_(Amsterdam)"))
            assertNull(Wikipedia.ref("Westerkerk"))
        }
    }
}
