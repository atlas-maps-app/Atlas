# Third-party material in the map core (libatlasmap.so)

What the core compiles into the library the APK ships, from `third_party/` (`third_party/VERSIONS.md` names each version, archive and SHA-256) or ported from published work. The app's licences screen lists these rows (`app/src/main/kotlin/app/atlas/ui/mine/art/mine_licences.py` reads this file). doctest and nanobench build the tests and benchmarks only, and the Khronos validation layer is packaged in debug builds only.

| What | Credit | Licence | Where | Notes |
| --- | --- | --- | --- | --- |
| HarfBuzz | HarfBuzz contributors; Microsoft | Old MIT License and MIT License | `third_party/harfbuzz/` | 14.6.0: shapes the engine's text and draws its glyph outlines, as Android's own text stack does (one change, `third_party/VERSIONS.md`); "Old MIT" in `third_party/harfbuzz/COPYING`, Microsoft's MIT in `src/ms-use/` |
| Slug | "Slug algorithm by Eric Lengyel" | MIT License and Apache License 2.0 | `shaders/text/slug.slang`, `text/SlugBands.cpp` | the glyphs' coverage from their outlines, computed in the core's own Slang shaders (`shaders/text/`); the algorithm's patent is dedicated to the public domain, and the reference shaders' licences ask for this credit |
| Vulkan-Headers and Vulkan-Hpp | The Khronos Group | Apache License 2.0 or MIT License | `third_party/vulkan-headers/` | |
| Vulkan Memory Allocator | Advanced Micro Devices | MIT License | `third_party/vma/` | 3.4.0 |
| Vulkan Profiles | The Khronos Group | Apache License 2.0 | `third_party/vulkan-profiles/` | the Vulkan Profiles library |
| Zstandard | Meta Platforms | BSD 3-Clause License | `third_party/zstd/` | zstd 1.5.7, decompression only |
| Earcut | Mapbox | ISC License | `third_party/earcut/` | earcut.hpp 3.2.4 |
| fdlibm | Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved. Developed at SunSoft, a Sun Microsystems, Inc. business. Permission to use, copy, modify, and distribute this software is freely granted, provided that this notice is preserved. | SunPro License | `third_party/fdlibm/` | fdlibm 5.3, ported in `base/pymath/fdlibm/` |
