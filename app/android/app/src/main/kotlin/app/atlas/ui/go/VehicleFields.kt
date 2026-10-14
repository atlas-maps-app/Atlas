package app.atlas.ui.go

import androidx.annotation.StringRes
import app.atlas.R
import app.atlas.model.EvSpec
import app.atlas.model.Mode
import app.atlas.model.Vehicle
import app.atlas.services.Units
import kotlin.math.roundToInt

/**
 * One number of a vehicle that routing respects: how to read and write it on a [Vehicle], its unit, its step and its
 * sensible range. The form edits these with a stepper; nothing is typed.
 */
enum class VehicleField(
    @StringRes val label: Int,
    val unit: String,
    val step: Double,
    val min: Double,
    val max: Double,
    val get: (Vehicle) -> Double?,
    val set: (Vehicle, Double?) -> Vehicle,
) {
    LENGTH(R.string.go_field_length, "m", 0.1, 1.0, 30.0, { it.lengthM }, { v, x -> v.copy(lengthM = x) }),
    HEIGHT(R.string.go_field_height, "m", 0.05, 1.0, 5.0, { it.heightM }, { v, x -> v.copy(heightM = x) }),
    WIDTH(R.string.go_field_width, "m", 0.05, 1.0, 3.5, { it.widthM }, { v, x -> v.copy(widthM = x) }),
    WEIGHT(R.string.go_field_weight, "t", 0.5, 0.5, 60.0, { it.weightT }, { v, x -> v.copy(weightT = x) }),
    AXLE(R.string.go_field_axle, "t", 0.5, 1.0, 20.0, { it.axleLoadT }, { v, x -> v.copy(axleLoadT = x) }),
    AIR_DRAFT(R.string.go_field_air_draft, "m", 0.1, 0.5, 40.0, { it.airDraftM }, { v, x -> v.copy(airDraftM = x) }),
    DRAFT(R.string.go_field_draft, "m", 0.1, 0.1, 6.0, { it.draftM }, { v, x -> v.copy(draftM = x) }),
    BEAM(R.string.go_field_beam, "m", 0.1, 0.5, 8.0, { it.beamM }, { v, x -> v.copy(beamM = x) }),
    BATTERY(R.string.go_field_battery, "kWh", 1.0, 0.2, 200.0, { it.ev?.batteryKwh }, { v, x -> v.withEv { e -> e.copy(batteryKwh = x ?: e.batteryKwh) } }),
    CONSUMPTION(R.string.go_field_consumption, "kWh/100 km", 0.5, 0.5, 60.0, { it.ev?.consumption }, { v, x -> v.withEv { e -> e.copy(consumption = x ?: e.consumption) } }),
    CHARGE_NOW(R.string.go_field_charge_now, "%", 5.0, 5.0, 100.0, { it.ev?.socNow?.times(100) }, { v, x -> v.withEv { e -> e.copy(socNow = (x ?: 80.0) / 100) } }),
    ARRIVE_MIN(R.string.go_field_arrive_min, "%", 5.0, 5.0, 50.0, { it.ev?.socMin?.times(100) }, { v, x -> v.withEv { e -> e.copy(socMin = (x ?: 10.0) / 100) } }),
    MAX_DC(R.string.go_field_max_dc, "kW", 10.0, 10.0, 400.0, { it.ev?.maxDcKw }, { v, x -> v.withEv { e -> e.copy(maxDcKw = x ?: e.maxDcKw) } }),
    ;

    /** The value one step up or down from [current] (or a sensible start when unset), kept in range. */
    fun stepped(current: Double?, up: Boolean, start: Double): Double {
        val base = current ?: return start.coerceIn(min, max)
        val next = base + if (up) step else -step
        // Round to the step so repeated taps do not drift (3.9999 → 4.0).
        return ((next / step).roundToInt() * step).coerceIn(min, max)
    }

    /** "4.0 m", "40 t", "80 %" (feet and inches for heights in imperial units). */
    fun format(value: Double, units: Units = Units.METRIC): String = when {
        unit == "m" && units == Units.IMPERIAL -> GoFormat.size(value, units)
        unit == "m" -> GoFormat.size(value)
        unit == "t" -> "${trim(value)} t"
        unit == "%" -> "${value.roundToInt()} %"
        else -> "${trim(value)} $unit"
    }

    companion object {
        /** The fields that matter for [mode], in the order the form shows them. */
        fun forMode(mode: Mode): List<VehicleField> = when (mode) {
            Mode.TRUCK -> listOf(LENGTH, HEIGHT, WEIGHT, WIDTH, AXLE)
            Mode.CAMPER -> listOf(LENGTH, HEIGHT, WEIGHT, WIDTH)
            Mode.BOAT, Mode.SAIL -> listOf(LENGTH, AIR_DRAFT, DRAFT, BEAM)
            Mode.CAR -> listOf(BATTERY, CONSUMPTION, CHARGE_NOW, ARRIVE_MIN, MAX_DC, HEIGHT)
            Mode.EBIKE -> listOf(BATTERY, CHARGE_NOW)
            else -> emptyList()
        }

        /** Where a stepper starts on an unset field of [mode]. */
        fun start(field: VehicleField, mode: Mode): Double {
            GoStore.defaultVehicle(mode).let { d -> field.get(d)?.let { return it } }
            return when (field) {
                BATTERY -> if (mode == Mode.EBIKE) 0.5 else 60.0
                CONSUMPTION -> if (mode == Mode.EBIKE) 1.0 else 18.0
                CHARGE_NOW -> 80.0
                ARRIVE_MIN -> 10.0
                MAX_DC -> 100.0
                HEIGHT -> if (mode == Mode.CAR) 1.6 else 3.0
                else -> (field.min + field.max) / 4
            }
        }

        private fun trim(x: Double): String = if (x == x.roundToInt().toDouble()) x.roundToInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", x)
    }
}

/** [this] with its electric spec changed by [f] (one made with defaults first when there is none). */
fun Vehicle.withEv(f: (EvSpec) -> EvSpec): Vehicle = copy(ev = f(ev ?: EvSpec(batteryKwh = 60.0, consumption = 18.0)))

/** Short summaries of a vehicle for rows: "16.5 m · 4.0 m · 40 t". */
object VehicleFacts {
    fun short(mode: Mode, v: Vehicle, units: Units): String =
        VehicleField.forMode(mode).take(3).mapNotNull { f -> f.get(v)?.let { f.format(it, units) } }.joinToString(" · ")
}
