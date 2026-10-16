#!/usr/bin/env python3
"""perfsplit.py <samples.txt> [out.txt] | --selftest: splits a simpleperf profile of Atlas's first home frame into its parts.

Input is the text `simpleperf report-sample --show-callchain` prints on the device: one "sample:" block per sample, with
the leaf's "file:" and "symbol:" lines and then, under "callchain:", every caller (innermost first).

Each sample gets two labels.
  category  the first match walking from the innermost frame outwards:
              turbulence  the engine's Turbulence (feTurbulence noise)
              filter      the other SVG filter primitives (SvgFilterRaster, SvgShaderFilters)
              skia        the platform's native drawing (libhwui, which holds Skia, text shaping, codecs)
              other       anything else (Kotlin glue, the runtime, the kernel)
  stage     the outermost entry point of a frame's stage in the chain: EngineFrame.rasterise (raster), SvgParser.parse
            (parse), a region's load (load) or render (render). LiveEngine.render wraps both load and render, so it
            decides nothing by itself. Without an entry point: the package of any Atlas frame, else the thread.

The RASTER split counts the raster stage, every turbulence and filter sample, and the native drawing on the engine's own
threads ("atlas-engine" and the like) whose Java frames the unwinder could not name. Skia on RenderThread is the UI's
drawing, not the engine's raster, and is left out. When the profile has no managed frames at all the report says so: the
leaf-dso and thread tables still split native from managed code.
"""
import re
import sys
from collections import Counter

SKIA_DSO = re.compile(r"libhwui|libskia|libminikin|libandroid_runtime|libjnigraphics|libharfbuzz|libicu|libft2|libpng|libjpeg|libwebp|libgui")
TURB = re.compile(r"\bTurbulence\b")
FILTER = re.compile(r"\bSvgFilterRaster\b|\bSvgShaderFilters\b")
ENTRIES = (
    ("raster", re.compile(r"\bEngineFrame\b\W*(Companion\W*)?rasterise\b")),
    ("parse", re.compile(r"\bSvgParser\W+parse\b")),
    ("load", re.compile(r"\b(Regions|Amsterdam|Generic|Real|City|Living|LiveEngine)\W+load\w*\b|\bRegionFiles\b|\bLiveRegions\b|\bPackReader\b|\bPackLibrary\b")),
    ("render", re.compile(r"\b(Regions|Amsterdam|Generic|Real|City|Living)\W+render\w*\b")),
)
PARSE_PKG = re.compile(r"app\.atlas\.mapview\.svg\.(SvgParser|SvgXml|SvgNumbers|SvgValues)\b")
RASTER_PKG = re.compile(r"app\.atlas\.mapview\.svg\.|app\.atlas\.mapview\.EngineFrame\b")
RENDER_PKG = re.compile(r"app\.atlas\.render\.")
ATLAS = re.compile(r"app\.atlas\.|app/atlas/")
ENGINE_THREAD = re.compile(r"^atlas-(?!map-data|shortcuts|engine-ti)")
MANAGED_DSO = re.compile(r"\.(odex|vdex|oat|art|jar|apk|dex)\b|\[anon:dalvik|\[JIT|jit-cache|jit-zygote-cache|boot[-.\w]*\.(oat|art)")


def parse(lines):
    """Yields (thread, [(file, symbol), ...]) per sample, innermost frame first."""
    thread, frames, file = None, None, None
    for raw in lines:
        line = raw.strip()
        if line == "sample:":
            if frames is not None:
                yield thread, frames
            thread, frames, file = None, [], None
            continue
        if frames is None:
            continue
        if line.startswith("thread_name:"):
            thread = line[len("thread_name:"):].strip()
        elif line.startswith("file:"):
            file = line[len("file:"):].strip()
        elif line.startswith("symbol:"):
            frames.append((file or "", line[len("symbol:"):].strip()))
            file = None
    if frames is not None:
        yield thread, frames


def category(frames):
    for f, s in frames:
        if TURB.search(s):
            return "turbulence"
        if FILTER.search(s):
            return "filter"
        if SKIA_DSO.search(f):
            return "skia"
    return "other"


