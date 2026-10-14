package app.atlas

import android.app.Application
import android.content.Context

/** The application: it owns the one [Services] of the process. */
class AtlasApp : Application() {
    /** Built on first use, so a cold start pays only for what the first screen needs. */
    val services: Services by lazy { Services(this) }
}

/** The process's [Services], from any context of the app. */
val Context.services: Services get() = (applicationContext as AtlasApp).services
