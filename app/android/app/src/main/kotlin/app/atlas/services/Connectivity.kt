package app.atlas.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the phone can reach the internet now, so screens say "offline" at once instead of waiting on timeouts. */
interface Connectivity {
    val online: StateFlow<Boolean>

    /** True on a metered network (mobile data): big downloads wait for Wi-Fi unless the user says otherwise. */
    val metered: StateFlow<Boolean>
}

/** A fixed answer, for tests and the CI demo. */
class FixedConnectivity(online: Boolean, metered: Boolean = false) : Connectivity {
    override val online: StateFlow<Boolean> = MutableStateFlow(online)
    override val metered: StateFlow<Boolean> = MutableStateFlow(metered)
}

/** The system's default network, watched with a callback for the life of the process. */
class SystemConnectivity(context: Context) : Connectivity {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val onlineState = MutableStateFlow(current())
    private val meteredState = MutableStateFlow(manager?.isActiveNetworkMetered ?: false)
    override val online: StateFlow<Boolean> = onlineState.asStateFlow()
    override val metered: StateFlow<Boolean> = meteredState.asStateFlow()

    init {
        runCatching {
            manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    onlineState.value = isOnline(caps)
                    meteredState.value = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }

                override fun onLost(network: Network) {
                    onlineState.value = false
                    meteredState.value = false
                }
            })
        }
    }

    private fun current(): Boolean {
        val m = manager ?: return false
        return m.getNetworkCapabilities(m.activeNetwork)?.let(::isOnline) ?: false
    }

    /** Online means a network that reaches the internet, as the system checked (so not a captive portal's login). */
    private fun isOnline(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
