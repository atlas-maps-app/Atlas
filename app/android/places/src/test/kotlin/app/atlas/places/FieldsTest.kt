package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldsTest {
    @Test
    fun theFieldsBehindTheFacts() {
        for (k in FactKind.entries) if (k.field != null) assertNotNull(k.name, Fields.of(k))
        val wc = Fields.byId("wheelchair")!!
        assertEquals("wheelchair", wc.key)
        assertEquals("radio", wc.type)
        assertEquals(listOf("designated", "yes", "limited", "no"), wc.options)
        assertEquals("Wheelchair Access", wc.label())
        assertEquals("Limited", wc.optionLabel("limited"))
        assertEquals("Rolstoeltoegankelijkheid", wc.label("nl"))
        assertTrue(wc.optionLabel("limited", "de").isNotBlank())
        assertEquals("Odd value", wc.optionLabel("odd_value"))
    }

    @Test
    fun byKey() {
        assertEquals("wheelchair", Fields.forKey("wheelchair")?.id)
        val diet = Fields.forKey("diet:vegan")!!
        assertEquals("diet_multi", diet.id)
        assertTrue(diet.isMultiKey)
        assertTrue("vegan" in diet.options)
        assertEquals("fuel/fuel_multi", Fields.forKey("fuel:diesel")?.id)
        assertEquals("check", Fields.forKey("outdoor_seating")?.type)
        assertNull(Fields.forKey("no_such_key"))
        assertTrue(Fields.ids.size >= 40)
    }
}
