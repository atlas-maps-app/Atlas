package app.atlas.mapview.vk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import app.atlas.mapcore.FrameRecords
import app.atlas.mapview.VkFactsLines
import org.junit.Test

/**
 * VK bench's plan and arithmetic (plan-vector K1): the paths, the matrix, the colour stage, the statistics, the report
 * lines and the report as recorded while the bench runs.
 */
class VkBenchTest {
    @Test
    fun thePathsMoveTheCameraAsScripted() {
        val c = VkBench.Camera()
        VkBench.cameraAt(VkBench.Path.ORBIT, VkBench.View.BOARD, 7.5, 30.0, c)
        assertEquals(18.0 + 90.0, c.bearing, 1e-9)
        assertEquals(46.0, c.pitch, 0.0)
        VkBench.cameraAt(VkBench.Path.TILT, VkBench.View.BOARD, 15.0, 30.0, c)
        assertEquals(70.0, c.pitch, 1e-9) // half way: the steepest pitch
        VkBench.cameraAt(VkBench.Path.TILT, VkBench.View.BOARD, 0.0, 30.0, c)
        assertEquals(20.0, c.pitch, 1e-9)
        VkBench.cameraAt(VkBench.Path.PINCH, VkBench.View.BOARD, 15.0, 30.0, c)
        assertEquals(6000.0, c.distance, 1e-6)
        VkBench.cameraAt(VkBench.Path.PINCH, VkBench.View.BOARD, 0.0, 30.0, c)
        assertEquals(600.0, c.distance, 1e-9)
        VkBench.cameraAt(VkBench.Path.PAN, VkBench.View.BOARD, 7.5, 30.0, c)
        assertTrue(c.latitude != VkBench.ORIGIN_LATITUDE && c.longitude != VkBench.ORIGIN_LONGITUDE)
        VkBench.cameraAt(VkBench.Path.STILL, VkBench.View.STEEP, 0.0, 30.0, c)
        assertEquals(70.0, c.pitch, 0.0)
        assertEquals(2300.0, c.distance, 0.0)
    }

    @Test
    fun theMatrixHoldsWhatTheDeviceHas() {
        // lavapipe: C and 1x, both routes, not a tiler: C has resolve and direct in the render pass, resolve dynamic.
        val lavapipe = VkBench.matrix(setOf(2, 4), dynamic = true, tiler = false).map { it.name }
        assertEquals(
            listOf(
                "C renderpass onepass resolve", "C renderpass composite resolve", "C renderpass composite direct",
                "C dynamic onepass resolve", "C dynamic composite resolve",
                "1x renderpass onepass resolve", "1x renderpass composite direct", "1x dynamic onepass resolve", "1x dynamic composite direct",
            ),
            lavapipe,
        )
        // A Mali with lazily allocated memory and 2x, a render pass only: no direct target on a tiler.
        val mali = VkBench.matrix(setOf(0, 1, 2, 4), dynamic = false, tiler = true).map { it.name }
        assertEquals(8, mali.size)
        assertFalse(mali.any { it.contains("direct") && !it.startsWith("1x") })
        assertTrue(mali.first().startsWith("A "))
    }

    @Test
    fun theStatisticsAreTheUsualOnes() {
        val v = doubleArrayOf(5.0, 1.0, 3.0, 2.0, 4.0)
        assertEquals(3.0, VkBench.percentile(v, 5, 50.0), 0.0)
        assertEquals(5.0, VkBench.percentile(v, 5, 95.0), 0.0)
        assertEquals(1.0, VkBench.percentile(v, 5, 0.0), 0.0)
        assertTrue(VkBench.percentile(v, 0, 50.0).isNaN())
        assertEquals(2.0, VkBench.slope(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(1.0, 3.0, 5.0), 3), 1e-12)
        assertTrue(VkBench.slope(doubleArrayOf(1.0), doubleArrayOf(1.0), 1).isNaN())
    }

