package app.atlas.render.speed

import app.atlas.render.Amsterdam
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.jar.JarFile

/**
 * No method of the engine is too big to be compiled. ART never compiles a method over 10 000 dex code units (its huge-method
 * threshold, skipped by every compiler filter but `everything`, the JIT's included), so such a method runs in the interpreter on
 * the phone for every frame; HotSpot leaves methods over 8000 bytes of bytecode to its interpreter too. Amsterdam.renderImpl
 * (12 208 code units in the debug APK) and Regions.load (11 188) were over ART's limit. Every method here is held to 8000 bytes of
 * JVM bytecode (about 7400 dex code units), except the few already over it outside the live map's path, which must not grow.
 */
class MethodSizeTest {
    /** One method's bytecode length, by class file and name. */
    private class Sized(val owner: String, val name: String, val bytes: Int)

    /** The methods over 8000 bytes before this test, none on the live path: their sizes then, as ceilings. */
    private val ceilings = mapOf(
        "app/atlas/render/Living.render" to 10793, // the living map's own renderer (LivingTest), 9834 dex code units: at ART's edge
        "app/atlas/render/City.render" to 10282,
        "app/atlas/render/Regions.drawItems" to 8897, // under ART's limit (8252 code units); HotSpot interprets it
        "app/atlas/render/Real.renderImpl" to 8597, // the Williamsburg renders only
    )

    @Test
    fun `no engine method is too big to compile`() {
        val sized = classFiles().flatMap { (path, open) -> open().use { methodSizes(path.removeSuffix(".class"), it) } }
        assertTrue("found the engine's classes", sized.size > 2000 && sized.any { it.owner == "app/atlas/render/Amsterdam" })
        val over = sized.filter { it.bytes > (ceilings["${it.owner}.${it.name}"] ?: LIMIT) }
        assertTrue(over.joinToString("\n", "methods too big for ART or HotSpot to compile; split them:\n") { "${it.owner}.${it.name}: ${it.bytes} bytes" }, over.isEmpty())
        val largest = sized.maxBy { if ("${it.owner}.${it.name}" in ceilings) 0 else it.bytes }
        println("methods: ${sized.size}; the largest held to $LIMIT bytes: ${largest.owner}.${largest.name}, ${largest.bytes} bytes")
    }

    /** The engine's class files, relative path and opener, from its classes directory or jar. */
    private fun classFiles(): List<Pair<String, () -> InputStream>> {
        val root = File(Amsterdam::class.java.protectionDomain.codeSource.location.toURI())
        if (root.isDirectory) {
            return root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") && it.path.contains("app${File.separator}atlas${File.separator}render") }
                .map { f -> f.relativeTo(root).invariantSeparatorsPath to { f.inputStream() as InputStream } }.toList()
        }
        val jar = JarFile(root)
        return jar.entries().toList().filter { it.name.endsWith(".class") && it.name.startsWith("app/atlas/render/") }
            .map { e -> e.name to { jar.getInputStream(e) } }
    }

    /** Each method's `Code` length in a class file (JVMS 4): the constant pool read only for the names. */
    private fun methodSizes(owner: String, input: InputStream): List<Sized> {
        val d = DataInputStream(input.buffered())
        check(d.readInt() == 0xCAFEBABE.toInt()) { "$owner is not a class file" }
        d.readUnsignedShort(); d.readUnsignedShort()
        val n = d.readUnsignedShort()
        val utf = arrayOfNulls<String>(n)
        var i = 1
        while (i < n) {
            when (val tag = d.readUnsignedByte()) {
                1 -> utf[i] = d.readUTF()
                3, 4 -> d.skipExactly(4)
                5, 6 -> { d.skipExactly(8); i++ } // a long or double takes two entries
                7, 8, 16, 19, 20 -> d.skipExactly(2)
                9, 10, 11, 12, 17, 18 -> d.skipExactly(4)
                15 -> d.skipExactly(3)
                else -> error("$owner: constant pool tag $tag")
            }
            i++
        }
        d.skipExactly(6)
        d.skipExactly(2 * d.readUnsignedShort()) // interfaces
        repeat(d.readUnsignedShort()) { // fields
            d.skipExactly(6)
            repeat(d.readUnsignedShort()) { d.skipExactly(2); d.skipExactly(d.readInt()) }
        }
        val out = ArrayList<Sized>()
        repeat(d.readUnsignedShort()) {
            d.skipExactly(2)
            val name = utf[d.readUnsignedShort()]!!
            d.skipExactly(2)
            repeat(d.readUnsignedShort()) {
                val attr = utf[d.readUnsignedShort()]
                val len = d.readInt()
                if (attr == "Code") {
                    d.skipExactly(4)
                    val code = d.readInt()
                    out.add(Sized(owner, name, code))
                    d.skipExactly(len - 8)
                } else d.skipExactly(len)
            }
        }
        return out
    }

    private fun DataInputStream.skipExactly(n: Int) = skipNBytes(n.toLong())

    private companion object {
        /** HotSpot's HugeMethodLimit; about 7400 dex code units, well under ART's 10 000. */
        const val LIMIT = 8000
    }
}
