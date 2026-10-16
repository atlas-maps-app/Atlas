package app.atlas.ui.mine.charge

import app.atlas.model.Mode
import app.atlas.providers.EvModel
import app.atlas.providers.EvModels
import app.atlas.services.SettingsStore
import app.atlas.ui.go.GoStore
import app.atlas.ui.mine.MineKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Your electric car as Atlas knows it: a model from the open EV data, how full the battery is, and the ports it takes. */
data class Car(val model: EvModel?, val soc: Double, val ports: Set<String>) {
    /** Kilometres you can drive on what is in the battery, from the model's consumption; null with no model. */
    val rangeKm: Double? get() = model?.let { ChargeModel.rangeKm(it, soc) }
    val name: String? get() = model?.let { "${it.make} ${it.model}".trim() }

    companion object {
        const val DEFAULT_SOC = 0.8
    }
}

/**
 * Reads and writes the car in the settings (`mine.ev.*`), and, when it is chosen, puts it into the Go tab's car vehicle too,
 * so a trip planned there also plans its charging stops. Nothing leaves the phone.
 */
class CarStore(private val settings: SettingsStore, private val go: GoStore, private val models: EvModels = EvModels.bundled()) {
    val car: Flow<Car> = combine(
        settings.flow(MineKeys.EV_MODEL), settings.flow(MineKeys.EV_SOC), settings.flow(MineKeys.EV_PORTS),
    ) { id, soc, ports ->
        val model = id?.let { models.byId(it) }
        Car(model, soc?.toDouble() ?: Car.DEFAULT_SOC, ports ?: model?.connectors.orEmpty())
    }

    suspend fun now(): Car = car.first()

    /** Chooses [model] (null forgets the car); its ports become the ones to look for. */
    suspend fun choose(model: EvModel?, soc: Double = Car.DEFAULT_SOC) {
        settings.set(MineKeys.EV_MODEL, model?.id)
        settings.set(MineKeys.EV_PORTS, null)
        settings.set(MineKeys.EV_SOC, model?.let { soc.toFloat() })
        syncVehicle(model, soc)
    }

    /** Says how full the battery is now. */
    suspend fun setSoc(soc: Double) {
        settings.set(MineKeys.EV_SOC, soc.toFloat())
        syncVehicle(now().model, soc)
    }

    /** Looks for these ports only (empty: all the car takes). */
    suspend fun setPorts(ports: Set<String>) = settings.set(MineKeys.EV_PORTS, ports.takeIf { it.isNotEmpty() })

    private suspend fun syncVehicle(model: EvModel?, soc: Double) {
        val current = go.vehicleNow(Mode.CAR)
        go.setVehicle(Mode.CAR, current.copy(ev = model?.toEvSpec(socNow = soc)))
    }
}
