#!/usr/bin/env python3
"""The Globe board's backdrop for the core (ENGINE.md step 5, "The globe"): the board's stars behind the Earth, from
the app's own data (app/src/main/res/raw/local_globe.json, its "far": x, y, radius and opacity in the board's
412 x 915 frame, white), written as the table the world's shaders draw them from
(mapcore/cpp/shaders/shared/backdrop.slang). Run from app/android after the board's data changes;
:mapcorecheck's BackdropTableTest holds the table to the data."""
import json
import pathlib

here = pathlib.Path(__file__).resolve().parent.parent
far = json.loads((here / "app/src/main/res/raw/local_globe.json").read_text())["far"]
lines = [
    "// The Globe board's backdrop stars (ENGINE.md step 5, \"The globe\"): written by ci/backdrop.py from the app's",
    "// local_globe.json (its \"far\"); :mapcorecheck's BackdropTableTest holds it to that data. Each star's place in the",
    "// board's 412 x 915 frame (x, y), its radius (board px) and its opacity; all white.",
    "#pragma once",
    "",
    f"static const uint BACKDROP_STARS = {len(far)}u;",
    f"static const float4 backdrop_stars[{len(far)}] = {{",
]
for x, y, r, o in far:
    lines.append(f"    float4({x:.1f}f, {y:.1f}f, {r:.2f}f, {o:.2f}f),")
lines.append("};")
(here / "mapcore/cpp/shaders/shared/backdrop.slang").write_text("\n".join(lines) + "\n")
print(f"{len(far)} stars written")
