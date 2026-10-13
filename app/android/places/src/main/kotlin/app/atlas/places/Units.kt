package app.atlas.places

/** A measured tag value in its dimension's base unit: metres, tonnes, kilowatts, km/h, volts, amperes, kWh. */
data class Measure(val value: Double, val dimension: Dimension)

/** What a [Measure] measures, with its base unit. */
enum class Dimension(val base: String) { LENGTH("m"), MASS("t"), POWER("kW"), SPEED("km/h"), VOLTAGE("V"), CURRENT("A"), ENERGY("kWh"), NONE("") }

/** Reads OSM quantities such as "4.2 m", "3.5t", "11 kW", "12'6\"", "30 mph" and converts them to base units. */
object Units {
    private class UnitDef(val dimension: Dimension, val factor: Double)

    private val units: Map<String, UnitDef> = buildMap {
        fun put(names: String, d: Dimension, f: Double) = names.split(' ').forEach { put(it, UnitDef(d, f)) }
        put("m metre metres meter meters", Dimension.LENGTH, 1.0)
        put("cm", Dimension.LENGTH, 0.01)
        put("mm", Dimension.LENGTH, 0.001)
        put("km", Dimension.LENGTH, 1000.0)
        put("ft feet foot '", Dimension.LENGTH, 0.3048)
        put("in inch inches \"", Dimension.LENGTH, 0.0254)
        put("mi mile miles", Dimension.LENGTH, 1609.344)
        put("nmi", Dimension.LENGTH, 1852.0)
        put("t tonne tonnes ton tons", Dimension.MASS, 1.0)
        put("kg", Dimension.MASS, 0.001)
        put("lb lbs", Dimension.MASS, 0.00045359237)
        put("st", Dimension.MASS, 0.90718474) // the US short ton, as in maxweight=4 st
        put("w", Dimension.POWER, 0.001)
        put("kw", Dimension.POWER, 1.0)
        put("mw", Dimension.POWER, 1000.0)
        put("hp", Dimension.POWER, 0.7457)
        put("km/h kmh kph", Dimension.SPEED, 1.0)
        put("mph", Dimension.SPEED, 1.609344)
        put("knots kn kt", Dimension.SPEED, 1.852)
        put("v", Dimension.VOLTAGE, 1.0)
        put("kv", Dimension.VOLTAGE, 1000.0)
        put("a", Dimension.CURRENT, 1.0)
        put("kwh", Dimension.ENERGY, 1.0)
        put("wh", Dimension.ENERGY, 0.001)
        put("mwh", Dimension.ENERGY, 1000.0)
    }

    private val plain = Regex("""^\s*([-+]?(?:\d+(?:[.,]\d+)?|[.,]\d+))\s*([a-zA-Z/'"]*)\s*$""")
    private val feetInches = Regex("""^\s*(\d+(?:\.\d+)?)\s*'\s*(\d+(?:\.\d+)?)\s*"\s*$""")

    /** True when [word] names a unit Atlas reads ("m", "kW", "mph"…), in any case. */
    fun isUnit(word: String): Boolean = word.lowercase() in units

    /** The dimension a unit name belongs to, or null if unknown. */
    fun dimensionOf(unit: String): Dimension? = units[unit.lowercase()]?.dimension

    /** Parses one value; a bare number gets [Dimension.NONE]. Null when the text is not a quantity. */
    fun parse(text: String): Measure? {
        feetInches.matchEntire(text)?.let { m ->
            return Measure(m.groupValues[1].toDouble() * 0.3048 + m.groupValues[2].toDouble() * 0.0254, Dimension.LENGTH)
        }
        val m = plain.matchEntire(text) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val u = m.groupValues[2]
        if (u.isEmpty()) return Measure(n, Dimension.NONE)
        val unit = units[u.lowercase()] ?: return null
        return Measure(n * unit.factor, unit.dimension)
    }

    /** [value] [unit] in base units, e.g. (150, "kW") -> 150 kW; (2, "MW") -> 2000 kW. */
    fun toBase(value: Double, unit: String?): Measure {
        val u = unit?.let { units[it.lowercase()] } ?: return Measure(value, Dimension.NONE)
        return Measure(value * u.factor, u.dimension)
    }

    /** Every quantity in a ";" list, e.g. "22 kW;11 kW". */
    fun parseAll(text: String): List<Measure> =
        if (';' in text) text.split(';').mapNotNull { parse(it) } else listOfNotNull(parse(text))
}
