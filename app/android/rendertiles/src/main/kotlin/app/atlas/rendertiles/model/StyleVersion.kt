package app.atlas.rendertiles.model

import java.security.MessageDigest

/**
 * The version of stage P's derivation: what the packer writes into render tiles from a scene (plan-vector D18, beside
 * `EngineVersion`, which versions what the port draws). Tiles, the core's expanded-tile cache and the likeness
 * references are keyed by both.
 *
 * [ID] hashes the payload version, [DERIVATION] and the schema (every layer's columns, the tag keys each carries, the
 * keep ladder), so a change of the format changes it by itself; a change of how a value is derived that keeps the
 * format bumps [DERIVATION].
 */
object StyleVersion {
    /** Bumped when the packer derives a value differently while the format stays the same. */
    const val DERIVATION = 1

    /** `s<payload>.<derivation>-` and 16 hex digits of the schema's hash. */
    val ID: String by lazy {
        val md = MessageDigest.getInstance("SHA-256")
        fun put(s: String) {
            md.update(s.toByteArray(Charsets.UTF_8))
            md.update(0)
        }
        put("payload ${Format.PAYLOAD}")
        for (layer in Layer.entries) {
            val cols = runCatching { Schema.columns(layer) }.getOrNull() ?: continue
            put("layer ${layer.id} ${layer.label}")
            for (c in cols) put("${c.name} ${c.codec.id}")
        }
        for ((layer, keys) in Schema.RENDER_TAGS) put("tags ${layer.label} ${keys.joinToString(",")}")
        put("keep ${Keep.TOLERANCES.joinToString(",")}")
        "s${Format.PAYLOAD}.$DERIVATION-" + md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }.substring(0, 16)
    }
}
