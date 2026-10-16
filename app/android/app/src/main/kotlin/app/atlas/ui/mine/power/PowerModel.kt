package app.atlas.ui.mine.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Sun
import app.atlas.model.Place
import app.atlas.providers.Forecast
import app.atlas.search.PlaceSource
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** The phone's battery as it says: charge, whether it is plugged in, battery saver, and, when it tells, how long it lasts. */
data class PhoneBattery(val percent: Int, val charging: Boolean, val saver: Boolean, val hoursLeft: Double?, val capacityMah: Double?) {
    companion object {
        /** Reads the battery now. A phone that does not say its current or its charge counter gives no hours left. */
        fun read(context: Context): PhoneBattery? {
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return null
            val percent = (level * 100f / scale).roundToInt()
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val bm = context.getSystemService(BatteryManager::class.java)
            val counter = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 && it != Long.MIN_VALUE } // µAh
            val current = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.takeIf { it != Long.MIN_VALUE && it != 0L } // µA
            val hours = if (!charging && counter != null && current != null) counter.toDouble() / abs(current) else null
            val capacity = if (counter != null && percent > 0) counter / 1000.0 / (percent / 100.0) else null
            val saver = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: false
            return PhoneBattery(percent, charging, saver, hours?.takeIf { it in 0.05..200.0 }, capacity)
        }
    }
}

/** One place to put the phone on charge, from the places on this phone. */
data class PowerSpot(val place: Place, val kind: Kind, val name: String, val detail: String, val distanceM: Double) {
    enum class Kind(val symbol: String, val colour: Long) {
        LOCKER("power.bank", 0xFF7B4DFF), CAFE("place.cafe.2", 0xFFB8461A), PILLAR("power.pillar", 0xFF2E5BFF), LIBRARY("place.library", 0xFF2F7A3A),
        SOLAR("weather.sun.2", 0xFFE8922A), OUTLET("fact.socket", 0xFF1E9E5A),
    }
}

/** What the Power board works out: spots to charge a phone around you, the sun's yield for a panel, the bank's worth. */
object PowerModel {
    /** The spots with a way to charge a phone within [radiusM] of [centre], nearest first. */
    fun spots(source: PlaceSource, centre: LatLon, radiusM: Double = 1_500.0, limit: Int = 8): List<PowerSpot> {
        val box = BBox.around(centre, radiusM)
        val seen = HashSet<String>()
        val out = ArrayList<PowerSpot>()
        fun add(p: Place, kind: PowerSpot.Kind?) {
            if (!seen.add(p.key)) return
            val d = Geo.distance(centre, p.location)
            if (d > radiusM) return
            val k = kind ?: kindOf(p) ?: return
            out += PowerSpot(p, k, p.name() ?: p.tags["operator"] ?: p.tags["brand"] ?: "", detail(p), d)
        }
        for (r in source.byTag("amenity", "device_charging_station", box, 40)) add(r.place, PowerSpot.Kind.LOCKER)
        for (r in source.byTag("power_supply", "yes", box, 80)) add(r.place, null)
        for (r in source.byTag("socket:usb", null, box, 40)) add(r.place, null)
        for (r in source.byTag("socket:schuko", null, box, 40)) if (r.tags["amenity"] != "charging_station") add(r.place, null)
        return out.sortedBy { it.distanceM }.take(limit)
    }

    private fun kindOf(p: Place): PowerSpot.Kind? {
        val t = p.tags
        return when {
            t["amenity"] == "charging_station" -> null
            t["amenity"] in setOf("cafe", "restaurant", "fast_food", "pub", "bar") -> PowerSpot.Kind.CAFE
            t["amenity"] == "library" -> PowerSpot.Kind.LIBRARY
            t["public_transport"] != null || t["railway"] != null -> PowerSpot.Kind.PILLAR
            t["amenity"] == "bench" && t["solar_panel"] != null -> PowerSpot.Kind.SOLAR
            else -> PowerSpot.Kind.OUTLET
        }
    }

    /** The words [detail] puts together, so the screen can give them in the person's language (the defaults are English). */
    class Words(
        val usb: String = "USB", val plugs: String = "plugs", val outlets: String = "outlets", val free: String = "free", val fee: String = "fee",
        val feeWith: (String) -> String = { "fee: $it" }, val open24h: String = "24/7",
    )

