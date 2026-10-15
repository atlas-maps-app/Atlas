package app.atlas.render

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's regex engine is ICU, not the JVM's: it throws on `(?U)` and on the flags UNICODE_CHARACTER_CLASS and
 * CANON_EQ, which the JVM tests accept. Every Eat screen crashed on the emulator over one `(?U)` (15 October). No code
 * that ships may use them, except [PyRe.compile], which leaves the flag off on Android.
 */
class AndroidRegexTest {
    @Test
    fun shippedCodeUsesNoRegexAndroidRejects() {
        val android = File(Oracle.repo, "app/android")
        val bad = Regex("""\(\?[a-zA-Z]*U[a-zA-Z]*\)|UNICODE_CHARACTER_CLASS|CANON_EQ""")
        val found = ArrayList<String>()
        android.listFiles()!!.filter { File(it, "src/main").isDirectory }.forEach { module ->
            File(module, "src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    val t = line.trim()
                    val comment = t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                    if (!comment && bad.containsMatchIn(line) && "\"Dalvik\"" !in line) found += "${f.relativeTo(android)}:${i + 1}: $t"
                }
            }
        }
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }
}
