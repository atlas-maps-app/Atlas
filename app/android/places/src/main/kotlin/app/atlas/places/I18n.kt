package app.atlas.places

import java.util.concurrent.ConcurrentHashMap

/** The translated strings of id-tagging-schema, one bundled file per language, each read on first use. */
internal object I18n {
    class PresetText(val name: String, val terms: List<String>, val aliases: List<String>)
    class FieldText(val label: String, val options: Map<String, String>)
    class Pack(val presets: Map<String, PresetText>, val fields: Map<String, FieldText>)

    private val packs = ConcurrentHashMap<String, Pack>()

    fun pack(lang: String?): Pack = Langs.resolve(lang).let { l -> packs.getOrPut(l) { load(l) } }

    private fun load(lang: String): Pack {
        val root = Resources.jsonOrNull("i18n/$lang.json")?.obj ?: return Pack(emptyMap(), emptyMap())
        val presets = HashMap<String, PresetText>()
        root["p"]?.obj?.forEach { (id, v) ->
            val a = v.arr ?: return@forEach
            val terms = a.getOrNull(1)?.str?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val aliases = a.getOrNull(2)?.str?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            presets[id] = PresetText(a.getOrNull(0)?.str ?: "", terms, aliases)
        }
        val fields = HashMap<String, FieldText>()
        root["f"]?.obj?.forEach { (id, v) ->
            val a = v.arr ?: return@forEach
            fields[id] = FieldText(a.getOrNull(0)?.str ?: "", a.getOrNull(1).stringMap())
        }
        return Pack(presets, fields)
    }

    fun presetName(id: String, lang: String?): String? =
        pack(lang).presets[id]?.name?.takeIf { it.isNotEmpty() } ?: pack("en").presets[id]?.name?.takeIf { it.isNotEmpty() }

    fun fieldLabel(field: String, lang: String?): String? =
        pack(lang).fields[field]?.label?.takeIf { it.isNotEmpty() } ?: pack("en").fields[field]?.label?.takeIf { it.isNotEmpty() }

    fun fieldOption(field: String, value: String, lang: String?): String? =
        pack(lang).fields[field]?.options?.get(value) ?: pack("en").fields[field]?.options?.get(value)
}
