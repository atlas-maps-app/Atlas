package app.atlas.testing

import app.atlas.AtlasApp
import app.atlas.ServicesConfig

/**
 * The application of the app's Robolectric tests (robolectric.properties): its [app.atlas.Services] never touch the
 * network and wire no map, as [testServices]' do not. The app's own would: the activity tests launch the map, which
 * outside CI's runs fetches the world from GitHub's world-test release (MainActivity.allowWorld, 82 MB for the
 * Westerkerk's block) and the published packs' list.
 */
class UnitTestApp : AtlasApp() {
    override val config = ServicesConfig(offline = true, mapEngine = false)
}