    @Test
    fun aSummaryCountsDropsAndFragments() {
        val presented = doubleArrayOf(16.7, 16.6, 33.4, 16.7, 50.0)
        val s = VkBench.summarise(
            presented, presented.size, "display-timing",
            doubleArrayOf(8.0, 9.0, 10.0), 3, doubleArrayOf(1.0, 2.0), doubleArrayOf(0.5, 0.6), doubleArrayOf(0.2, 0.3), 2,
            doubleArrayOf(), 0, 5L, 2, 1000L, 263, 677, 1000.0 / 60.0,
        )
        assertEquals(3, s.dropped) // one at 33 ms (one vsync missed), two at 50 ms
        assertEquals(16.7, s.presentedP50, 0.0)
        assertEquals(50.0, s.presentedMax, 0.0)
        assertEquals(2.5, s.fse, 1e-12) // 5k fragments over two frames of 1,000 pixels: 2.5 full screens a frame
        assertTrue(s.hwuiGpuP50.isNaN())
        val line = VkBench.line("board C renderpass composite direct", s, 120.5, 30.25)
        assertTrue(line, line.startsWith("vkbench board C renderpass composite direct: p50 16.70 p95 50.00 max 50.00 dropped 3 gpu 9.00"))
        assertTrue(line.contains("graphics 120.5 native 30.3"))
        assertTrue(line.contains("presented-by display-timing"))
    }

    @Test
    fun theColourLineSetsTheTenBitFrameAgainstTheEightBitOne() {
        fun summary(p50: Double, p95: Double) = VkBench.summarise(
            doubleArrayOf(16.7), 1, "vsync", doubleArrayOf(p50, p50, p95), 3, doubleArrayOf(1.0), doubleArrayOf(0.5), doubleArrayOf(0.2), 1,
            doubleArrayOf(), 0, 0L, 0, 1000L, 263, 677, 1000.0 / 60.0,
        )
        val line = VkBench.colourLine("board A renderpass composite resolve", "rgb10a2", summary(4.31, 4.5), summary(4.12, 4.33))
        assertEquals("vkbench colour board A renderpass composite resolve: rgb10a2 4.31 ms, rgba8 4.12 ms GPU (p95 4.50 and 4.33; rgb10a2 +0.19 ms)", line)
        assertTrue(VkBench.colourLine("x", "bgr10a2", summary(Double.NaN, Double.NaN), summary(4.0, 4.0)).endsWith("bgr10a2 —)"))
        // The colour stage as VkFacts' colour.stage line gives it (CP/gpu/Facts.hpp's colour_stage).
        val deep = VkBench.colourStage("rgb10a2 (A2B10G10R10UnormPack32 in SrgbNonlinear: the surface offers it in sRGB and the frame's attachments follow it)")
        assertEquals("rgb10a2", deep.format)
        assertFalse(deep.off)
        assertEquals("A2B10G10R10UnormPack32 in SrgbNonlinear: the surface offers it in sRGB and the frame's attachments follow it", deep.why)
        assertEquals("bgr10a2", VkBench.colourStage("bgr10a2 (A2R10G10B10UnormPack32 in SrgbNonlinear: …)").format)
        val guard = "vk guard colour ImaginationProprietary 0x00661bd0 attempt 1 pipeline composite (module standin_composite, " +
            "configuration A renderpass composite resolve rgb10a2) build 0123456789abcdef"
        val off = VkBench.colourStage("off (10 bits are off for this driver and build: $guard)")
        assertEquals(null, off.format)
        assertTrue(off.off)
        assertEquals("10 bits are off for this driver and build: $guard", off.why)
        assertEquals("vkbench colour: skipped (10 bits are off for this driver and build: $guard)", VkBench.colourSkipped(off))
        assertEquals("a0 colour: rgba8 (10 bits are off for this driver and build: $guard)", VkBench.a0Colour(off, null, null))
        val none = VkBench.colourStage("none (the surface offers no 10-bit format)")
        assertEquals(null, none.format)
        assertFalse(none.off)
        assertEquals("vkbench colour: rgba8 only (the surface offers no 10-bit format)", VkBench.colourSkipped(none))
        assertEquals("a0 colour: rgba8 (the surface offers no 10-bit format)", VkBench.a0Colour(none, null, null))
        assertEquals(null, VkBench.colourStage("?").format) // a core without the line: no colour stage
        assertEquals(
            "a0 colour: rgb10a2 4.31 ms against rgba8 4.12 ms GPU in the picked configuration at the board's view, side by side in the colour stage",
            VkBench.a0Colour(deep, summary(4.31, 4.5), summary(4.12, 4.33)),
        )
        // The frame records say which depth each frame was drawn at (CP/jni/Bridge.hpp's frame_flag::ten_bit).
        val records = FrameRecords.allocate(2)
        records.putInt(FrameRecords.FLAGS_AT, FrameRecords.GPU or FrameRecords.TEN_BIT or (2 shl 8))
        records.putInt(FrameRecords.SIZE + FrameRecords.FLAGS_AT, FrameRecords.GPU)
        assertTrue(FrameRecords.read(records, 0).tenBit)
        assertFalse(FrameRecords.read(records, 1).tenBit)
        assertTrue(FrameRecords.read(records, 1).gpuValid)
    }

