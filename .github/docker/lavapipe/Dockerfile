# lavapipe (Mesa's software Vulkan), the Vulkan loader and the Khronos validation layer, built from tagged releases for
# the core's host tests on CI (plan-vector T10). A distribution keeps only the newest Mesa in each pocket, so an apt
# pin breaks; instead this image is built once from these tags, on a runner of each architecture, and
# .github/workflows/core.yml pins it by digest. Rebuilding it is a reviewed change, with the likeness numbers before and
# after (lavapipe's frames change with Mesa).
#
# The arm64 runner takes everything from it; the x86_64 runner takes lavapipe from the Android emulator package (the
# CI emulator's own) and only what that package lacks from here. Files land in /opt/vulkan, where core.yml copies them.
# ubuntu:24.04's multi-architecture index as of 17 October 2026 (registry-1.docker.io), so a rebuild starts from the
# same system; its apt packages still come from the archive's current pockets.
FROM ubuntu:24.04@sha256:534baea6a22c03a63003dbc8dbe78fe34bc0d7e595d9a9dc9834884ff530eb55 AS build

ARG MESA_VERSION=26.2.5
# From Mesa's release notes (docs.mesa3d.org/relnotes/26.2.5.html).
ARG MESA_SHA256=794b3d1729075dae7ebd47c7283b7ca0cdb5dec668f7f11cc0a5d82f6138a0a5
# The Vulkan SDK release of the core's vendored Vulkan-Headers (app/android/mapcore/cpp/third_party/VERSIONS.md), and
# the commits its tag names in each repository: a clone that lands elsewhere stops the build.
ARG VULKAN_SDK_TAG=vulkan-sdk-1.4.363.0
ARG HEADERS_COMMIT=6802bb4733b63ed5efd3adb308a6c885ef180ea1
ARG LOADER_COMMIT=fa218055ff4948b159f31d43f800c808a13d51df
ARG LAYERS_COMMIT=15c64e73db67e27ff0215708fbe48684a35c6827
# Meson 1.8.2 from PyPI, its wheel and its source archive.
ARG MESON_SHA256_WHEEL=274b49dbe26e00c9a591442dd30f4ae9da8ce11ce53d0f4682cd10a45d50f6fd
ARG MESON_SHA256_SDIST=c105816d8158c76b72adcb9ff60297719096da7d07f6b1f000fd8c013cd387af

