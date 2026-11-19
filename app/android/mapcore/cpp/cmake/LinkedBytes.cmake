# cmake -DMAP=<map> -DARCHIVE=<name> -DWHAT=<words> -DOUT=<file> -P LinkedBytes.cmake
#
# The bytes an archive's members take in a library linked from it, from lld's map of the link (-Wl,-Map=<map>), which
# lists every input section kept, with its size and the object it came from. Counted: the sections a phone loads
# (code, read-only data, data, unwind tables). Left out: the debug sections, which the APK's library is stripped of,
# and .bss/.tbss, which take no bytes in the file. Prints one line, and writes it to OUT (CI's summary reads it).
#
# The core's Android builds run it after libatlasmap.so links, for its Rust library (cpp/CMakeLists.txt): the cost the
# Rust trial measured once by hand (+192 KB on arm64), now printed by every build.
foreach(_var MAP ARCHIVE WHAT OUT)
    if(NOT DEFINED ${_var})
        message(FATAL_ERROR "LinkedBytes.cmake: -D${_var}=… is missing")
    endif()
endforeach()
string(REPLACE "." "\\." _archive "${ARCHIVE}")
# an input section's line: VMA, LMA, size, align (hex but the align), then <path>/<archive>(<member>):(<section>)
file(STRINGS "${MAP}" _lines REGEX "/${_archive}\\([^)]*\\):\\(")
set(_code 0)
set(_rest 0)
foreach(_line IN LISTS _lines)
    if(NOT _line MATCHES "^ *[0-9a-f]+ +[0-9a-f]+ +([0-9a-f]+) +[0-9]+ .*\\):\\(([^)]*)\\)$")
        continue()
    endif()
    set(_section "${CMAKE_MATCH_2}")
    math(EXPR _size "0x${CMAKE_MATCH_1}")
    if(_section MATCHES "^\\.(debug|bss|tbss|comment)")
        continue()
    elseif(_section MATCHES "^\\.text")
        math(EXPR _code "${_code} + ${_size}")
    else()
        math(EXPR _rest "${_rest} + ${_size}")
    endif()
endforeach()
math(EXPR _all "${_code} + ${_rest}")
if(_all EQUAL 0)
    message(FATAL_ERROR "LinkedBytes.cmake: ${MAP} lists nothing of ${ARCHIVE}")
endif()
set(_said "Rust: ${ARCHIVE} takes ${_all} bytes of ${WHAT}: code ${_code}, data and unwind tables ${_rest}")
message(STATUS "${_said}")
file(WRITE "${OUT}" "${_said}\n")
