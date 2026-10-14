package app.atlas.ui.search

import app.atlas.Services
import app.atlas.providers.Commons
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** An image kept on the phone with the credit its licence asks for (null when none is needed). */
class KeptImage(val bytes: ByteArray, val credit: String?, val licence: String?)

/**
 * Wikimedia Commons images (a place's photo, P18), fetched once at a phone-sized width and kept with their licence, so
 * the place shows its picture offline too. Only free licences are kept and shown.
 */
class KeptImages(private val dir: File) {
    /** The kept image of Commons file [name], or null. */
    fun read(name: String): KeptImage? {
        val f = fileOf(name)
        if (!f.isFile) return null
        val meta = File(f.path + ".txt").takeIf { it.isFile }?.readLines().orEmpty()
        return KeptImage(f.readBytes(), meta.getOrNull(0)?.ifEmpty { null }, meta.getOrNull(1)?.ifEmpty { null })
    }

    /** The image of [name] from the phone, else from Commons when online (then kept); null when neither has it. */
    suspend fun get(services: Services, name: String, width: Int = WIDTH): KeptImage? {
        read(name)?.let { return it }
        if (services.config.offline || !services.connectivity.online.value) return null
        return try {
            val files = services.commons.files(listOf(name), width)
            val info = files[Commons.normaliseName(name)] ?: files.values.firstOrNull()
            if (info == null || !info.free) return null
            val url = info.thumbUrl ?: Commons.fileUrl(name, width)
            val res = services.http.execute(services.http.request(url).header("Accept", "image/*").build()).requireOk()
            val kept = KeptImage(res.body, info.credit, info.licence)
            keep(name, kept)
            kept
        } catch (e: IOException) {
            null
        }
    }

    private fun keep(name: String, image: KeptImage) {
        dir.mkdirs()
        val f = fileOf(name)
        f.writeBytes(image.bytes)
        File(f.path + ".txt").writeText((image.credit ?: "") + "\n" + (image.licence ?: "") + "\n")
    }

    private fun fileOf(name: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(Commons.normaliseName(name).toByteArray())
        return File(dir, digest.take(12).joinToString("") { "%02x".format(it) } + ".img")
    }

    companion object {
        const val WIDTH = 720
    }
}