    @Test
    fun theColourStageMakesEachDepthsPipelinesOncePerConfiguration() {
        // The owner's PowerVR: A, B, C and 1x, both routes, a tiler. The colour stage measures each at 10 bits and at 8
        // beside it, the order turning with each configuration.
        val cells = VkBench.matrix(setOf(0, 1, 2, 4), dynamic = true, tiler = true)
        assertEquals(16, cells.size)
        val steps = VkBench.colourSteps(cells)
        assertEquals(2 * cells.size, steps.size)
        assertEquals(steps.size, steps.map { it.config to it.tenBit }.toSet().size) // each configuration once at each depth
        for (i in cells.indices) {
            assertEquals(setOf(true, false), setOf(steps[2 * i].tenBit, steps[2 * i + 1].tenBit))
            assertEquals(cells[i], steps[2 * i].config)
            assertEquals(cells[i], steps[2 * i + 1].config)
            if (i > 0) assertEquals(steps[2 * i - 1].tenBit, steps[2 * i].tenBit) // the depth goes on into the next one
        }
        assertTrue(steps.first().tenBit) // the stage opens at 10 bits
        // The core's work, as Native.bench makes it: a new depth remakes the swapchain and the pipelines, a new
        // configuration the pipelines. Starting at the frame's 8 bits in the picked configuration (the main runs'), no
        // configuration's pipelines are made twice at one depth, and the depth changes once a configuration.
        var depth = false
        var config: VkBench.Config? = null
        val made = mutableListOf<Pair<VkBench.Config, Boolean>>()
        var switches = 0
        for (step in steps) {
            if (step.tenBit != depth) switches++
            if (step.tenBit != depth || step.config != config) made += step.config to step.tenBit
            depth = step.tenBit
            config = step.config
        }
        assertEquals(made.size, made.toSet().size)
        assertEquals(steps.size, made.size)
        assertEquals(cells.size + 1, switches) // into 10 bits, then once a configuration
        assertTrue(VkBench.colourSteps(emptyList()).isEmpty())
    }

    @Test
    fun theColourStageRunsWhereThereAreNoTenBitsSayingSo() {
        // CI's emulator, or a phone whose surface offers no 10-bit sRGB format: the stage still runs, each configuration
        // at 8 bits alone, so its flow (the window going and coming back, the bounded waits) runs on every surface.
        fun summary(p50: Double, p95: Double) = VkBench.summarise(
            doubleArrayOf(16.7), 1, "vsync", doubleArrayOf(p50, p50, p95), 3, doubleArrayOf(1.0), doubleArrayOf(0.5), doubleArrayOf(0.2), 1,
            doubleArrayOf(), 0, 0L, 0, 1000L, 263, 677, 1000.0 / 60.0,
        )
        val none = VkBench.colourStage("none (the surface offers no 10-bit format)")
        assertEquals(
            "vkbench colour stage: rgba8 only (the surface offers no 10-bit format): no 10 bits here, so each configuration at the board's view at 8 bits alone",
            VkBench.colourStageLine(none),
        )
        val deep = VkBench.colourStage("rgb10a2 (A2B10G10R10UnormPack32 in SrgbNonlinear: the surface offers it in sRGB and the frame's attachments follow it)")
        assertTrue(VkBench.colourStageLine(deep).startsWith("vkbench colour stage: rgb10a2 (A2B10G10R10UnormPack32 in SrgbNonlinear: "))
        assertTrue(VkBench.colourStageLine(deep).endsWith("; each configuration at the board's view at 10 bits and at 8 beside it"))
        assertEquals(
            "vkbench colour board C renderpass composite resolve: rgba8 4.12 ms GPU (p95 4.33; no 10 bits here: the surface offers no 10-bit format)",
            VkBench.colourEightLine("board C renderpass composite resolve", summary(4.12, 4.33), none.why),
        )
        val cells = VkBench.matrix(setOf(2, 4), dynamic = true, tiler = false)
        val eightOnly = VkBench.colourSteps(cells, ten = false)
        assertEquals(cells, eightOnly.map { it.config })
        assertTrue(eightOnly.none { it.tenBit })
        assertEquals(VkBench.colourSteps(cells), VkBench.colourSteps(cells, ten = true))
        // Every wait for a window ends, and says why it ended without one.
        assertEquals("no surface window came within 60 s", VkBench.windowTimedOut("surface"))
        assertEquals(60, VkBench.WINDOW_WAIT_SECONDS)
    }

