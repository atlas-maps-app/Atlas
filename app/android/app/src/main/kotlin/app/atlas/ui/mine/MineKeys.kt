package app.atlas.ui.mine

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/** What the mine package keeps in the app's settings, under `mine.` keys (the shell's own are in `SettingsKeys`). */
object MineKeys {
    /** Temperatures in °F instead of °C. */
    val TEMP_F = booleanPreferencesKey("mine.temp.f")

    /** Clock times as 12-hour instead of 24-hour. */
    val CLOCK_12 = booleanPreferencesKey("mine.clock.12")

    /** The voice is off: guidance is felt, not heard. */
    val VOICE_MUTED = booleanPreferencesKey("mine.voice.muted")

    /** Vibrations for turns (on unless switched off). */
    val HAPTICS = booleanPreferencesKey("mine.voice.haptics")

    /** Where you have been is recorded on this phone (off unless switched on). */
    val TRAIL = booleanPreferencesKey("mine.trail")

    /** The barometer's last 48 hours are kept for the forecast (off unless switched on). */
    val PRESSURE = booleanPreferencesKey("mine.pressure")

    /** Offline downloads wait for Wi-Fi (on unless switched off). */
    val WIFI_ONLY = booleanPreferencesKey("mine.offline.wifi")

    /** Languages of the voices to keep with every region ("en", "nl"). */
    val VOICE_LANGS = stringSetPreferencesKey("mine.offline.voices")

    /** Regions to keep fresh around home, work and the trip being planned. */
    val AUTO_REGIONS = booleanPreferencesKey("mine.offline.auto")

    /** The regions the automatic download has asked for already (box corners), so a deleted one does not come back. */
    val AUTO_DONE = stringSetPreferencesKey("mine.offline.auto.done")

    /** The car: an `EvModels` id, its state of charge (0..1), the ports it takes (OSM socket names) and the least kW wanted. */
    val EV_MODEL = stringPreferencesKey("mine.ev.model")
    val EV_SOC = floatPreferencesKey("mine.ev.soc")
    val EV_PORTS = stringSetPreferencesKey("mine.ev.ports")
    val EV_MIN_KW = intPreferencesKey("mine.ev.minkw")

    /** The bottle on the water screen: its size and what is left (millilitres), and when that was said. */
    val WATER_BOTTLE_ML = intPreferencesKey("mine.water.bottle")
    val WATER_LEFT_ML = intPreferencesKey("mine.water.left")
    val WATER_STAMP = longPreferencesKey("mine.water.stamp")

    /** The tank: litres of the car's tank, how full it is (0..1), and the fuel wanted ("e10", "diesel"). */
    val FUEL_TANK_L = intPreferencesKey("mine.fuel.tank")
    val FUEL_LEFT = floatPreferencesKey("mine.fuel.left")
    val FUEL_GRADE = stringPreferencesKey("mine.fuel.grade")

    /** The power bank you carry, in mAh. */
    val POWER_BANK_MAH = intPreferencesKey("mine.power.bank")
}
