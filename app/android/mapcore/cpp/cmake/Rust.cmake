# The buildings' cleaning (load/Clean.hpp), a Rust library: app/android/mapcore/rust/clean, built by cargo into the
# build directory as a static library and linked into stage P (atlas_clean). The standard library only, so cargo
# fetches nothing (--offline --locked); the Rust it builds with is the one rust/rust-toolchain.toml pins, which rustup
# picks from the crate's folder. Release settings in its Cargo.toml (opt-level s, LTO over the crate and the standard
# library, one unit, panic = abort) keep it small. A fuzz build instruments it for libFuzzer as the C++ is
# (sanitizer coverage); the other sanitizers leave it as it is (it holds no C++ and shares no memory it does not own).
#
# cargo is found where rustup puts it (~/.cargo/bin, or $CARGO_HOME/bin) when it is not on the PATH.
set(ATLAS_RUST_DIR "${CMAKE_CURRENT_LIST_DIR}/../../rust")
find_program(ATLAS_CARGO cargo HINTS "$ENV{CARGO_HOME}/bin" "$ENV{HOME}/.cargo/bin" REQUIRED)

set(_rust_crate "${ATLAS_RUST_DIR}/clean")
set(_rust_dir "${CMAKE_BINARY_DIR}/rust")
set(_rust_target "")
set(_rust_libs "")
if(ATLAS_ANDROID)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(_rust_target aarch64-linux-android)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(_rust_target x86_64-linux-android)
    else()
        message(FATAL_ERROR "No Rust target for the Android ABI '${ANDROID_ABI}'.")
    endif()
    # what the standard library links there (cargo rustc -- --print native-static-libs)
    set(_rust_libs dl log m c)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    set(_rust_libs gcc_s util rt pthread m dl c)
endif()
if(_rust_target)
    set(_rust_lib "${_rust_dir}/${_rust_target}/release/libatlas_clean.a")
    set(_rust_target_arg --target ${_rust_target})
else()
    set(_rust_lib "${_rust_dir}/release/libatlas_clean.a")
    set(_rust_target_arg "")
endif()
set(_rust_flags "")
if(ATLAS_SANITIZE STREQUAL "fuzzer")
    set(_rust_flags
        "-Cpasses=sancov-module -Cllvm-args=-sanitizer-coverage-level=4"
        " -Cllvm-args=-sanitizer-coverage-inline-8bit-counters -Cllvm-args=-sanitizer-coverage-pc-table"
        " -Cllvm-args=-sanitizer-coverage-trace-compares")
    string(JOIN "" _rust_flags ${_rust_flags})
endif()

file(GLOB_RECURSE _rust_sources CONFIGURE_DEPENDS "${_rust_crate}/src/*.rs")
add_custom_command(
    OUTPUT "${_rust_lib}"
    COMMAND "${CMAKE_COMMAND}" -E env "CARGO_TARGET_DIR=${_rust_dir}" "RUSTFLAGS=${_rust_flags}" --
            "${ATLAS_CARGO}" build --release --offline --locked --lib --quiet ${_rust_target_arg}
    DEPENDS ${_rust_sources} "${_rust_crate}/Cargo.toml" "${_rust_crate}/Cargo.lock"
            "${ATLAS_RUST_DIR}/rust-toolchain.toml"
    WORKING_DIRECTORY "${_rust_crate}"
    COMMENT "Building the buildings' cleaning (Rust)"
    VERBATIM USES_TERMINAL)
add_custom_target(atlas_clean_build DEPENDS "${_rust_lib}")
add_library(atlas_clean STATIC IMPORTED GLOBAL)
set_target_properties(atlas_clean PROPERTIES IMPORTED_LOCATION "${_rust_lib}" INTERFACE_LINK_LIBRARIES "${_rust_libs}")
add_dependencies(atlas_clean atlas_clean_build)
