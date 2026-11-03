package app.atlas

import android.app.Application
import android.content.Context

/** The application: it owns the one [Services] of the process. */
open class AtlasApp : Application() {
    /** How the process's [Services] are set up: the app's own defaults (the unit tests' application touches no network). */
    protected open val config: ServicesConfig get() = ServicesConfig()

    /** Built on first use, so a cold start pays only for what the first screen needs. */
    val services: Services by lazy { Services(this, config) }
}

/** The process's [Services], from any context of the app. */
val Context.services: Services get() = (applicationContext as AtlasApp).services
