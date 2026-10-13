package app.atlas.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvModelsTest {
    private val ev = EvModels.bundled()

    @Test
    fun theBundledCatalogueLoads() {
        assertEquals(2341, ev.all.size)
        assertEquals(1321, ev.all.count { it.source == "kw" })
        assertTrue(ev.all.all { it.batteryKwh > 0 && it.consumption > 0 })
        assertTrue(ev.all.count { it.curve.isNotEmpty() && !it.curveIsDefault } >= 800)
        assertTrue(ev.makes().contains("Tesla"))
        val licences = EvModels::class.java.getResourceAsStream(EvModels.LICENCES_RESOURCE)!!.reader().readText()
        assertTrue(licences.contains("Attribution Requirement") && licences.contains("Community Data License Agreement - Permissive - Version 2.0"))
    }

    @Test
    fun searchFindsModelsTheWayPeopleTypeThem() {
        val m3 = ev.search("tesla model 3")
        assertTrue(m3.isNotEmpty())
        assertTrue(m3.take(5).all { it.make == "Tesla" && it.model.startsWith("Model 3") })
        assertEquals("Model 3", m3.first().model)
        assertTrue(ev.search("tesla m3").take(3).all { it.model.startsWith("Model 3") })
        val y2021 = ev.search("model 3 2021")
        assertTrue(y2021.isNotEmpty() && y2021.all { it.year == 2021 })
        assertTrue(ev.search("id.3").any { it.make == "Volkswagen" && it.model.contains("ID.3", true) })
        assertTrue(ev.search("e-tron gt").first().model.contains("e-tron GT", true))
        assertTrue(ev.search("zzzz").isEmpty())
        assertTrue(ev.search("byd 3").first().make == "BYD")
        assertTrue(ev.search("   ").isEmpty())
    }

    @Test
    fun aModelBecomesTheRoutersEvSpec() {
        val lr = ev.all.first { it.make == "Tesla" && it.model == "Model 3" && it.variant == "LR" && it.year == 2020 }
        assertEquals(lr, ev.byId(lr.id))
        assertEquals(setOf("type2", "type2_combo", "tesla_supercharger_ccs"), lr.connectors)
        val spec = lr.toEvSpec(socNow = 0.6)
        assertEquals(74.0, spec.batteryKwh, 0.0)
        assertEquals(16.1 * EvModel.HIGHWAY_FACTOR, spec.consumption, 1e-9)
        assertEquals(195.0, spec.maxDcKw, 0.0)
        assertEquals(11.0, spec.maxAcKw, 0.0)
        assertEquals(0.6, spec.socNow, 0.0)
        assertEquals(listOf(0.0 to 180.0, 0.4 to 195.0, 0.6 to 110.0, 1.0 to 12.0), spec.curve)
        assertFalse(lr.consumptionEstimated)
    }

    @Test
    fun newerModelsComeFromOpenEvDataWithPhysics() {
        val oed = ev.all.filter { it.source == "oed" }
        assertTrue(oed.size > 1000)
        assertTrue(oed.all { it.consumptionEstimated })
        val truck = ev.search("tesla cybertruck 2024").first()
        assertEquals("oed", truck.source)
        assertTrue(truck.massKg!! in 2900.0..3200.0)
        assertEquals("epa", truck.rangeCycle)
        assertTrue(truck.consumption in 18.0..30.0)
        // A model both datasets know stays one entry, with OpenEV Data's physics added to it.
        val a6 = ev.search("audi a6 avant e-tron 2024").first { it.variant.isEmpty() && it.model == "A6 Avant e-tron" }
        assertEquals("kw", a6.source)
        assertEquals(2220.0, a6.massKg!!, 0.0)
        assertEquals(0.22, a6.dragCd!!, 0.0)
        assertFalse(a6.consumptionEstimated)
    }

    @Test
    fun aMakesModelsComeNewestFirst() {
        val tesla = ev.ofMake("tesla")
        assertTrue(tesla.isNotEmpty() && tesla.all { it.make == "Tesla" })
        val years = tesla.map { it.year ?: 0 }
        assertEquals(years.sortedDescending(), years)
        assertTrue(ev.ofMake("No Such Make").isEmpty())
    }

    @Test
    fun curvesParse() {
        assertEquals(listOf(0.0 to 80.75, 0.5 to 85.0, 0.8 to 42.5), EvModels.parseCurve("50:85,0:80.75,80:42.5,bad"))
        assertTrue(EvModels.parseCurve("").isEmpty())
    }
}
