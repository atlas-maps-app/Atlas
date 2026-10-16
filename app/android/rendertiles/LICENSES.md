# :rendertiles, licences of what it uses

- **zstd-jni** 1.5.7-20 (`com.github.luben:zstd-jni`, from Maven Central), the JNI binding through which the packer
  compresses render tiles' chunks with the reference zstd library: BSD-2-Clause, Copyright (c) 2015-present, Luben
  Karavelov. It bundles **Zstandard** 1.5.7, BSD-3-Clause (or GPL-2.0, at the user's option), Copyright (c)
  Meta Platforms, Inc. and affiliates. The core decompresses the same frames with the zstd it vendors (K0).
- The data the tiles are derived from: OpenStreetMap, © OpenStreetMap contributors, ODbL 1.0 (the pack's manifest
  carries the attribution).

Nothing is vendored into this module; it runs at build time (the packer, Gradle's bundled tiles) and in tests, and is
not part of the APK.
