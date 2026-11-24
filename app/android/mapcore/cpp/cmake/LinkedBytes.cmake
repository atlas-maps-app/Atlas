# cmake -DMAP=<map> -DRUST_DIR=<dir> -DWHAT=<words> -DOUT=<file> -P LinkedBytes.cmake
#
# The bytes each Rust archive takes in a library linked from it, from lld's map of the link (-Wl,-Map=<map>), which
# lists every input section kept, with its size and the object it came from. The Rust archives are those under
# RUST_DIR, where cargo builds every crate (cmake/Rust.cmake: ATLAS_RUST_DIR), so a crate added is counted with no
# word here or in the caller. Counted: the sections a phone loads (code, read-only data, data, unwind tables). Left
# out: the debug sections, which the APK's library is stripped of, and .bss/.tbss, which take no bytes in the file.
# Prints one line an archive, then one of the map's own cost (its bytes, and the milliseconds this read and count of it
# took), and writes them to OUT (CI's summary reads it). Fails where the map lists nothing of one.
#
# The core's Android builds run it after libatlasmap.so links (cpp/CMakeLists.txt): the cost the Rust trial measured
# once by hand (+192 KB on arm64), now printed by every build.
foreach(_var MAP RUST_DIR WHAT OUT)
    if(NOT DEFINED ${_var})
        message(FATAL_ERROR "LinkedBytes.cmake: -D${_var}=… is missing")
    endif()
endforeach()
string(TIMESTAMP _began "%s%f" UTC) # microseconds
# an input section's line: VMA, LMA, size, align (hex but the align), then <path>/<archive>(<member>):(<section>)
file(STRINGS "${MAP}" _lines REGEX "\\.a\\([^)]*\\):\\(")
set(_archives "")
foreach(_line IN LISTS _lines)
    string(FIND "${_line}" "${RUST_DIR}/" _at)
    if(_at LESS 0 OR NOT _line MATCHES
       "^ *[0-9a-f]+ +[0-9a-f]+ +([0-9a-f]+) +[0-9]+ .*/([^/(]+\\.a)\\([^)]*\\):\\(([^)]*)\\)$")
        continue()
    endif()
    set(_archive "${CMAKE_MATCH_2}")
    set(_section "${CMAKE_MATCH_3}")
    math(EXPR _size "0x${CMAKE_MATCH_1}")
    if(NOT _archive IN_LIST _archives)
        list(APPEND _archives "${_archive}")
        set("_code_${_archive}" 0)
        set("_rest_${_archive}" 0)
    endif()
    if(_section MATCHES "^\\.(debug|bss|tbss|comment)")
        continue()
    elseif(_section MATCHES "^\\.text")
        math(EXPR "_code_${_archive}" "${_code_${_archive}} + ${_size}")
    else()
        math(EXPR "_rest_${_archive}" "${_rest_${_archive}} + ${_size}")
    endif()
endforeach()
if(NOT _archives)
    message(FATAL_ERROR "LinkedBytes.cmake: ${MAP} lists nothing of an archive under ${RUST_DIR}")
endif()
list(SORT _archives)
set(_said "")
foreach(_archive IN LISTS _archives)
    math(EXPR _all "${_code_${_archive}} + ${_rest_${_archive}}")
    if(_all EQUAL 0)
        message(FATAL_ERROR "LinkedBytes.cmake: ${MAP} lists no section of ${_archive} that a phone loads")
    endif()
    string(CONCAT _line "Rust: ${_archive} takes ${_all} bytes of ${WHAT}: code ${_code_${_archive}}, data and unwind "
        "tables ${_rest_${_archive}}")
    message(STATUS "${_line}")
    string(APPEND _said "${_line}\n")
endforeach()
string(TIMESTAMP _ended "%s%f" UTC)
math(EXPR _ms "(${_ended} - ${_began}) / 1000")
file(SIZE "${MAP}" _map_bytes)
set(_line "Rust: lld's map of ${WHAT}: ${_map_bytes} bytes, read and counted in ${_ms} ms")
message(STATUS "${_line}")
file(WRITE "${OUT}" "${_said}${_line}\n")
