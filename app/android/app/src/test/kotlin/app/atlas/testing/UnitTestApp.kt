package app.atlas.testing

import app.atlas.AtlasApp
import app.atlas.ServicesConfig

/**
 * The application of the app's Robolectric tests (robolectric.properties): its [app.atlas.Services] never touch the
 * network and wire no map, as [testServices]' do not. The app's own would: the activity tests launch the map, which
 * outside CI's runs fetches the world from the GitHub release world-latest points at (MainActivity.allowWorld: its
 * index and 9.7 MB globe, then the packs' heads and chunks by ranges) and the published packs' list.
 */
class UnitTestApp : AtlasApp() {
    override val config = ServicesConfig(offline = true, mapEngine = false)
}
