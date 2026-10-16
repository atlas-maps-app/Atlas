package app.atlas.render.speed

import app.atlas.render.Amsterdam
import app.atlas.render.Oracle
import app.atlas.render.Regions
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest

/**
 * Render times on the JVM, for the port's speed work: the Westerkerk living map (Amsterdam.render, split, as LiveEngine draws it)
 * and the Main board (grachten_home through Regions). Each frame's layers are hashed, so a faster port can be checked against the
 * same run's md5s. Runs only with the environment variable ATLAS_BENCH=1 (a bench, not a test); `ATLAS_BENCH_JFR=<file>` also records
 * a JFR profile of the timed renders. `ATLAS_BENCH_ONLY=westerkerk` or `grachten` runs one of them. Run it with
 * `--no-configuration-cache`: the configuration cache keeps the test JVM's environment from an earlier run.
 */
class RenderBench {
    private val threads = ManagementFactory.getThreadMXBean()

    private fun md5(s: String?): String =
        if (s == null) "-" else MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Times [n] runs of [frame] after [warm] untimed ones: wall and thread CPU, ms; prints the medians and the frame's md5s. */
    private fun time(name: String, warm: Int, n: Int, frame: () -> List<String?>) {
        var sums: List<String>? = null
        repeat(warm) { sums = frame().map { md5(it) } }
        val wall = ArrayList<Double>(); val cpu = ArrayList<Double>()
        repeat(n) {
            val c0 = threads.currentThreadCpuTime; val t0 = System.nanoTime()
            val got = frame().map { md5(it) }
            wall.add((System.nanoTime() - t0) / 1e6); cpu.add((threads.currentThreadCpuTime - c0) / 1e6)
            check(sums == null || got == sums) { "$name: a frame differs from the warm-up's: $got vs $sums" }
            sums = got
        }
        fun med(v: List<Double>) = v.sorted()[v.size / 2]
        println("BENCH %-22s render median %7.1f ms wall, %7.1f ms cpu (min %7.1f / %7.1f; n=%d); md5 %s".format(
            name, med(wall), med(cpu), wall.min(), cpu.min(), n, sums))
    }

    @Test
    fun renders() {
        assumeTrue("a bench: set ATLAS_BENCH=1", System.getenv("ATLAS_BENCH") == "1")
        Oracle.configure()
        val only = System.getenv("ATLAS_BENCH_ONLY")
        val jfrTo = System.getenv("ATLAS_BENCH_JFR")
        if (only == null || only == "westerkerk") {
            Amsterdam.amsRaw = File(Oracle.repo, "data/ams/raw.json")
            val t0 = System.nanoTime()
            val S = Amsterdam.load()
            println("BENCH westerkerk load %.0f ms".format((System.nanoTime() - t0) / 1e6))
            shadeBuild("westerkerk", S)
            val cfg = linkedMapOf<String, Any?>("W" to 412, "H" to 915, "target" to listOf(-10, 0, -30), "yaw" to 52, "pitch" to 38, "dist" to 430,
                "fov" to 36, "cy" to 440, "radius" to 380)
            val jfr = jfrTo?.let { startJfr() }
            for (m in listOf("sunny", "night")) {
                time("westerkerk $m", 3, 9) {
                    val r = Amsterdam.render(S, m, cfg, null, layer = "split")
                    listOf(r.svg) + r.objs!!
                }
            }
            jfr?.let { stopJfr(it, File("$jfrTo.westerkerk.jfr")) }
        }
        if (only == null || only == "grachten") {
            Regions.root = File(Oracle.repo, "data/regions")
            Regions.parkRoot = File(Oracle.repo, "data/park")
            Regions.streetCfgDir = File(Oracle.repo, "cache/render")
            assumeTrue("the grachten extract is local only", File(Regions.root, "grachten/raw.json").exists())
            val t0 = System.nanoTime()
            val S = Regions.load("grachten_home")
            println("BENCH grachten_home load %.0f ms".format((System.nanoTime() - t0) / 1e6))
            val cfg = Regions.cfgFor("grachten_home")
            shadeBuild("grachten_home", S)
            val jfr = jfrTo?.let { startJfr() }
            for (m in listOf("sunny", "grey")) {
                time("grachten_home $m", 2, 5) {
                    val r = Regions.render(S, m, cfg)
                    listOf(r.g, r.o, r.o2)
                }
            }
            jfr?.let { stopJfr(it, File("$jfrTo.grachten.jfr")) }
        }
    }

    /** What building the shadows' grid costs per frame (Amsterdam.render builds one for its solids every time). */
    @Suppress("UNCHECKED_CAST")
    private fun shadeBuild(name: String, S: app.atlas.render.py.Dict) {
        val solids = (S["buildings"] as List<app.atlas.render.py.Dict>).filter { app.atlas.render.py.truthy(it["hidden"]).not() && app.atlas.render.py.truthy(it["h"]) } +
            (S["parts"] as List<app.atlas.render.py.Dict>).filter { app.atlas.render.py.truthy(it["h"]) }
        val L = app.atlas.render.V3(0.3, 0.6, 0.2)
        val ms = (0 until 7).map { val t0 = System.nanoTime(); Amsterdam.Shade(solids, L); (System.nanoTime() - t0) / 1e6 }.drop(2).sorted()
        println("BENCH %-22s shade grid of %d solids: median %.1f ms".format(name, solids.size, ms[ms.size / 2]))
    }

    private fun startJfr(): jdk.jfr.Recording {
        val r = jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"))
        r.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(2))
        r.start()
        return r
    }

    private fun stopJfr(r: jdk.jfr.Recording, to: File) {
        r.stop(); r.dump(to.toPath()); r.close()
        println("BENCH jfr written to $to")
    }
}
