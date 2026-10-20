package app.atlas.testing

import app.atlas.render.data.PackBoxes
import app.atlas.render.data.PackFeatures
import app.atlas.render.data.PackReader
import app.atlas.services.LocalData
import java.io.File

/** The Westerkerk pack the app bundles (`assets/packs/amsterdam.atlas`): the map's and the features' data in tests. */
object BundledPack {
    private val repo: File get() = File(System.getProperty("atlas.repo") ?: "../..")

    val file: File get() = File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas")

    val features: PackFeatures by lazy { PackFeatures(listOf(PackBoxes(PackReader.open(file)))) }

    /** [LocalData] over the pack alone: no region's store, offline. */
    val local: LocalData by lazy { LocalData(stores = { null }, packs = { features }) }
}