def engine_thread(thread):
    return bool(thread) and bool(ENGINE_THREAD.search(thread))


def stage(thread, frames):
    for _, s in reversed(frames):
        for name, entry in ENTRIES:
            if entry.search(s):
                return name
    syms = [s for _, s in frames]
    if any(PARSE_PKG.search(s) for s in syms):
        return "parse"
    if any(RASTER_PKG.search(s) for s in syms):
        return "raster"
    if any(RENDER_PKG.search(s) for s in syms):
        return "render"
    if any(ATLAS.search(s) for s in syms):
        return "atlas, other"
    if engine_thread(thread):
        return "engine thread, unresolved"
    if thread == "RenderThread":
        return "UI drawing (RenderThread)"
    return "no Atlas frame"


def in_raster(thread, frames, cat, stg):
    if stg == "raster" or cat in ("turbulence", "filter"):
        return True
    return stg == "engine thread, unresolved" and cat == "skia"


def pct(n, total):
    return f"{100.0 * n / total:5.1f}%" if total else "  n/a "


def report(samples):
    total = len(samples)
    out = [f"samples: {total}"]
    if not total:
        out.append("no samples: the profile is empty (simpleperf did not run, or the process had no CPU time)")
        return out
    named = sum(1 for _, fr in samples if any(ATLAS.search(s) for _, s in fr))
    out.append(f"samples with an Atlas managed frame: {named} ({pct(named, total).strip()})"
               + ("" if named else "  <- none: Java and Kotlin frames were not resolved; read the dso and thread tables"))
    cats, stages, raster = Counter(), Counter(), Counter()
    for thread, fr in samples:
        c = category(fr)
        s = stage(thread, fr)
        cats[c] += 1
        stages[s] += 1
        if in_raster(thread, fr, c, s):
            raster[c] += 1
    r = sum(raster.values())
    out.append("")
    out.append(f"RASTER split ({r} samples, {pct(r, total).strip()} of all):")
    for c, label in (("skia", "Skia (native drawing)"), ("filter", "filter primitives"), ("turbulence", "Turbulence"),
                     ("other", "raster glue (Kotlin, runtime, GC)")):
        out.append(f"  {label:42s} {raster[c]:6d} {pct(raster[c], r)}")
    out.append("")
    out.append("STAGES (outermost entry point):")
    for s, n in stages.most_common():
        out.append(f"  {s:42s} {n:6d} {pct(n, total)}")
    out.append("")
    out.append("CATEGORIES (innermost match, all samples):")
    for c, n in cats.most_common():
        out.append(f"  {c:42s} {n:6d} {pct(n, total)}")
    dso = Counter(fr[0][0] if fr else "?" for _, fr in samples)
    managed = sum(n for d, n in dso.items() if MANAGED_DSO.search(d))
    out.append("")
    out.append(f"BY LEAF DSO (managed code, compiled or JIT: {managed}, {pct(managed, total).strip()}):")
    for d, n in dso.most_common(14):
        out.append(f"  {n:6d} {pct(n, total)} {d}")
    sym = Counter((fr[0][1] if fr else "?") for _, fr in samples)
    out.append("")
    out.append("TOP LEAF SYMBOLS:")
    for s, n in sym.most_common(25):
        out.append(f"  {n:6d} {pct(n, total)} {s[:140]}")
    th = Counter(t or "?" for t, _ in samples)
    out.append("")
    out.append("BY THREAD:")
    for t, n in th.most_common(10):
        out.append(f"  {n:6d} {pct(n, total)} {t}")
    return out


