package app.atlas.rendertiles

import app.atlas.render.data.PackReader
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveScene
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.compile.SceneToTiles
import java.io.File
import java.nio.file.Files

/**
 * The bundled set's packs, built once per test JVM: the Westerkerk (the app's bundled element pack, in git) and the
 * canal ring (`packs-renders`' grachten.atlas, kept in the repo's cache on the owner's machine), with the scenes they
 * were derived from.
 */
object Packs {
    val repo: File = File(System.getProperty("atlas.repo") ?: "../../..").canonicalFile
    val westerkerkPack = File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas")
    val grachtenPack = File(repo, "cache/packs/regions/grachten.atlas")

    val work: File by lazy { Files.createTempDirectory("rtiles").toFile().also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() }) } }

    class Built(val element: File, val file: File, val packed: SceneToTiles.Companion.Packed, val millis: Long) {
        val written: Format3Writer.Written get() = packed.written
        val reader: Format3Reader by lazy { Format3Reader(file) }
    }

    private val built = HashMap<String, Built>()

    /** The render pack of [element], built once. */
    @Synchronized
    fun build(element: File, name: String = element.nameWithoutExtension): Built = built.getOrPut(name) {
        val out = File(work, "$name.atlas")
        val t0 = System.nanoTime()
        val p = SceneToTiles.pack(element, out, File(work, "engine")) { println("PACK $it") }
        Built(element, out, p, (System.nanoTime() - t0) / 1_000_000)
    }

    /** The region of [element], opened in the same engine folder. */
    fun region(element: File): LiveRegion {
        val before = LiveEngine.workDir
        LiveEngine.workDir = File(work, "engine")
        try {
            return PackReader.open(element).use { LiveEngine.open(it, SceneToTiles.sha256(element)) }
        } finally {
            LiveEngine.workDir = before
        }
    }

    /** [region]'s scene loaded as [LiveEngine.loadScene] loads it. */
    fun scene(region: LiveRegion, view: String? = null, tolerance: Double = 0.0): LiveScene {
        val before = LiveEngine.workDir
        LiveEngine.workDir = File(work, "engine")
        try {
            return LiveEngine.loadScene(region, view, tolerance = tolerance)
        } finally {
            LiveEngine.workDir = before
        }
    }
}
