package app.atlas.places

/**
 * An id-tagging-schema field behind one of Atlas's facts: the tag it edits, how id asks for it and the values it
 * offers. The Tag-it quests ask for an UNKNOWN [Fact] with the field of its [FactKind.field].
 */
class Field internal constructor(
    /** The field id, as [FactKind.field] and [Preset.fields] name it ("wheelchair", "diet_multi"). */
    val id: String,
    /** The tag key it edits; for a field over several keys, their common prefix ("diet:", "payment:"). */
    val key: String,
    /** id's kind of field: "check" (yes/no), "radio", "combo", "multiCombo", "semiCombo", "number", "text", "url"… */
    val type: String,
    /** The values it offers, in id's order; empty for yes/no checks and free text. */
    val options: List<String>,
) {
    /** True when the field sets several keys, one per option ("diet:vegan=yes"). */
    val isMultiKey: Boolean get() = key.endsWith(":")

    /** The field's name in [lang], else in English ("Wheelchair Access"). */
    fun label(lang: String? = "en"): String = I18n.fieldLabel(id, lang) ?: id.substringAfterLast('/').replace('_', ' ')

    /** An option's name in [lang], else in English, else the value made readable ("Limited"). */
    fun optionLabel(value: String, lang: String? = "en"): String =
        I18n.fieldOption(id, value, lang) ?: value.replace('_', ' ').replaceFirstChar { it.uppercase() }

    override fun toString() = id
}

/** The fields of Atlas's facts, read on first use from `fields.json`. */
object Fields {
    private val byId: Map<String, Field> by lazy {
        (Resources.json("fields.json").obj ?: emptyMap()).mapNotNull { (id, v) ->
            val o = v.obj ?: return@mapNotNull null
            id to Field(id, o["k"]?.str ?: id, o["t"]?.str ?: "text", o["o"].stringList())
        }.toMap()
    }

    /** Every field id Atlas bundles. */
    val ids: Set<String> get() = byId.keys

    fun byId(id: String): Field? = byId[id]

    /** The field that edits [key] ("wheelchair"), or the multi-key field it belongs to ("diet:vegan" -> diet_multi). */
    fun forKey(key: String): Field? =
        byId.values.firstOrNull { it.key == key } ?: byId.values.firstOrNull { it.isMultiKey && key.startsWith(it.key) }

    /** The field a fact is about, if it has one. */
    fun of(kind: FactKind): Field? = kind.field?.let { byId[it] }
}
