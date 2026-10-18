package app.atlas.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/** [BundledRenders]: the APK's `tiles/bundled.json` as `packer render-tiles --bundled` writes it, and what it cannot read. */
class BundledRendersTest {
    /** An index in RenderTiles' INDEX format: the render pack's own SHA-256 beside its source element pack's. */
    private val index = """
        {
            "format": 3,
            "payload": 1,
            "engine": "e1",
            "style": "s1",
            "packs": [
                {
                    "id": "amsterdam",
                    "file": "amsterdam.atlas",
                    "bytes": 412345,
                    "sha256": "AB12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34",
                    "profile": "living",
                    "levels": [14, 12],
                    "chunks": 9,
                    "source": { "file": "amsterdam.atlas", "sha256": "1111111111111111111111111111111111111111111111111111111111111111" }
                },
                {
                    "id": "grachten",
                    "file": "grachten.atlas",
                    "bytes": 1834567,
                    "sha256": "ef56ef56ef56ef56ef56ef56ef56ef56ef56ef56ef56ef56ef56ef56ef56ef56",
                    "profile": "region",
                    "levels": [14],
                    "chunks": 40,
                    "source": { "file": "grachten.atlas", "sha256": "3ec8629e2d3f5708a604ada1592b4bc4a5dd722e69c2aaefa1cbafe5811593ec" }
                }
            ]
        }
    """.trimIndent()

    @Test
    fun eachBundledRenderPackGivesItsRegionAndItsOwnHash() {
        assertEquals(
            mapOf(
                "amsterdam" to "ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34",
                "grachten" to "ef56ef56ef56ef56ef56ef56ef56ef56ef56ef56ef56ef56ef56ef56ef56ef56",
            ),
            BundledRenders.read { index.byteInputStream() },
        )
    }

    @Test
    fun aMissingOrUnreadableIndexIsAnEmptyMap() {
        assertTrue(BundledRenders.read { throw FileNotFoundException(BundledRenders.ASSET) }.isEmpty())
        assertTrue(BundledRenders.read { throw IOException("closed") }.isEmpty())
        assertTrue(BundledRenders.read { "not json".byteInputStream() }.isEmpty())
        assertTrue(BundledRenders.read { "[1, 2]".byteInputStream() }.isEmpty())
        assertTrue(BundledRenders.read { """{"packs": {"id": "x"}}""".byteInputStream() }.isEmpty())
        assertTrue(BundledRenders.read { """{"format": 3}""".byteInputStream() }.isEmpty())
    }

    @Test
    fun aPackWithoutItsIdOrHashIsLeftOut() {
        val text = """{"packs": [{"id": "a"}, {"sha256": "00"}, {"id": 7, "sha256": "01"}, 5, {"id": "b", "sha256": "02"}]}"""
        assertEquals(mapOf("b" to "02"), BundledRenders.parse(text))
    }
}
