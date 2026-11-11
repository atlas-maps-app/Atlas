package app.atlas.ui.search

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.atlas.search.Intents
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

/**
 * The time to eat is called after its meal, in the Eat screen's own words (the owner, 10 November, of the Eat card beside
 * the shelf of that name: "And yes?"): Lunch at lunch, Dinner at dinner, its own name between them and late; every other
 * intent keeps its name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntentNameTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theTimeToEatIsCalledAfterItsMeal() {
        val eat = checkNotNull(Intents.byId(Intents.EAT))
        val coffee = checkNotNull(Intents.byId("coffee"))
        val times = mapOf("noon" to LocalTime.of(12, 30), "tea" to LocalTime.of(15, 30), "evening" to LocalTime.of(19, 30), "late" to LocalTime.of(22, 30))
        val names = mutableMapOf<String, String>()
        compose.setContent {
            for ((label, at) in times) names[label] = intentName(eat, "en", at)
            names["coffee"] = intentName(coffee, "en", LocalTime.of(12, 30))
        }
        compose.waitForIdle()
        assertEquals("Lunch", names["noon"])
        assertEquals("Eat", names["tea"])
        assertEquals("Dinner", names["evening"])
        assertEquals("Eat", names["late"])
        assertEquals("Coffee", names["coffee"])
    }
}
