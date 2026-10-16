package app.atlas.packer

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Format v1 stays byte for byte what it was: the renders' packs rebuilt from data/regions and data/ams equal the ones
 * published to packs-renders (kept in cache/packs/regions), and the bundled Westerkerk pack equals the app's asset.
 */
class PackV1StabilityTest {
    @get:Rule val tmp = TemporaryFolder()

    private val repo = File(System.getProperty("atlas.repo") ?: error("atlas.repo is not set"))
    private val published = File(repo, "cache/packs/regions")
    private val asset = File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas")

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val b = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(b)
                if (n < 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun theRendersPacksAreRebuiltByteForByte() {
        assumeTrue(published.isDirectory && File(repo, "data/regions/places.py").isFile)
        val names = published.listFiles { f -> f.name.endsWith(".atlas") }.orEmpty().map { it.name.removeSuffix(".atlas") }.sorted()
        assumeTrue(names.isNotEmpty())
        val out = tmp.newFolder("v1")
        var checked = 0
        for (name in names) {
            val inputs = if (name == "amsterdam") File(repo, "data/ams/raw.json") else File(repo, "data/regions/$name/raw.json")
            if (!inputs.isFile) continue
            val built = Renders.build(repo, out, setOf(name)).single()
            val want = sha(File(published, "$name.atlas"))
            val got = sha(built.file)
            println("V1PACK $name ${built.file.length()} bytes ${got.take(16)} ${if (got == want) "same" else "DIFFERENT from $want"}")
            assertEquals("$name.atlas", want, got)
            if (name == "amsterdam" && asset.isFile) assertEquals("the bundled amsterdam.atlas", sha(asset), got)
            built.file.delete()
            checked++
        }
        assumeTrue(checked > 0)
        println("V1PACK $checked packs identical")
    }
}
