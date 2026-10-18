# Third-party material in the map core (libatlasmap.so)

What the core compiles into the library the APK ships, from `third_party/` (`third_party/VERSIONS.md` names each version, archive and SHA-256) or ported from published work. The app's licences screen lists these rows (`app/src/main/kotlin/app/atlas/ui/mine/art/mine_licences.py` reads this file). doctest and nanobench build the tests and benchmarks only, and the Khronos validation layer is packaged in debug builds only.

| What | Where | Licence | Credit |
| --- | --- | --- | --- |
| HarfBuzz 14.6.0: shapes the engine's text and draws its glyph outlines, as Android's own text stack does (one change, `third_party/VERSIONS.md`) | `third_party/harfbuzz/` | "Old MIT" (`third_party/harfbuzz/COPYING`); `src/ms-use/` MIT (Microsoft) | HarfBuzz contributors |
| The Slug algorithm (Eric Lengyel): the glyphs' coverage from their outlines, computed in the core's own Slang shaders (`shaders/text/`) | `shaders/text/slug.slang`, `text/SlugBands.cpp` | Patent dedicated to the public domain; the reference shaders MIT and Apache-2.0, which ask for this credit | "Slug algorithm by Eric Lengyel" |
| Vulkan-Headers with Vulkan-Hpp (Khronos) | `third_party/vulkan-headers/` | Apache-2.0 OR MIT | The Khronos Group |
| Vulkan Memory Allocator 3.4.0 (AMD) | `third_party/vma/` | MIT | Advanced Micro Devices |
| Vulkan Profiles library (Khronos) | `third_party/vulkan-profiles/` | Apache-2.0 | The Khronos Group |
| zstd 1.5.7, decompression only (Meta) | `third_party/zstd/` | BSD-3-Clause | Meta Platforms |
| earcut.hpp 3.2.4 (Mapbox) | `third_party/earcut/` | ISC | Mapbox |
| fdlibm 5.3 (Sun Microsystems), ported in `base/pymath/fdlibm/` | `third_party/fdlibm/` | "Permission to use, copy, modify, and distribute this software is freely granted, provided that this notice is preserved" | Sun Microsystems |
