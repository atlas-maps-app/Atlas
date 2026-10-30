package app.atlas.providers

import java.io.IOException

/** The keys the user pasted in Settings, by provider id. The app implements it over its own secure storage. */
fun interface Keys {
    /** The key for [providerId], or null when none is set. */
    fun key(providerId: String): String?

    companion object {
        val NONE = Keys { null }

        fun of(vararg pairs: Pair<String, String>): Keys {
            val map = pairs.toMap()
            return Keys { id -> map[id]?.takeIf { it.isNotBlank() } }
        }
    }
}

/** Whether a provider needs a key: none, an optional one (for more quota or features), a required one, or a partner agreement. */
enum class KeyNeed { NONE, OPTIONAL, REQUIRED, PARTNER }

/** What a provider can do right now. */
enum class ProviderState {
    /** Works and will be asked. */
    READY,

    /** Works once the user adds a key in Settings. */
    NEEDS_KEY,

    /** Cannot work for Atlas at all for now (no public API, or terms that forbid our use); shown honestly as such. */
    UNAVAILABLE,

    /** Works offline from a file it downloads; nothing is downloaded yet. */
    NEEDS_DOWNLOAD,
}

/**
 * What the Settings · Data screen shows for a source: its name, its licence label, the credit line,
 * whether it needs a key and whether it is on by default; and for the privacy board, the hosts it is asked at.
 */
data class ProviderInfo(
    val id: String,
    val name: String,
    val licence: String,
    val attribution: String,
    val url: String,
    val keyNeed: KeyNeed = KeyNeed.NONE,
    val onByDefault: Boolean = keyNeed == KeyNeed.NONE,
    /** Anything the user should know: terms, limits, why it is off. */
    val note: String? = null,
    /** The hosts the app sends this source's requests to (its servers, not its website), as the privacy board names them. */
    val hosts: List<String> = emptyList(),
    /** False where the source is asked only when the user acts (a note they leave, edits they send): it has no switch. */
    val switchable: Boolean = true,
    /** The source whose servers this one is asked through (OpenStreetMap's chargers, through Overpass), if any. */
    val via: String? = null,
)

/** A source of data with an identity and a state. */
interface Provider {
    val info: ProviderInfo
    val id: String get() = info.id

    /** READY, NEEDS_KEY or UNAVAILABLE, from the keys the user has entered. */
    fun state(): ProviderState
}

/** The state of a provider whose key is [keyNeed] and whose key is currently [key]. */
fun stateFor(keyNeed: KeyNeed, key: String?): ProviderState = when (keyNeed) {
    KeyNeed.NONE, KeyNeed.OPTIONAL -> ProviderState.READY
    KeyNeed.REQUIRED, KeyNeed.PARTNER -> if (key.isNullOrBlank()) ProviderState.NEEDS_KEY else ProviderState.READY
}

/** A provider could not answer. It is an [IOException] so one catch handles network and provider failures alike. */
open class ProviderException(val providerId: String, message: String, cause: Throwable? = null) : IOException(message, cause)

/** The provider needs a key the user has not entered. */
class NeedsKeyException(providerId: String) : ProviderException(providerId, "$providerId needs a key: add one in Settings · Data")

/** The provider cannot be used: no public API, terms that forbid it, or a missing partner agreement. */
class ProviderUnavailableException(providerId: String, reason: String) : ProviderException(providerId, "$providerId is unavailable: $reason")