    @Test
    fun theColourStageSaysWhenTheAppGoesToTheBackgroundAndComesBack() {
        // The app sent to the background pauses the run (the core leaves the stage, nothing is drawn); in the colour
        // stage the run waits for it as for a window, and a step it cut short is measured again. smoke.sh reads these:
        // a stage that ends here fails, as one whose window never came back does.
        assertEquals(
            "vkbench colour: the app went to the background before board C renderpass composite resolve; the colour stage was left, and waits for the app (at most 60 s)",
            VkBench.colourAppAway("board C renderpass composite resolve"),
        )
        assertEquals("vkbench colour: the app came back", VkBench.colourAppBack())
        assertTrue(VkBench.colourAppGone().endsWith("; the colour stage ends here"))
        assertEquals(
            "vkbench colour board C renderpass composite resolve: rgb10a2 cut short (the app went to the background); measured again",
            VkBench.colourCutShort("board C renderpass composite resolve", "rgb10a2"),
        )
    }

    @Test
    fun theWholeRunWaitsForTheAppAndMeasuresAgainWhatItCutShort() {
        // Outside the colour stage the run waits for the app, however long it is away, and its lines are not the colour
        // stage's: smoke.sh tells the stage's own pause by "vkbench colour: …".
        assertEquals("vkbench: the app went to the background before the hosts; the run waits for it", VkBench.appAway("the hosts"))
        assertEquals("vkbench: the app came back", VkBench.appBack())
        assertFalse(VkBench.appAway("board C").startsWith("vkbench colour"))
        assertFalse(VkBench.appBack().startsWith("vkbench colour"))
        assertEquals(
            "vkbench board C renderpass composite resolve: cut short (the app went to the background); measured again",
            VkBench.cutShort("board C renderpass composite resolve"),
        )
        // The soak's line says how long the app was away of it, and nothing when it never went.
        assertEquals("", VkBench.soakAway(0.0))
        assertEquals("; the app was away 37 s of it (the slope takes the pause in)", VkBench.soakAway(37.2))
    }

    @Test
    fun aCellTheGuardTurnedOffIsSkippedSayingWhyAndTheRunGoesOn() {
        // A driver that crashed in the work of a cell the facts would not pick has that cell alone turned off for it and
        // this build (CP/gpu/Guard.hpp): the core refuses it with the guard's line, and the matrix says so and goes on.
        val guard = "vk guard cell ImaginationProprietary 0x00661bd0 attempt 1 B dynamic onepass resolve build 0123456789abcdef"
        val why = "${VkBench.CELL_OFF}$guard)"
        assertEquals(
            "vkbench board B dynamic onepass resolve: off (the driver crashed in it: $guard)",
            VkBench.skippedLine("board B dynamic onepass resolve", why),
        )
        // Anything else the core refuses is a cell the device cannot draw, as before.
        assertEquals(
            "vkbench pitch70 1x dynamic onepass resolve: unavailable (this device has no dynamic rendering with local reads)",
            VkBench.skippedLine("pitch70 1x dynamic onepass resolve", "this device has no dynamic rendering with local reads"),
        )
    }

    @Test
    fun theReportIsRecordedAsTheBenchRunsAndAnUnfinishedOneIsKept() {
        val lines = listOf("vkbench start: full bench, Imagination PowerVR", "vkbench board A renderpass composite resolve: p50 16.70")
        val running = VkBench.recorded(lines, "the colour stage")
        assertTrue(running.startsWith("atlas vkbench report, recorded at: the colour stage\n"))
        val (step, kept) = VkBench.unfinished(running) ?: error("an unfinished report was not found")
        assertEquals("the colour stage", step)
        assertEquals(lines, kept)
        // A finished run, or anything else, keeps nothing.
        assertEquals(null, VkBench.unfinished(VkBench.recorded(lines, VkBench.COMPLETE)))
        assertEquals(null, VkBench.unfinished(""))
        assertEquals(null, VkBench.unfinished("vkbench start: not a recorded report"))
        val section = VkBench.keptSection(step, kept)
        assertEquals("vkbench kept: the last run ended in the colour stage; the results it recorded before that follow", section.first())
        assertEquals(lines, section.subList(1, section.size - 1))
        assertEquals("vkbench kept: end", section.last())
    }

