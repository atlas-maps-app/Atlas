package app.atlas.search

/**
 * Tags as the store writes them: one blob of varints per place. Each tag is its key's dictionary id, then its value:
 * an even number is a dictionary id times two, an odd one a UTF-8 length times two plus one, followed by the bytes.
 * A café's ten tags take about forty bytes where their JSON took three hundred.
 */
internal object TagBlob {
    fun encode(tags: Map<String, String>, dict: Dictionaries, sink: ByteSink = ByteSink()): ByteArray {
        sink.reset()
        for ((k, v) in tags) {
            sink.varint(dict.internKey(k))
            if (StoredTags.inline(k, v)) {
                val bytes = v.toByteArray(Charsets.UTF_8)
                sink.varint((bytes.size.toLong() shl 1) or 1L)
                sink.bytes(bytes)
            } else {
                sink.varint(dict.internValue(v).toLong() shl 1)
            }
        }
        return sink.toByteArray()
    }

    /** Like [encode], but every value inline: for what providers add (ratings, ids), which rarely repeats. */
    fun encodeInline(tags: Map<String, String>, dict: Dictionaries, sink: ByteSink = ByteSink()): ByteArray {
        sink.reset()
        for ((k, v) in tags) {
            sink.varint(dict.internKey(k))
            val bytes = v.toByteArray(Charsets.UTF_8)
            sink.varint((bytes.size.toLong() shl 1) or 1L)
            sink.bytes(bytes)
        }
        return sink.toByteArray()
    }

    fun decode(blob: ByteArray, dict: Dictionaries): Map<String, String> {
        if (blob.isEmpty()) return emptyMap()
        val out = HashMap<String, String>(16)
        val src = ByteSource(blob)
        while (src.hasMore) {
            val k = dict.key(src.int())
            val code = src.varint()
            out[k] = if (code and 1L == 1L) src.string((code ushr 1).toInt()) else dict.value((code ushr 1).toInt())
        }
        return out
    }

    /** True when the blob holds a tag of key id [key], read without making strings. */
    fun hasKey(blob: ByteArray, key: Int): Boolean {
        val src = ByteSource(blob)
        while (src.hasMore) {
            val k = src.int()
            val code = src.varint()
            if (k == key) return true
            if (code and 1L == 1L) src.skip((code ushr 1).toInt())
        }
        return false
    }

    /** Calls [block] with each tag's key id and its value code, without making strings: for counting keys and values. */
    inline fun forEachCode(blob: ByteArray, block: (key: Int, valueId: Int) -> Unit) {
        val src = ByteSource(blob)
        while (src.hasMore) {
            val k = src.int()
            val code = src.varint()
            if (code and 1L == 1L) {
                src.skip((code ushr 1).toInt())
                block(k, -1)
            } else {
                block(k, (code ushr 1).toInt())
            }
        }
    }
}
