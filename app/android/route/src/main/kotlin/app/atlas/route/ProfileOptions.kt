package app.atlas.route

import app.atlas.model.Mode
import app.atlas.model.RoutePrefs
import app.atlas.model.Vehicle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a request's [RoutePrefs] and [Vehicle] into BRouter key values: overrides of the profile's global `assign`s,
 * so one profile file serves every preference without being rewritten.
 */
object ProfileOptions {

    fun keyValues(mode: Mode, prefs: RoutePrefs, vehicle: Vehicle): Map<String, String> {
        val kv = LinkedHashMap<String, String>()
        val spec = Profiles.forMode(mode) ?: return kv
        if (spec.preferences) {
            kv["atlas_quiet"] = num(prefs.quiet)
            kv["atlas_green"] = num(max(prefs.green, prefs.shade))
            kv["atlas_lit"] = num(prefs.lit)
            kv["atlas_avoid_unpaved"] = flag(prefs.avoidUnpaved)
            kv["atlas_avoid_ferries"] = flag(prefs.avoidFerries)
        }
        when (mode) {
            Mode.WALK, Mode.WHEELCHAIR -> {
                kv["atlas_quiet"] = num(prefs.quiet)
                kv["atlas_green"] = num(max(prefs.green, prefs.shade))
                kv["atlas_lit"] = num(prefs.lit)
                kv["atlas_flat"] = num(prefs.flat)
                kv["avoid_unpaved"] = flag(prefs.avoidUnpaved)
                kv["avoid_ferries"] = flag(prefs.avoidFerries)
                if (mode == Mode.WALK) kv["avoid_steps"] = flag(prefs.avoidSteps)
            }
            Mode.HIKE -> {
                if (prefs.avoidSteps) kv["allow_steps"] = "0"
                if (prefs.avoidFerries) kv["allow_ferries"] = "0"
                if (prefs.flat >= 0.3) kv["consider_elevation"] = "1"
                if (prefs.quiet >= 0.5) kv["consider_noise"] = "1"
                if (prefs.green >= 0.5) kv["consider_forest"] = "1"
            }
            Mode.BIKE, Mode.EBIKE, Mode.CARGO_BIKE, Mode.ROAD_BIKE -> {
                if (prefs.avoidSteps && mode != Mode.CARGO_BIKE) kv["allow_steps"] = "0"
                if (prefs.avoidFerries) kv["allow_ferries"] = "0"
                if (prefs.flat > 0) {
                    kv["consider_elevation"] = "1"
                    // trekking-style profiles penalise descent as a proxy for hills; add a direct climbing cost too
                    kv["uphillcost"] = num(80 * prefs.flat)
                }
                if (mode == Mode.ROAD_BIKE) kv["consider_traffic"] = num(max(0.1, prefs.quiet))
                else if (prefs.quiet >= 0.5) kv["consider_traffic"] = "1"
                if (prefs.quiet >= 0.5) kv["consider_noise"] = "1"
                if (prefs.green >= 0.5) {
                    kv["consider_forest"] = "1"; kv["consider_river"] = "1"
                }
            }
            Mode.MTB -> {
                if (prefs.avoidFerries) kv["allow_ferries"] = "0"
                if (prefs.flat >= 0.5) kv["hills"] = "1"
            }
            Mode.MOPED -> Unit
            Mode.MOTORCYCLE -> {
                motor(kv, prefs)
                kv["vmax"] = num(min(vehicle.maxSpeedKmh ?: 200.0, 100 + 40 * prefs.fast))
            }
            Mode.CAR -> {
                motor(kv, prefs)
                kv["vmax"] = num(min(vehicle.maxSpeedKmh ?: 200.0, 90 + 70 * prefs.fast))
                val mass = vehicle.weightT?.times(1000) ?: if (vehicle.ev != null) 2000.0 else null
                if (mass != null) {
                    kv["totalweight"] = num(mass)
                    kv["f_roll"] = num(mass * G * 0.0095)
                }
                if (vehicle.ev != null) {
                    // electric: strong regeneration, little standby power
                    kv["recup_efficiency"] = "0.75"
                    kv["p_standby"] = "300"
                }
            }
            Mode.CAMPER -> {
                motor(kv, prefs)
                kv["vmax"] = num(min(vehicle.maxSpeedKmh ?: 100.0, 100.0))
                val mass = (vehicle.weightT ?: 3.5) * 1000
                kv["totalweight"] = num(mass)
                kv["f_roll"] = num(mass * G * 0.01)
                kv["f_air"] = num(airForce(vehicle.widthM ?: 2.3, vehicle.heightM ?: 3.0, 0.55))
                kv["atlas_heavy"] = flag((vehicle.weightT ?: 3.5) > 3.5)
                kv["atlas_low"] = flag((vehicle.heightM ?: 3.0) <= 2.0)
            }
            Mode.TRUCK -> {
                motor(kv, prefs)
                kv["avoid_ferries"] = flag(prefs.avoidFerries)
                val weight = vehicle.weightT ?: 40.0
                kv["vmax"] = num(vehicle.maxSpeedKmh ?: if (weight > 7.5) 80.0 else 90.0)
                kv["totalweight"] = num(weight * 1000)
                kv["f_roll"] = num(weight * 1000 * G * 0.006)
                kv["f_air"] = num(airForce(vehicle.widthM ?: 2.55, vehicle.heightM ?: 4.0, 0.6))
                kv["height_restrictor_ok"] = "0"
            }
            Mode.BOAT, Mode.SAIL -> {
                vehicle.maxSpeedKmh?.let { kv["maxSpeed"] = num(it) }
                if (prefs.avoidFerries) kv["avoid_ferry_lines"] = "1"
            }
            Mode.TRANSIT -> Unit
        }
        return kv
    }

    private fun motor(kv: MutableMap<String, String>, prefs: RoutePrefs) {
        kv["avoid_toll"] = flag(prefs.avoidTolls)
        kv["avoid_motorways"] = flag(prefs.avoidMotorways)
        kv["avoid_unpaved"] = flag(prefs.avoidUnpaved)
    }

    /** Aerodynamic drag factor ½·cw·A·ρ in N/(m/s)². */
    private fun airForce(width: Double, height: Double, cw: Double) = 0.5 * cw * width * height * 1.2

    private const val G = 9.81

    private fun flag(b: Boolean) = if (b) "1" else "0"
    private fun num(v: Double) = String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
}
