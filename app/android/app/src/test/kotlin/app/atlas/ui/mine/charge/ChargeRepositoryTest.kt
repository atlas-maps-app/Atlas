package app.atlas.ui.mine.charge

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.providers.ChargerIndex
import app.atlas.providers.NdwChargers
import app.atlas.testing.testServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Charge screen offline (the user, 30 October: "What exactly is happening across the app when youre online vs
 * offline?"): the national registers kept on the phone answer as they do online, with the downloaded regions' chargers,
 * where until then it showed the regions' alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargeRepositoryTest {
    /** A charger of the Dutch register by the Westerkerk, as its index keeps it. */
    private val register = Place(
        null, LatLon(52.37455, 4.88402), mapOf("amenity" to "charging_station", "operator" to "Vattenfall", "capacity" to "2"),
        source = NdwChargers.ID, extra = mapOf("charger:id" to "ndw:NL-TST-1"),
    )

    @Test
    fun offlineTheRegisterKeptOnThePhoneAnswers() = runBlocking {
        val services = testServices()
        // the Dutch register as its download leaves it in the kept folder
        File(services.dirs.providers, "${NdwChargers.ID}-chargers.tsv.gz").apply {
            parentFile!!.mkdirs()
            outputStream().use { ChargerIndex(listOf(register), NdwChargers.INFO).write(it) }
        }
        val got = ChargeRepository(services).load(Services.WESTERKERK, 1_000.0)
        assertFalse(got.online)
        assertEquals(1, got.places.count { it.extra["charger:id"] == "ndw:NL-TST-1" || it.extra["charger:ids"]?.contains("ndw:NL-TST-1") == true })
        assertTrue(got.sources.toString(), NdwChargers.ID in got.sources)
        services.close()
    }

    @Test
    fun offlineWithoutARegisterOnThePhoneTheRegionsAloneAnswer() = runBlocking {
        val services = testServices()
        val got = ChargeRepository(services).load(Services.WESTERKERK, 1_000.0)
        assertFalse(got.online)
        assertTrue(got.places.none { it.source == NdwChargers.ID })
        assertFalse(NdwChargers.ID in got.sources)
        services.close()
    }
}