SELFTEST = """\
sample:
  event_type: cpu-clock
  time: 1
  event_count: 4000000
  thread_id: 10
  thread_name: atlas-engine
  vaddr_in_file: 1a
  file: /data/app/x/oat/x86_64/base.odex
  symbol: float app.atlas.mapview.svg.Turbulence.noise2(int, float, float)
  callchain:
    vaddr_in_file: 1b
    file: /data/app/x/oat/x86_64/base.odex
    symbol: void app.atlas.mapview.svg.SvgFilterRaster.turbulence(int[])
    vaddr_in_file: 1c
    file: /data/app/x/oat/x86_64/base.odex
    symbol: android.graphics.Bitmap app.atlas.mapview.EngineFrame$Companion.rasterise(java.util.List, float)
sample:
  thread_name: atlas-engine
  file: /system/lib64/libhwui.so
  symbol: SkScan::AntiFillPath(SkPath const&, SkRegion const&, SkBlitter*, bool)
  callchain:
    file: /data/app/x/oat/x86_64/base.odex
    symbol: void app.atlas.mapview.svg.SvgCanvas.drawShape(app.atlas.mapview.svg.SvgShape)
    file: /data/app/x/oat/x86_64/base.odex
    symbol: android.graphics.Bitmap app.atlas.mapview.EngineFrame$Companion.rasterise(java.util.List, float)
sample:
  thread_name: atlas-engine
  file: /data/app/x/oat/x86_64/base.odex
  symbol: void app.atlas.mapview.svg.SvgXml.attribute()
  callchain:
    file: /data/app/x/oat/x86_64/base.odex
    symbol: app.atlas.mapview.svg.SvgDocument app.atlas.mapview.svg.SvgParser.parse(java.lang.String)
sample:
  thread_name: atlas-engine
  file: /data/app/x/oat/x86_64/base.odex
  symbol: java.lang.Object app.atlas.render.py.PyJson.value()
  callchain:
    file: /data/app/x/oat/x86_64/base.odex
    symbol: app.atlas.render.py.Dict app.atlas.render.Regions.load(java.lang.String)
    file: /data/app/x/oat/x86_64/base.odex
    symbol: app.atlas.render.live.LiveFrame app.atlas.render.live.LiveEngine.render(app.atlas.render.live.LiveRegion)
sample:
  thread_name: atlas-engine
  file: /data/app/x/oat/x86_64/base.odex
  symbol: void app.atlas.render.py.PyFmt.appendFixed(java.lang.StringBuilder, double)
  callchain:
    file: /data/app/x/oat/x86_64/base.odex
    symbol: app.atlas.render.Amsterdam$Result app.atlas.render.Amsterdam.render(app.atlas.render.py.Dict)
    file: /data/app/x/oat/x86_64/base.odex
    symbol: app.atlas.render.live.LiveFrame app.atlas.render.live.LiveEngine.render(app.atlas.render.live.LiveRegion)
sample:
  thread_name: atlas-engine
  file: /system/lib64/libhwui.so
  symbol: SkRasterPipeline::run(unsigned long, unsigned long, unsigned long, unsigned long) const
sample:
  thread_name: RenderThread
  file: /system/lib64/libhwui.so
  symbol: android::uirenderer::renderthread::CanvasContext::draw()
"""


def selftest():
    samples = list(parse(SELFTEST.splitlines()))
    got = [(category(fr), stage(t, fr), in_raster(t, fr, category(fr), stage(t, fr))) for t, fr in samples]
    want = [
        ("turbulence", "raster", True),
        ("skia", "raster", True),
        ("other", "parse", False),
        ("other", "load", False),
        ("other", "render", False),
        ("skia", "engine thread, unresolved", True),
        ("skia", "UI drawing (RenderThread)", False),
    ]
    assert len(samples[0][1]) == 3, samples[0]
    assert got == want, "\n".join(f"{g} != {w}" for g, w in zip(got, want) if g != w)
    text = "\n".join(report(samples))
    assert "RASTER split (3 samples" in text, text
    print("perfsplit selftest: ok")
    return 0


def main(argv):
    if len(argv) >= 2 and argv[1] == "--selftest":
        return selftest()
    if len(argv) < 2:
        print(__doc__)
        return 2
    with open(argv[1], errors="replace") as f:
        samples = list(parse(f))
    lines = report(samples)
    text = "\n".join(lines) + "\n"
    if len(argv) > 2:
        with open(argv[2], "w") as f:
            f.write(text)
    sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