# Besides the compilers and Mesa's generators:
# - libdrm-dev: Mesa requires libdrm (>= 2.4.109 for llvmpipe and lavapipe) whenever it builds a Vulkan driver on Linux,
#   for VK_KHR_display, even with no window system (meson.build: with_dri2, then dependency('libdrm'));
# - libx11-dev, libxcb1-dev, libxrandr-dev, libwayland-dev: the validation layer's Linux build turns on every window
#   system by default and requires their pkg-config files and headers. The layer links none of them (only its
#   DEBUG_CAPTURE_KEYBOARD option would), so the image gains no run-time dependency. Khronos's own CI builds the layer
#   this way (apt-get install libwayland-dev xorg-dev); switching the window systems off is a configuration it never
#   builds.
# Checked against Ubuntu noble's indexes (main and universe, with updates and security) for amd64 and arm64 on 17
# October 2026: every package and dependency resolves, and libdrm-dev is 2.4.125.
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      bison build-essential ca-certificates cmake curl flex git glslang-tools libdrm-dev libelf-dev libexpat1-dev \
      libwayland-dev libx11-dev libxcb1-dev libxrandr-dev libzstd-dev llvm-18-dev ninja-build pkg-config python3 \
      python3-mako python3-packaging python3-pip python3-venv python3-yaml xz-utils zlib1g-dev \
 && rm -rf /var/lib/apt/lists/*
# Mesa 26 needs a newer Meson than Ubuntu 24.04's.
RUN python3 -m venv /opt/meson \
 && printf 'meson==1.8.2 --hash=sha256:%s --hash=sha256:%s\n' "${MESON_SHA256_WHEEL}" "${MESON_SHA256_SDIST}" \
      > /tmp/meson.txt \
 && /opt/meson/bin/pip install --no-cache-dir --require-hashes --no-deps -r /tmp/meson.txt

# lavapipe alone: no GL, no window systems, LLVM linked in statically so the runner needs no libLLVM.
RUN curl -fsSLo /tmp/mesa.tar.xz "https://archive.mesa3d.org/mesa-${MESA_VERSION}.tar.xz" \
 && echo "${MESA_SHA256}  /tmp/mesa.tar.xz" | sha256sum -c - \
 && mkdir -p /src && tar -xJf /tmp/mesa.tar.xz -C /src \
 && cd "/src/mesa-${MESA_VERSION}" \
 && /opt/meson/bin/meson setup build --prefix=/opt/vulkan --libdir=lib --buildtype=release \
      -Dvulkan-drivers=swrast -Dgallium-drivers=llvmpipe -Dplatforms= -Dglx=disabled -Degl=disabled -Dgbm=disabled \
      -Dopengl=false -Dgles1=disabled -Dgles2=disabled -Dllvm=enabled -Dshared-llvm=disabled \
      -Dvalgrind=disabled -Dlibunwind=disabled -Dzstd=enabled \
 && ninja -C build install \
 && cp -L "/usr/lib/$(uname -m)-linux-gnu/libdrm.so.2" /opt/vulkan/lib/
# lavapipe links libdrm (for VK_KHR_display), which a CI runner need not have, so it travels with lavapipe.

# The loader and the validation layer from the same Vulkan SDK release as the headers, in their default configurations
# (every window system on, from the headers installed above; neither links them).
RUN git clone --depth 1 --branch "${VULKAN_SDK_TAG}" https://github.com/KhronosGroup/Vulkan-Headers /src/headers \
 && test "$(git -C /src/headers rev-parse HEAD)" = "${HEADERS_COMMIT}" \
 && cmake -S /src/headers -B /src/headers/build -G Ninja -DCMAKE_INSTALL_PREFIX=/opt/vulkan \
 && cmake --install /src/headers/build
RUN git clone --depth 1 --branch "${VULKAN_SDK_TAG}" https://github.com/KhronosGroup/Vulkan-Loader /src/loader \
 && test "$(git -C /src/loader rev-parse HEAD)" = "${LOADER_COMMIT}" \
 && cmake -S /src/loader -B /src/loader/build -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/opt/vulkan \
      -DVULKAN_HEADERS_INSTALL_DIR=/opt/vulkan -DBUILD_TESTS=OFF \
 && cmake --build /src/loader/build && cmake --install /src/loader/build
RUN git clone --depth 1 --branch "${VULKAN_SDK_TAG}" https://github.com/KhronosGroup/Vulkan-ValidationLayers /src/vvl \
 && test "$(git -C /src/vvl rev-parse HEAD)" = "${LAYERS_COMMIT}" \
 && cd /src/vvl \
 && python3 scripts/update_deps.py --dir external --config release --generator Ninja \
 && cmake -S . -B build -G Ninja -C external/helper.cmake -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/opt/vulkan \
      -DBUILD_WERROR=OFF -DBUILD_TESTS=OFF \
 && cmake --build build && cmake --install build

# What was built and what it needs from the system, for the record (core.yml prints it and checks the libraries).
RUN { echo "mesa ${MESA_VERSION} (lavapipe)"; echo "vulkan ${VULKAN_SDK_TAG} (loader, validation layer)"; uname -m; \
      for f in /opt/vulkan/lib/libvulkan_lvp.so /opt/vulkan/lib/libvulkan.so.1 \
               /opt/vulkan/lib/libVkLayer_khronos_validation.so; do echo "$f:"; LD_LIBRARY_PATH=/opt/vulkan/lib ldd "$f"; done; \
    } > /opt/vulkan/VERSIONS.txt

FROM scratch
COPY --from=build /opt/vulkan /opt/vulkan
