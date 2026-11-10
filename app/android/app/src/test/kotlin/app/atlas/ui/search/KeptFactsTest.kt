package app.atlas.ui.search

import app.atlas.providers.WikidataEntity
import app.atlas.providers.WikidataRef
import app.atlas.providers.WikidataTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Wikidata's facts kept on the phone: a brand's owner and head office with them, and what an older Atlas kept. */
class KeptFactsTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val ah = WikidataEntity(
        id = "Q1653985", labels = mapOf("en" to "Albert Heijn"), descriptions = mapOf("en" to "Dutch supermarket chain founded in 1887"),
        logo = "Albert Heijn Logo.svg", smallLogo = null, colour = null, image = null, inception = WikidataTime(1887, 5, 27, 11),
        architects = emptyList(), namedAfter = emptyList(), styles = emptyList(), website = "https://www.ah.nl/",
        sitelinks = mapOf("enwiki" to "Albert Heijn", "nlwiki" to "Albert Heijn (supermarkt)"),
        parents = listOf(WikidataRef("Q20539261", mapOf("en" to "Ahold Delhaize"))), headquarters = listOf(WikidataRef("Q211260", mapOf("en" to "Zaandam"))),
    )

    @Test
    fun aBrandsOwnerAndHeadOfficeAreKept() {
        val kept = KeptFacts(dir.root)
        kept.keep(ah)
        assertEquals(ah, kept.read("Q1653985", KeptFacts.VERSION))
        assertEquals(ah, kept.read("q1653985"))
    }

    /** A file an Atlas wrote before it kept owners says no version: read as it is, but not as the latest facts. */
    @Test
    fun anItemKeptBeforeOwnersWereReadIsAskedAgain() {
        File(dir.root, "Q1653985.json").writeText(
            """{"id":"Q1653985","labels":{"en":"Albert Heijn"},"descriptions":{},"architects":[],"namedAfter":[],"styles":[],"sitelinks":{}}""",
        )
        val kept = KeptFacts(dir.root)
        assertNull(kept.read("Q1653985", KeptFacts.VERSION))
        val old = kept.read("Q1653985")!!
        assertEquals("Albert Heijn", old.label(listOf("en")))
        assertEquals(emptyList<WikidataRef>(), old.parents)
    }
}
