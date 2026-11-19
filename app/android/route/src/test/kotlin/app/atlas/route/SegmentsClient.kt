package app.atlas.route

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The client these tests hand to [SegmentStore], set up as its own default was (15 s to connect, 60 s a read). Shipped
 * code has no default client: the app hands the store its gated view of `providers`' one client.
 */
internal val segmentsClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}