    /** What the tags say about the charging there: sockets, price, whether it is free. */
    fun detail(p: Place, w: Words = Words()): String {
        val t = p.tags
        val sockets = buildList {
            if (t.keys.any { it.startsWith("socket:usb") }) add(w.usb)
            if (t.keys.any { it.startsWith("socket:schuko") || it.startsWith("socket:type") }) add(w.plugs)
            if (t["power_supply"] == "yes" && isEmpty()) add(w.outlets)
        }.joinToString(" & ")
        val fee = when (t["fee"]) { "no" -> w.free; "yes" -> t["charge"]?.let { w.feeWith(it) } ?: w.fee; else -> null }
        return listOfNotNull(sockets.ifEmpty { null }, fee, t["opening_hours"]?.takeIf { it == "24/7" }?.let { w.open24h }).joinToString(" · ")
    }

    /** A campsite with a hook-up (`power_supply=yes`) near, for the off-the-grid card: null when none. */
    fun hookup(source: PlaceSource, centre: LatLon, radiusM: Double = 15_000.0): PowerSpot? {
        val box = BBox.around(centre, radiusM)
        val sites = (source.byTag("tourism", "camp_site", box, 40) + source.byTag("tourism", "caravan_site", box, 40)).map { it.place }
            .filter { it.tags["power_supply"] == "yes" || it.tags["power_supply"] == "16A" || it.tags.keys.any { k -> k.startsWith("power_supply") } }
        return sites.minByOrNull { Geo.distance(centre, it.location) }?.let { p -> PowerSpot(p, PowerSpot.Kind.OUTLET, p.name() ?: "", p.tags["power_supply"] ?: "", Geo.distance(centre, p.location)) }
    }

    /** Hourly watts of a panel of [panelW] today at [at]: the sun's height, thinned by the forecast's cloud, 80 % of it reaching the battery. */
    fun solarCurve(at: LatLon, zone: ZoneId, date: java.time.LocalDate, forecast: Forecast?, panelW: Double = 100.0): DoubleArray {
        val clouds = forecast?.hours?.associateBy { it.time.atZone(zone).let { z -> z.toLocalDate() to z.hour } }
        return DoubleArray(24 * 4) { q ->
            val t = date.atStartOfDay(zone).plusMinutes(q * 15L + 7).toInstant()
            val alt = Sun.position(t, at).altitudeDeg
            if (alt <= 0) 0.0 else {
                val z = t.atZone(zone)
                val cover = clouds?.get(z.toLocalDate() to z.hour)?.cloudCover?.takeIf { it.isFinite() }?.div(100.0) ?: 0.0
                // Kasten and Czeplak: global radiation under cloud cover n is (1 - 0.75 n^3.4) of clear sky.
                panelW * sin(Math.toRadians(alt)).coerceIn(0.0, 1.0) * (1 - 0.75 * cover.pow(3.4)) * 0.8
            }
        }
    }

    /** Watt-hours of the day from a curve of quarter hours. */
    fun wattHours(curve: DoubleArray): Double = curve.sum() * 0.25

    /** The first daylight hour that the forecast covers with at least [cover] % cloud, as an instant; null when none. */
    fun cloudsAt(forecast: Forecast?, zone: ZoneId, date: java.time.LocalDate, cover: Double = 70.0): Instant? =
        forecast?.hours?.firstOrNull { it.isDay && it.cloudCover >= cover && it.time.atZone(zone).toLocalDate() == date && it.time.isAfter(date.atStartOfDay(zone).plusHours(9).toInstant()) }?.time

    /** Phone charges and laptop charges in a bank of [mAh] at 3.7 V: Wh over a typical phone's 15 Wh and laptop's 50 Wh, less 25 % lost converting. */
    fun bankWorth(mAh: Int, phoneWh: Double = 17.0, laptopWh: Double = 50.0): Pair<Double, Double> {
        val wh = mAh * 3.7 / 1000.0 * 0.75
        return wh / phoneWh to wh / laptopWh
    }

    /** "3 h" or "40 min". */
    fun duration(hours: Double): String = if (hours < 1) "${max(1, (hours * 60).roundToInt())} min" else if (hours < 10) String.format(java.util.Locale.ROOT, "%.1f h", hours).replace(".0 h", " h") else "${hours.roundToInt()} h"
}
