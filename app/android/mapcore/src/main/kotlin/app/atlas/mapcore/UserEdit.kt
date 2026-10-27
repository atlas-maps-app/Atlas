package app.atlas.mapcore

/**
 * One of the user's own edits to OpenStreetMap waiting on the phone (the app's EditQueue, pending or sent), which the
 * windows draw until the map's data carries it (ENGINE.md step 6; osm/Edits.hpp): its element ([type] "node", "way"
 * or "relation", [id]), the values it sets (null takes a tag away) and those the user saw (null: no such tag), key
 * by key.
 */
data class UserEdit(val type: String, val id: Long, val set: Map<String, String?>, val seen: Map<String, String?>) {
    companion object {
        /** [edits] as the core reads them (osm/Edits.hpp's read_edits): their JSON, UTF-8, in a direct buffer. */
        fun json(edits: List<UserEdit>): java.nio.ByteBuffer {
            val s = StringBuilder("[")
            edits.forEachIndexed { i, e ->
                if (i > 0) s.append(',')
                s.append("{\"type\":").quoted(e.type).append(",\"id\":").append(e.id).append(",\"tags\":[")
                e.set.entries.forEachIndexed { k, (key, value) ->
                    if (k > 0) s.append(',')
                    s.append("{\"key\":").quoted(key).append(",\"value\":").quoted(value)
                    s.append(",\"seen\":").quoted(e.seen[key]).append('}')
                }
                s.append("]}")
            }
            val bytes = s.append(']').toString().toByteArray(Charsets.UTF_8)
            return java.nio.ByteBuffer.allocateDirect(bytes.size).put(bytes).flip() as java.nio.ByteBuffer
        }

        /** [v] as a JSON string, or null. */
        private fun StringBuilder.quoted(v: String?): StringBuilder {
            if (v == null) return append("null")
            append('"')
            for (c in v) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> append(c)
                }
            }
            return append('"')
        }
    }
}
