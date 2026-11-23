package app.atlas.mapcorecheck

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The client these tests hand to the tiles' fetchers, set up as `app.atlas.tiles.Http.client` was (10 s to
 * connect, 30 s a read, 90 s a call), one for the whole run so connections are reused. Shipped code has no default
 * client: the app hands each its gated view of `providers`' one client.
 */
internal val tilesClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
}
