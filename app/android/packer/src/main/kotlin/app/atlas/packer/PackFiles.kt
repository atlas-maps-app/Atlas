package app.atlas.packer

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** What a finished store holds, read back from the file itself: places, trees and address points. */
data class StoreSummary(val places: Long, val trees: Long, val schema: String?, val meta: Map<String, String>, val addresses: Long = 0)

/** File chores around a pack: sealing a store for shipping, gzip, checksums. */
object PackFiles {
    private const val BUFFER = 1 shl 20

    /**
     * Adds [meta] to a store's metadata table and makes it one self-contained file (no write-ahead log). With [vacuum]
     * it is then rewritten without slack (`VACUUM INTO` a copy that replaces it), which also makes it compress better;
     * that needs room for the copy, so it is skipped, with [log] told, when the disk is short. Returns what it holds.
     */
    fun sealStore(db: File, meta: Map<String, String>, vacuum: Boolean = true, log: (String) -> Unit = {}): StoreSummary {
        val driver = BundledSQLiteDriver()
        driver.open(db.absolutePath).let { conn ->
            try {
                conn.execSQL("BEGIN")
                conn.prepare("INSERT OR REPLACE INTO meta(key, value) VALUES (?1, ?2)").use { st ->
                    for ((k, v) in meta) {
                        st.bindText(1, k)
                        st.bindText(2, v)
                        st.step()
                        st.reset()
                    }
                }
                conn.execSQL("COMMIT")
                conn.execSQL("PRAGMA journal_mode=DELETE")
            } finally {
                // a clean close leaves no -wal or -shm file behind
                conn.close()
            }
        }
        if (vacuum) {
            val copy = File(db.path + ".vacuum")
            val free = db.absoluteFile.parentFile.usableSpace
            if (free < db.length() + db.length() / 20 + (64L shl 20)) {
                log("not vacuuming: ${free shr 20} MiB free for a ${db.length() shr 20} MiB store")
            } else {
                deleteDatabase(copy)
                driver.open(db.absolutePath).let { conn ->
                    try {
                        conn.prepare("VACUUM INTO ?1").use { st -> st.bindText(1, copy.absolutePath); st.step() }
                    } finally {
                        conn.close()
                    }
                }
                deleteDatabase(db)
                if (!copy.renameTo(db)) throw java.io.IOException("cannot move $copy to $db")
            }
        }
        driver.open(db.absolutePath).let { conn ->
            try {
                fun count(sql: String): Long = conn.prepare(sql).use { st -> if (st.step()) st.getLong(0) else 0L }
                val all = LinkedHashMap<String, String>()
                conn.prepare("SELECT key, value FROM meta ORDER BY key").use { st -> while (st.step()) all[st.getText(0)] = st.getText(1) }
                return StoreSummary(
                    count("SELECT count(*) FROM place"), count("SELECT count(*) FROM tree"), all["schema"], all,
                    count("SELECT count(*) FROM address"),
                )
            } finally {
                conn.close()
            }
        }
    }

    /**
     * Cuts [file] into parts of at most [partBytes] (`<name>.000`, `.001`…) for hosts with a file size limit (GitHub
     * release assets must stay under 2 GiB), then removes it. Returns the parts in order.
     */
    fun split(file: File, partBytes: Long): List<File> {
        require(partBytes > 0) { "parts need a positive size" }
        val total = file.length()
        val parts = ArrayList<File>()
        val buf = ByteArray(BUFFER)
        file.inputStream().use { input ->
            var left = total
            while (left > 0) {
                val part = File(file.path + String.format(Locale.ROOT, ".%03d", parts.size))
                var want = minOf(partBytes, left)
                part.outputStream().use { out ->
                    while (want > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), want).toInt())
                        if (n < 0) throw java.io.EOFException("${file.name} shrank while it was split")
                        out.write(buf, 0, n)
                        want -= n
                        left -= n
                    }
                }
                parts += part
            }
        }
        file.delete()
        return parts
    }

    /** Gzips [src] into [dst] at [level] (9 is smallest: packs are made once and downloaded many times). */
    fun gzip(src: File, dst: File, level: Int = Deflater.BEST_COMPRESSION) {
        dst.absoluteFile.parentFile?.mkdirs()
        val tmp = File(dst.path + ".part")
        src.inputStream().buffered(BUFFER).use { input ->
            object : GZIPOutputStream(tmp.outputStream().buffered(BUFFER), BUFFER) {
                init { def.setLevel(level) }
            }.use { out -> input.copyTo(out, BUFFER) }
        }
        if (!tmp.renameTo(dst)) {
            dst.delete()
            if (!tmp.renameTo(dst)) throw java.io.IOException("cannot move $tmp to $dst")
        }
    }

    /** Ungzips [src] into [dst]. */
    fun gunzip(src: File, dst: File) {
        GZIPInputStream(src.inputStream().buffered(BUFFER), BUFFER).use { input ->
            dst.outputStream().buffered(BUFFER).use { out -> input.copyTo(out, BUFFER) }
        }
    }

    /** The SHA-256 of [file], lower-case hex. */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Removes a SQLite file and its journal companions. */
    fun deleteDatabase(db: File) {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(db.path + suffix).delete()
    }
}
