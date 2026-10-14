package app.atlas.guide

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * osrm-text-instructions' own fixtures (test/fixtures/v5, trimmed to the vendored languages): every step and phrase
 * must compile to exactly the string the JavaScript library produces.
 */
class OsrmFixturesTest {

    private val fixtures: JsonObject by lazy {
        val text = javaClass.getResourceAsStream("/osrm/fixtures-v5.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun stepOf(o: JsonObject): OsrmStep {
        val m = o["maneuver"]!!.jsonObject
        val lanes = (o["intersections"]?.jsonArray?.firstOrNull() as? JsonObject)?.get("lanes")?.jsonArray
            ?.map { it.jsonObject["valid"]!!.jsonPrimitive.content == "true" }
        return OsrmStep(
            type = m.s("type")!!,
            modifier = m.s("modifier"),
            name = o.s("name"),
            ref = o.s("ref"),
            destinations = o.s("destinations"),
            exits = o.s("exits"),
            junctionName = o.s("junction_name"),
            rotaryName = o.s("rotary_name"),
            exit = (m["exit"] as? JsonPrimitive)?.intOrNull,
            bearingAfter = (m["bearing_after"] as? JsonPrimitive)?.content?.toDouble(),
            mode = o.s("mode"),
            drivingSide = o.s("driving_side"),
            lanes = lanes,
        )
    }

    private fun optionsOf(o: JsonObject?): OsrmOptions = if (o == null) OsrmOptions() else OsrmOptions(
        legIndex = (o["legIndex"] as? JsonPrimitive)?.intOrNull,
        legCount = (o["legCount"] as? JsonPrimitive)?.intOrNull,
        classes = o["classes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        waypointName = o.s("waypointName"),
    )

    private fun check(languages: Collection<String>) {
        var checked = 0
        val failures = ArrayList<String>()
        for ((name, value) in fixtures) {
            val fx = value.jsonObject
            val phrases = fx["phrases"]?.jsonObject
            if (phrases != null) {
                val phraseKey = name.substringAfter('/').replace('_', ' ')
                val opts = fx["options"]?.jsonObject
                val tokens = opts?.mapValues { (it.value as? JsonPrimitive)?.content } ?: emptyMap()
                for ((lang, expected) in phrases) {
                    if (lang !in languages) continue
                    val osrm = OsrmInstructions.forLanguage(lang)
                    val got = osrm.tokenize(osrm.phrase(phraseKey)!!, tokens)
                    if (got != expected.jsonPrimitive.content) failures += "$name/$lang: expected «${expected.jsonPrimitive.content}» got «$got»"
                    checked++
                }
                continue
            }
            val step = fx["step"]?.jsonObject ?: continue // the custom phrase-only fixtures carry no step, as in the JS test
            val osrmStep = stepOf(step)
            val options = optionsOf(fx["options"]?.jsonObject)
            for ((lang, expected) in fx["instructions"]!!.jsonObject) {
                if (lang !in languages) continue
                val got = OsrmInstructions.forLanguage(lang).compile(osrmStep, options)
                if (got != expected.jsonPrimitive.content) failures += "$name/$lang: expected «${expected.jsonPrimitive.content}» got «$got»"
                checked++
            }
        }
        assertTrue("fixtures checked: $checked", checked > 500 * languages.size / 2)
        assertEquals(failures.take(20).joinToString("\n"), 0, failures.size)
    }

    @Test fun englishMatchesTheLibrary() = check(listOf("en"))

    @Test fun dutchMatchesTheLibrary() = check(listOf("nl"))

    @Test fun germanMatchesTheLibrary() = check(listOf("de"))

    @Test fun frenchWithItsGrammarMatchesTheLibrary() = check(listOf("fr"))

    @Test fun everyVendoredLanguageMatchesTheLibrary() = check(OsrmInstructions.CODES)

    @Test fun resolvesLanguageTags() {
        assertEquals("no", OsrmInstructions.resolve("nb-NO"))
        assertEquals("pt-BR", OsrmInstructions.resolve("pt"))
        assertEquals("zh-Hans", OsrmInstructions.resolve("zh-CN"))
        assertEquals("en", OsrmInstructions.resolve("en-GB"))
        assertEquals(null, OsrmInstructions.resolve("cs"))
        assertEquals("en", OsrmInstructions.forLanguage("cs").code)
    }

    @Test fun russianGrammarDeclinesStreetNames() {
        val ru = OsrmInstructions.forLanguage("ru")
        assertEquals("Тверской улице", ru.grammarize("Тверская улица", "dative"))
    }
}