    @Test
    fun theDecisionsReadTheNumbers() {
        assertTrue(VkBench.onepassVerdict(6.5, 5.0).contains("ONEPASS is to be built"))
        assertTrue(VkBench.onepassVerdict(5.4, 5.0).contains("no ONEPASS"))
        assertTrue(VkBench.onepassVerdict(Double.NaN, 5.0).startsWith("unknown"))
        assertTrue(VkBench.onTime(1_000_000_000L, 1_004_000_000L, 16_666_667L))
        assertFalse(VkBench.onTime(1_000_000_000L, 1_016_000_000L, 16_666_667L))
        assertFalse(VkBench.onTime(1_000_000_000L, 0L, 16_666_667L))
        val soak = VkBench.soakLine(doubleArrayOf(0.0, 5.0, 7.5, 10.0), doubleArrayOf(0.3, 0.40, 0.41, 0.42), 4, 50.0, 300.0, false, 16.7)
        assertTrue(soak, soak.startsWith("vkbench soak: thermal slope 0.0040/min"))
        assertTrue(soak.contains("charge 50.0 mAh"))
        assertTrue(VkBench.soakLine(doubleArrayOf(0.0), doubleArrayOf(0.3), 1, 0.0, Double.NaN, true, 16.7).contains("plugged in"))
    }

    @Test
    fun theDeqpLevelReadsAsADate() {
        assertEquals("2024-03-01", VkFactsLines.dEqpDate((2024 shl 16) or (3 shl 8) or 1))
        assertEquals("2022-03-01", VkFactsLines.dEqpDate(0x07E60301))
    }

    @Test
    fun theGuardsLinesLeadTheFacts() {
        val report = listOf(
            "atlas.vkfacts: 1",
            "gpu: PowerVR",
            "guard: vk guard pipelines ImaginationProprietary 0x00661bd0 attempt 1 pipeline composite (module standin_composite, " +
                "configuration A renderpass composite resolve) build 0123456789abcdef",
            "guard.build: fedcba9876543210",
            "guard.driver: ImaginationProprietary 0x00661bd0",
            "guard.off: none",
            "guarded: not a guard line",
            "core.build: atlasmap 0.1.0",
        )
        val guard = VkFactsLines.guard(report)
        assertEquals(4, guard.size)
        assertTrue(guard[0].contains("pipeline composite (module standin_composite"))
        assertEquals("guard.off: none", guard.last())
        assertTrue(VkFactsLines.guard(listOf("gpu: x")).isEmpty())
    }

    @Test
    fun thePillsMoveWithinTheirStripAndRestEachCycle() {
        // A second's move of 80 dp at an even pace, then a second at rest; back the other way in the next cycle.
        assertEquals(-40f, pillShift(0.0), 1e-6f)
        assertEquals(0f, pillShift(0.5), 1e-4f)
        assertEquals(40f, pillShift(1.0), 1e-4f)
        assertEquals(40f, pillShift(1.7), 1e-4f)
        assertEquals(0f, pillShift(2.5), 1e-4f)
        assertEquals(-40f, pillShift(3.5), 1e-4f)
        assertEquals(-20f, pillShift(4.25), 1e-4f)
        assertTrue(pillMoving(0.5))
        assertFalse(pillMoving(1.5))
        assertTrue(pillMoving(2.2))
        assertFalse(pillMoving(3.9))
        // A core frame and the window's share their frame when the window drew that vsync, on time.
        val vsyncs = longArrayOf(0L, 16_666_667L, 33_333_333L)
        val late = booleanArrayOf(false, true, false)
        assertTrue(VkBench.drawnOnTime(vsyncs, late, 1_000_000L))
        assertFalse(VkBench.drawnOnTime(vsyncs, late, 16_666_667L))
        assertFalse(VkBench.drawnOnTime(vsyncs, late, 50_000_000L))
        val boxes = pillBoxes(412f, 915f, 10f)
        assertEquals(2, boxes.size)
        assertEquals(26f, boxes[0][0], 0f)
        assertEquals(915f - 48f, boxes[1][3], 0f)
    }
}
