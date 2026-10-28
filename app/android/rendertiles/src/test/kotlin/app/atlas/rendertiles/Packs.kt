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
 * were derived from; and the regions with a building tradition, whose element packs the Mac keeps beside it.
 */
object Packs {
    val repo: File = File(System.getProperty("atlas.repo") ?: "../../..").canonicalFile
    val westerkerkPack = File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas")
    val grachtenPack = File(repo, "cache/packs/regions/grachten.atlas")

    /** A region's element pack in the repo's cache (the Mac's alone). */
    fun regionPack(id: String) = File(repo, "cache/packs/regions/$id.atlas")

    val work: File by lazy { Files.createTempDirectory("rtiles").toFile().also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() }) } }

    class Built(val element: File, val file: File, val packed: SceneToTiles.Companion.Packed, val millis: Long) {
        val written: Format3Writer.Written get() = packed.written
        val reader: Format3Reader by lazy { Format3Reader(file) }
    }

    private val built = HashMap<String, Built>()

    /**
     * The render pack of [element], built once; without [survey], its height survey left out as the packer's tests leave
     * it out of a region the tiles cannot carry with it (SceneToTiles.withoutSurvey).
     */
    @Synchronized
    fun build(element: File, name: String = element.nameWithoutExtension, survey: Boolean = true): Built = built.getOrPut(name) {
        val out = File(work, "$name.atlas")
        val t0 = System.nanoTime()
        val p = SceneToTiles.pack(element, out, File(work, "engine"), survey = survey) { println("PACK $it") }
        Built(element, out, p, (System.nanoTime() - t0) / 1_000_000)
    }

    /** The region of [element], opened in the same engine folder, as [build] opens it. */
    fun region(element: File, survey: Boolean = true): LiveRegion {
        val before = LiveEngine.workDir
        LiveEngine.workDir = File(work, "engine")
        try {
            val source = SceneToTiles.sha256(element)
            return PackReader.open(element).use {
                if (survey) LiveEngine.open(it, source) else LiveEngine.open(SceneToTiles.withoutSurvey(it), "$source without its survey")
            }
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
