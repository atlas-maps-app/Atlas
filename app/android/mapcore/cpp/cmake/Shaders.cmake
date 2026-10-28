# The shader pipeline (plan-vector T6): Slang → SPIR-V 1.3 → validation → the exact-module check → embedded in the
# core as constexpr arrays, with the push-constant and buffer layouts generated from Slang's reflection.
#
# atlas_add_shaders(<target> <shaders dir>) compiles every module under the directory except shaders/shared/, whose
# files are included by modules and, through base/hlsl.hpp, by the twin's C++. For each module:
#   1. slangc writes SPIR-V 1.3 and its reflection; SLANG_RUN_SPIRV_VALIDATION=1 makes it run SPIRV-Tools' validator
#      (spirv-val) on the result, and Slang warnings are errors;
#   2. tools/nocontract refuses a module that holds a specialization constant (below), then removes the capabilities
#      the module declares without needing them (capabilities.hpp: Slang declares StorageImageReadWithoutFormat for
#      subpass inputs, which Vulkan 1.1 phones may not support); an exact module (file name exact_*.slang, or a line
#      "// atlas: exact") compiles with -fp-mode precise, then nocontract adds NoContraction wherever it is missing and
#      checks every exact-module rule (tools/nocontract/exactness.hpp);
#   3. Embed.cmake writes gen/spirv/<dir>/<name>.hpp: atlas::spirv::<dir>_<name>, its words and SHA-256.
# A line "// atlas: variant <suffix> <MACRO>[=<value>]" compiles the module once more with the macro defined, as
# <name>_<suffix> (for instance the OpFmaKHR variant of an exact module). A line "// atlas: glsl-route <entry>:<stage>"
# also compiles that entry point through Slang's GLSL route (-emit-spirv-via-glsl: GLSL, then glslang), as
# <name>_glsl_<entry>: the route the quirk table switches a pipeline to when a driver mishandles Slang's own SPIR-V
# (plan-vector T6). That route drops NoContraction (measured with Slang 2026.19: none of the exact probe's 30
# operations kept it), so for an exact module step 2 matters there most. gen/layouts.hpp collects every module's
# push constants and buffer bindings as C++ structs whose offsets and sizes are static_asserted against Slang's layout.
# The test spirv_lock compares each module's SHA-256 with shaders/<dir>/spirv.lock, so an unintended change of the
# compiled code shows; the target atlas_spirv_lock writes the lock files from the current build. A module that misses
# its lock is kept in <build>/spirv-lock-mismatch/ (as embedded, as slangc wrote it, and as spirv-dis's text where
# SPIRV-Tools is installed), and CI keeps that folder, so another host's bytes can be diffed.
#
# Beside it the test spirv_budget holds each compute entry point to shaders/spirv.budget: where the tests are built,
# every compute entry point (by its module's reflection) is compiled alone as its module is, and nocontract budget
# measures its bytes and its threads' local arrays (tools/nocontract/budget.hpp). On the user's PowerVR phone the map
# froze from alpha 16 at about z 17, the first frame that ran the windows' decide stage, once that one stage had grown
# to 333,400 bytes with 5,088 bytes of local arrays a thread; alpha 15's (211,280 bytes, 780) is the ceiling.
#
# Specialization constants are refused: every module is checked before it is embedded (nocontract constants, a scan of
# the module for every instruction of the pinned Slang's SPIR-V grammar whose name begins OpSpecConstant, and for any
# SpecId decoration by any instruction that decorates: tools/nocontract/grammar.hpp, derived from the table Slang's
# compiler embeds), and one that holds any fails the build, naming each. On 17 October 2026 PowerVR's Vulkan driver
# crashed compiling a pipeline of VK bench, in SpvConstCalculated's destructor in its shader compiler (libufwriter.so):
# the stand-in's composite took its sample count as a specialization constant and held a constant computed from it
# (OpSpecConstantOp IAdd). A variant is a module of its own: a "// atlas: variant" line, or a file that defines the
# macro and includes another (shaders/standin/composite_x2.slang), and the pipeline picks the module.
#
# The semantics SV_VertexID and SV_InstanceID are refused. Slang 2026.19 lowers each to a difference of two builtins
# (gl_VertexIndex - gl_BaseVertex, gl_InstanceIndex - gl_BaseInstance) whose two loads are arguments of one call in its
# legalisation (slang-ir-glsl-legalize.cpp), so they are emitted in whichever order its own compiler evaluated them:
# right to left in the x86_64 Linux release (GCC 11.2), left to right in the arm64 Linux (GCC 11.4) and macOS (clang)
# releases, as their machine code shows. The module's bytes then depend on the host that built it (the probe's did, on
# CI's x86_64 runners only), and it needs DrawParameters, which a Vulkan 1.1 phone may lack. SV_VulkanVertexID and
# SV_VulkanInstanceID read Vulkan's own builtins.
#
# Script modes (cmake -DMODE=... -P Shaders.cmake): semantics, layouts, lock-check, lock-update, budget-measure,
# budget-check.

if(CMAKE_SCRIPT_MODE_FILE)
    cmake_minimum_required(VERSION 3.28...4.1)

    # A module takes no semantic whose SPIR-V depends on the build host (above); run before slangc on every module.
    if(MODE STREQUAL "semantics")
        file(READ "${SOURCE}" text)
        string(REGEX REPLACE "//[^\n]*" "" text "${text}")
        string(TOLOWER "${text}" text)
        if(text MATCHES ":[ \t\r\n]*sv_(vertex|instance)id([^a-z0-9_]|$)")
            if(CMAKE_MATCH_1 STREQUAL "vertex")
                set(which Vertex)
            else()
                set(which Instance)
            endif()
            message(FATAL_ERROR "${SOURCE}: SV_${which}ID compiles to SPIR-V that depends on the host that built it "
                "(Slang 2026.19 emits its two loads in its own compiler's order of evaluation; cmake/Shaders.cmake "
                "says more). Take SV_Vulkan${which}ID, Vulkan's own index.")
        endif()
        return()
    endif()

    # C++ for a reflected type. Sets <out>_type, <out>_size; appends struct definitions to the variable `structs`.
    function(_atlas_cpp_type json out)
        string(JSON kind GET "${json}" kind)
        if(kind STREQUAL "scalar")
            string(JSON scalar GET "${json}" scalarType)
            # Each entry: Slang's scalar type, its C++ type, its size in bytes (a half or a bool as raw bits).
            set(map "float32=float=4" "float64=double=8" "int32=std::int32_t=4" "uint32=std::uint32_t=4"
                "int64=std::int64_t=8" "uint64=std::uint64_t=8" "int16=std::int16_t=2" "uint16=std::uint16_t=2"
                "float16=std::uint16_t=2" "int8=std::int8_t=1" "uint8=std::uint8_t=1" "bool=std::uint32_t=4")
            set(t "")
            foreach(entry IN LISTS map)
                if(entry MATCHES "^${scalar}=([^=]+)=([0-9]+)$")
                    set(t "${CMAKE_MATCH_1}")
                    set(s "${CMAKE_MATCH_2}")
                endif()
            endforeach()
            if(NOT t)
                message(FATAL_ERROR "Shaders.cmake: no C++ type for the scalar ${scalar}")
            endif()
        elseif(kind STREQUAL "vector")
            string(JSON n GET "${json}" elementCount)
            string(JSON element GET "${json}" elementType)
            _atlas_cpp_type("${element}" e)
            set(t "std::array<${e_type}, ${n}>")
            math(EXPR s "${e_size} * ${n}")
        elseif(kind STREQUAL "array")
            string(JSON n GET "${json}" elementCount)
            string(JSON element GET "${json}" elementType)
            string(JSON stride GET "${json}" uniformStride)
            _atlas_cpp_type("${element}" e)
            if(NOT stride EQUAL e_size)
                message(FATAL_ERROR "Shaders.cmake: an array whose stride (${stride}) is not its element's size "
                    "(${e_size}); give the element explicit padding in the shader")
            endif()
            set(t "std::array<${e_type}, ${n}>")
            math(EXPR s "${stride} * ${n}")
        elseif(kind STREQUAL "struct")
            _atlas_struct("${json}" t s)
        else()
            message(FATAL_ERROR "Shaders.cmake: reflected ${kind} types are not generated yet (matrices among them)")
        endif()
        set(${out}_type "${t}" PARENT_SCOPE)
        set(${out}_size "${s}" PARENT_SCOPE)
        set(structs "${structs}" PARENT_SCOPE)
    endfunction()

    # A parameter's descriptor binding, and its input attachment index ("" for none). Most parameters have one
    # "binding"; an input attachment has "bindings": its descriptor slot and its index in the subpass.
    function(_atlas_parameter_binding p out_binding out_attachment)
        set(attachment "")
        string(JSON binding ERROR_VARIABLE no_binding GET "${p}" binding)
        if(no_binding)
            set(binding "")
            string(JSON n ERROR_VARIABLE no_bindings LENGTH "${p}" bindings)
            if(no_bindings OR n EQUAL 0)
                string(JSON pname GET "${p}" name)
                message(FATAL_ERROR "Shaders.cmake: the parameter ${pname} has neither a binding nor bindings")
            endif()
            math(EXPR last "${n} - 1")
            foreach(i RANGE ${last})
                string(JSON b GET "${p}" bindings ${i})
                string(JSON kind GET "${b}" kind)
                if(kind STREQUAL "descriptorTableSlot")
                    set(binding "${b}")
                elseif(kind STREQUAL "inputAttachmentIndex")
                    string(JSON attachment GET "${b}" index)
                endif()
            endforeach()
            if(binding STREQUAL "")
                set(binding "{\"kind\": \"none\"}")
            endif()
        endif()
        set(${out_binding} "${binding}" PARENT_SCOPE)
        set(${out_attachment} "${attachment}" PARENT_SCOPE)
    endfunction()

    # A struct's definition with explicit padding, offsets and size checked; appended to `structs` once per name.
    function(_atlas_struct json out_type out_size)
        string(JSON name GET "${json}" name)
        string(JSON fields LENGTH "${json}" fields)
        set(members "")
        set(checks "")
        set(at 0)
        if(fields GREATER 0)
            math(EXPR last "${fields} - 1")
            foreach(i RANGE ${last})
                string(JSON field GET "${json}" fields ${i})
                string(JSON fname GET "${field}" name)
                string(JSON ftype GET "${field}" type)
                string(JSON offset GET "${field}" binding offset)
                string(JSON fsize GET "${field}" binding size)
                _atlas_cpp_type("${ftype}" f)
                if(offset LESS at)
                    message(FATAL_ERROR "Shaders.cmake: ${name}.${fname} overlaps the field before it")
                endif()
                if(offset GREATER at)
                    math(EXPR gap "${offset} - ${at}")
                    string(APPEND members "    std::array<std::byte, ${gap}> pad_${at};\n")
                endif()
                string(APPEND members "    ${f_type} ${fname};\n")
                string(APPEND checks "static_assert(offsetof(${name}, ${fname}) == ${offset});\n")
                math(EXPR at "${offset} + ${f_size}")
            endforeach()
        endif()
        # The struct's own size, padding at its end included (std430: rounded up to its alignment).
        string(JSON size ERROR_VARIABLE no_size GET "${json}" sizes 0 value)
        if(no_size)
            set(size ${at})
        endif()
        if(size GREATER at)
            math(EXPR gap "${size} - ${at}")
            string(APPEND members "    std::array<std::byte, ${gap}> pad_${at};\n")
        endif()
        string(APPEND checks "static_assert(sizeof(${name}) == ${size});\n")
        string(APPEND checks "static_assert(std::is_standard_layout_v<${name}> && std::is_trivially_copyable_v<${name}>);\n")
        string(FIND "${structs}" "struct ${name} {" seen)
        if(seen EQUAL -1)
            string(APPEND structs "struct ${name} {\n${members}};\n${checks}\n")
        endif()
        set(structs "${structs}" PARENT_SCOPE)
        set(${out_type} "${name}" PARENT_SCOPE)
        set(${out_size} "${size}" PARENT_SCOPE)
    endfunction()

    if(MODE STREQUAL "layouts")
        file(STRINGS "${MANIFEST}" modules)
        set(text "// Generated by cmake/Shaders.cmake from Slang's reflection of every shader module. Do not edit.\n")
        string(APPEND text "// Each module's push constants and buffer elements as C++, laid out byte for byte as the GPU reads them.\n")
        string(APPEND text "#pragma once\n\n#include <array>\n#include <cstddef>\n#include <cstdint>\n#include <type_traits>\n\n")
        string(APPEND text "namespace atlas::layouts {\n\n/// A descriptor's set and binding.\n")
        string(APPEND text "struct Binding {\n    std::uint32_t set;\n    std::uint32_t binding;\n};\n\n")
        foreach(entry IN LISTS modules)
            string(REPLACE "|" ";" entry "${entry}")
            list(GET entry 0 symbol)
            list(GET entry 2 reflection)
            file(READ "${reflection}" json)
            set(structs "")
            set(decls "")
            string(JSON count LENGTH "${json}" parameters)
            if(count GREATER 0)
                math(EXPR last "${count} - 1")
                foreach(i RANGE ${last})
                    string(JSON p GET "${json}" parameters ${i})
                    string(JSON pname GET "${p}" name)
                    _atlas_parameter_binding("${p}" binding attachment)
                    string(JSON binding_kind GET "${binding}" kind)
                    string(JSON type_kind GET "${p}" type kind)
                    if(binding_kind STREQUAL "pushConstantBuffer")
                        string(JSON element GET "${p}" type elementType)
                        _atlas_cpp_type("${element}" e)
                        string(APPEND decls "/// The push constants (${e_size} bytes).\nusing PushConstants = ${e_type};\n")
                    elseif(binding_kind STREQUAL "descriptorTableSlot")
                        string(JSON index GET "${binding}" index)
                        string(JSON space ERROR_VARIABLE no_space GET "${binding}" space)
                        if(no_space)
                            set(space 0)
                        endif()
                        string(APPEND decls "inline constexpr Binding ${pname}{.set = ${space}, .binding = ${index}};\n")
                        if(NOT attachment STREQUAL "")
                            string(APPEND decls "/// ${pname}'s input attachment index in its subpass.\n")
                            string(APPEND decls "inline constexpr std::uint32_t ${pname}_input_attachment = ${attachment};\n")
                        endif()
                        if(type_kind STREQUAL "resource")
                            string(JSON shape GET "${p}" type baseShape)
                            if(shape STREQUAL "structuredBuffer")
                                string(JSON result GET "${p}" type resultType)
                                _atlas_cpp_type("${result}" e)
                                string(APPEND decls "using ${pname}_element = ${e_type};\n")
                                string(APPEND decls "static_assert(sizeof(${pname}_element) == ${e_size});\n")
                            endif()
                        elseif(type_kind STREQUAL "constantBuffer")
                            string(JSON element GET "${p}" type elementType)
                            _atlas_cpp_type("${element}" e)
                            string(APPEND decls "using ${pname}_block = ${e_type};\n")
                        endif()
                    endif()
                endforeach()
            endif()
            string(APPEND text "namespace ${symbol} {\n\n${structs}${decls}\n} // namespace ${symbol}\n\n")
        endforeach()
        string(APPEND text "} // namespace atlas::layouts\n")
        if(EXISTS "${OUTPUT}")
            file(READ "${OUTPUT}" old)
            if(old STREQUAL text)
                return()
            endif()
        endif()
        file(WRITE "${OUTPUT}" "${text}")
        return()
    endif()

    if(MODE STREQUAL "lock-check" OR MODE STREQUAL "lock-update")
        file(STRINGS "${MANIFEST}" modules)
        set(failures "")
        set(missed "")
        set(locks "")
        foreach(entry IN LISTS modules)
            string(REPLACE "|" ";" entry "${entry}")
            list(GET entry 0 symbol)
            list(GET entry 1 spv)
            list(GET entry 3 lock)
            file(SHA256 "${spv}" sha)
            list(APPEND locks "${lock}")
            string(MAKE_C_IDENTIFIER "${lock}" key)
            string(APPEND lock_${key} "${symbol} ${sha}\n")
            if(MODE STREQUAL "lock-check")
                set(expected "")
                if(EXISTS "${lock}")
                    file(STRINGS "${lock}" lines REGEX "^${symbol} ")
                    if(lines)
                        string(REGEX REPLACE "^${symbol} " "" expected "${lines}")
                    endif()
                endif()
                if(NOT expected STREQUAL sha)
                    string(APPEND failures "  ${symbol}: built ${sha}, locked '${expected}' (${lock})\n")
                    list(APPEND missed "${symbol}|${spv}")
                endif()
            endif()
        endforeach()
        # The modules that missed their lock, for a diff with another host's: each as embedded (<symbol>.spv), as
        # slangc wrote it before nocontract (<symbol>.slangc.spv), with Slang's reflection, and as spirv-dis's text
        # where SPIRV-Tools is installed. Emptied first, so the folder holds this run's misses only.
        if(MODE STREQUAL "lock-check" AND KEEP)
            file(REMOVE_RECURSE "${KEEP}")
            if(missed)
                file(MAKE_DIRECTORY "${KEEP}")
                find_program(spirv_dis NAMES spirv-dis)
                foreach(entry IN LISTS missed)
                    string(REPLACE "|" ";" entry "${entry}")
                    list(GET entry 0 symbol)
                    list(GET entry 1 spv)
                    string(REGEX REPLACE "\\.spv$" "" base "${spv}")
                    file(COPY_FILE "${spv}" "${KEEP}/${symbol}.spv")
                    foreach(extra IN ITEMS slangc.spv json)
                        if(EXISTS "${base}.${extra}")
                            file(COPY_FILE "${base}.${extra}" "${KEEP}/${symbol}.${extra}")
                        endif()
                    endforeach()
                    if(spirv_dis)
                        foreach(kept IN ITEMS spv slangc.spv)
                            if(EXISTS "${KEEP}/${symbol}.${kept}")
                                string(REGEX REPLACE "spv$" "spvasm" text "${KEEP}/${symbol}.${kept}")
                                execute_process(COMMAND "${spirv_dis}" "${KEEP}/${symbol}.${kept}" -o "${text}"
                                    RESULT_VARIABLE rc OUTPUT_QUIET ERROR_QUIET)
                            endif()
                        endforeach()
                    endif()
                endforeach()
                file(WRITE "${KEEP}/mismatch.txt"
                    "Built on ${HOST} with Slang ${SLANG} (cmake/Shaders.cmake, lock-check):\n${failures}")
            endif()
        endif()
        if(MODE STREQUAL "lock-update")
            list(REMOVE_DUPLICATES locks)
            foreach(lock IN LISTS locks)
                string(MAKE_C_IDENTIFIER "${lock}" key)
                file(WRITE "${lock}" "# SHA-256 of each compiled module (cmake/Shaders.cmake); update with the target atlas_spirv_lock.\n${lock_${key}}")
                message(STATUS "wrote ${lock}")
            endforeach()
        elseif(failures)
            set(where "")
            if(KEEP)
                set(where " The modules as built are in ${KEEP}.")
            endif()
            message(FATAL_ERROR "The compiled SPIR-V differs from shaders/*/spirv.lock:\n${failures}"
                "If the change is intended (a shader or Slang changed), rebuild the target atlas_spirv_lock and "
                "commit the lock files with it.${where}")
        endif()
        return()
    endif()

    # Each compute entry point of a module (its reflection's) compiled alone with the module's own flags, measured by
    # nocontract budget, into OUTPUT: a line "<symbol>:<entry point> <bytes> <local arrays> <their bytes> <locals>
    # <their bytes>" each. A module through the GLSL route is its one entry point already: its own SPIR-V is measured.
    if(MODE STREQUAL "budget-measure")
        file(READ "${REFLECTION}" json)
        string(JSON count LENGTH "${json}" entryPoints)
        separate_arguments(flags UNIX_COMMAND "${FLAGS}")
        set(text "")
        if(count GREATER 0)
            math(EXPR last "${count} - 1")
            foreach(i RANGE ${last})
                string(JSON entry GET "${json}" entryPoints ${i} name)
                string(JSON stage GET "${json}" entryPoints ${i} stage)
                if(NOT stage STREQUAL "compute")
                    continue()
                endif()
                set(alone "${RAW}")
                if(NOT ROUTE)
                    set(alone "${BASE}.${entry}.spv")
                    execute_process(
                        COMMAND "${SLANGC}" "${SOURCE}" -target spirv -profile spirv_1_3 -fvk-use-entrypoint-name
                                -std 2026 -warnings-as-errors all ${flags} -I "${ROOT}" -I "${SHADERS}" -entry ${entry}
                                -stage compute -o "${alone}"
                        RESULT_VARIABLE rc ERROR_VARIABLE err OUTPUT_QUIET)
                    if(NOT rc EQUAL 0)
                        message(FATAL_ERROR "${SOURCE}: slangc of ${entry} alone failed:\n${err}")
                    endif()
                endif()
                execute_process(COMMAND "${NOCONTRACT}" budget "${alone}"
                    RESULT_VARIABLE rc OUTPUT_VARIABLE measured ERROR_VARIABLE err OUTPUT_STRIP_TRAILING_WHITESPACE)
                if(NOT rc EQUAL 0)
                    message(FATAL_ERROR "${alone}: nocontract budget failed:\n${err}")
                endif()
                string(APPEND text "${SYMBOL}:${entry} ${measured}\n")
            endforeach()
        endif()
        file(WRITE "${OUTPUT}" "${text}")
        return()
    endif()

    # The test spirv_budget: each compute entry point's measure (the budget-measure files MANIFEST lists) against
    # CEILINGS (shaders/spirv.budget): its own line where it has one, else the line "*"; each printed, and any that
    # passes its line named.
    if(MODE STREQUAL "budget-check")
        file(STRINGS "${CEILINGS}" lines REGEX "^[^#]")
        foreach(line IN LISTS lines)
            string(REGEX MATCHALL "[^ \t]+" fields "${line}")
            list(LENGTH fields n)
            if(NOT n EQUAL 4)
                message(FATAL_ERROR "${CEILINGS}: cannot read the line '${line}'")
            endif()
            list(GET fields 0 key)
            string(MAKE_C_IDENTIFIER "${key}" id) # "*" is "_"
            list(SUBLIST fields 1 3 ceiling_${id})
        endforeach()
        if(NOT DEFINED ceiling__)
            message(FATAL_ERROR "${CEILINGS} has no line \"*\"")
        endif()
        file(STRINGS "${MANIFEST}" files)
        set(failures "")
        set(checked 0)
        foreach(f IN LISTS files)
            file(STRINGS "${f}" measures)
            foreach(line IN LISTS measures)
                string(REGEX MATCHALL "[^ \t]+" fields "${line}")
                list(GET fields 0 key)
                list(GET fields 1 bytes)
                list(GET fields 2 arrays)
                list(GET fields 3 array_bytes)
                string(MAKE_C_IDENTIFIER "${key}" id)
                set(ceiling "${ceiling__}")
                if(DEFINED ceiling_${id})
                    set(ceiling "${ceiling_${id}}")
                endif()
                list(GET ceiling 0 most_bytes)
                list(GET ceiling 1 most_arrays)
                list(GET ceiling 2 most_array_bytes)
                message(STATUS "${key}: ${bytes} bytes (at most ${most_bytes}), ${arrays} local arrays of ${array_bytes} "
                    "bytes (at most ${most_arrays} of ${most_array_bytes})")
                if(bytes GREATER most_bytes OR arrays GREATER most_arrays OR array_bytes GREATER most_array_bytes)
                    string(APPEND failures "  ${key}: ${bytes} bytes, ${arrays} local arrays of ${array_bytes} bytes; "
                        "its line: ${most_bytes}, ${most_arrays}, ${most_array_bytes}\n")
                endif()
                math(EXPR checked "${checked} + 1")
            endforeach()
        endforeach()
        if(checked EQUAL 0)
            message(FATAL_ERROR "spirv_budget: no compute entry point was measured (${MANIFEST})")
        endif()
        if(failures)
            message(FATAL_ERROR "Compute entry points past their line in ${CEILINGS}:\n${failures}"
                "Split such a stage into entry points of their own, each dispatched over what needs it "
                "(shaders/building/draw.slang's windows' decide stages), rather than raising its line.")
        endif()
        message(STATUS "spirv_budget: ${checked} compute entry points within their lines")
        return()
    endif()

    message(FATAL_ERROR "Shaders.cmake: unknown MODE '${MODE}'")
endif()

include_guard(GLOBAL)

# Compiles the shader modules under `shaders_dir` and makes `target` depend on their generated headers.
function(atlas_add_shaders target shaders_dir)
    set(gen_dir "${CMAKE_BINARY_DIR}/gen")
    set(work_dir "${CMAKE_BINARY_DIR}/shaders")
    file(GLOB_RECURSE sources CONFIGURE_DEPENDS RELATIVE "${shaders_dir}" "${shaders_dir}/*.slang")
    list(FILTER sources EXCLUDE REGEX "^shared/")
    list(SORT sources)
    set(headers "")
    set(reflections "")
    set(budgets "")
    set(manifest "")
    set(module_includes "")
    set(module_entries "")
    set(module_hashes "")
    foreach(rel IN LISTS sources)
        set(src "${shaders_dir}/${rel}")
        get_filename_component(rel_dir "${rel}" DIRECTORY)
        get_filename_component(name "${rel}" NAME_WE)
        file(STRINGS "${src}" directives REGEX "^// atlas: ")
        set(exact OFF)
        if(name MATCHES "^exact_" OR "// atlas: exact" IN_LIST directives)
            set(exact ON)
        endif()
        # The plain module, then one per variant line: "<suffix>|<define>|<entry>:<stage> for the GLSL route".
        set(variants "||")
        foreach(line IN LISTS directives)
            if(line MATCHES "^// atlas: variant ([a-z0-9_]+) ([A-Za-z_][A-Za-z0-9_]*(=[^ ]+)?)$")
                list(APPEND variants "_${CMAKE_MATCH_1}|${CMAKE_MATCH_2}|")
            elseif(line MATCHES "^// atlas: glsl-route ([A-Za-z_][A-Za-z0-9_]*):([a-z]+)$")
                list(APPEND variants "_glsl_${CMAKE_MATCH_1}||${CMAKE_MATCH_1}:${CMAKE_MATCH_2}")
            elseif(line MATCHES "^// atlas: (variant|glsl-route)")
                message(FATAL_ERROR "shaders/${rel}: cannot read the directive '${line}'")
            endif()
        endforeach()
        foreach(variant IN LISTS variants)
            string(REPLACE "|" ";" variant "${variant}")
            list(GET variant 0 suffix)
            list(GET variant 1 define)
            list(GET variant 2 route)
            set(define_flags "")
            if(define)
                set(define_flags "-D${define}")
            endif()
            # Slang's own SPIR-V, or one entry point through GLSL and glslang (GLSL has one entry point per shader).
            set(route_flags -profile spirv_1_3 -fvk-use-entrypoint-name)
            if(route)
                string(REPLACE ":" ";" route "${route}")
                list(GET route 0 entry)
                list(GET route 1 stage)
                set(route_flags -profile glsl_450+spirv_1_3 -emit-spirv-via-glsl -entry ${entry} -stage ${stage})
            endif()
            string(MAKE_C_IDENTIFIER "${rel_dir}_${name}${suffix}" symbol)
            set(base "${work_dir}/${rel_dir}/${name}${suffix}")
            set(raw "${base}.slangc.spv")
            set(spv "${base}.spv")
            set(json "${base}.json")
            set(dep "${base}.d")
            set(header "${gen_dir}/spirv/${rel_dir}/${name}${suffix}.hpp")
            set(fp_mode "")
            if(exact)
                set(fp_mode -fp-mode precise)
            endif()
            add_custom_command(
                OUTPUT "${raw}" "${json}"
                COMMAND "${CMAKE_COMMAND}" -E make_directory "${work_dir}/${rel_dir}"
                COMMAND "${CMAKE_COMMAND}" -DMODE=semantics -DSOURCE=${src} -P "${CMAKE_CURRENT_FUNCTION_LIST_FILE}"
                COMMAND "${CMAKE_COMMAND}" -E env SLANG_RUN_SPIRV_VALIDATION=1
                        "${ATLAS_SLANGC}" "${src}" -target spirv ${route_flags} -std 2026
                        -warnings-as-errors all ${fp_mode} ${define_flags}
                        -I "${CMAKE_SOURCE_DIR}" -I "${shaders_dir}"
                        -o "${raw}" -reflection-json "${json}" -depfile "${dep}"
                DEPFILE "${dep}"
                DEPENDS "${src}" "${ATLAS_SLANGC}"
                COMMENT "slangc ${rel}${suffix}"
                VERBATIM)
            # Every module loses the capabilities it declares without needing them (tools/nocontract/capabilities.hpp:
            # Slang declares StorageImageReadWithoutFormat for subpass inputs); an exact module is then made exact.
            # Every module is first refused if it holds a specialization constant (above), on slangc's output, so a
            # refused module never reaches ${spv} and the next build checks it again.
            set(module_name "shaders/${rel} (${symbol})")
            if(exact)
                add_custom_command(
                    OUTPUT "${spv}"
                    COMMAND ${ATLAS_NOCONTRACT} constants "${raw}" "${module_name}"
                    COMMAND ${ATLAS_NOCONTRACT} capabilities "${raw}" "${spv}"
                    COMMAND ${ATLAS_NOCONTRACT} fix "${spv}" "${spv}"
                    COMMAND ${ATLAS_NOCONTRACT} check "${spv}"
                    DEPENDS "${raw}" ${ATLAS_NOCONTRACT_DEPENDS}
                    COMMENT "nocontract ${rel}${suffix}"
                    VERBATIM)
            else()
                add_custom_command(
                    OUTPUT "${spv}"
                    COMMAND ${ATLAS_NOCONTRACT} constants "${raw}" "${module_name}"
                    COMMAND ${ATLAS_NOCONTRACT} capabilities "${raw}" "${spv}"
                    DEPENDS "${raw}" ${ATLAS_NOCONTRACT_DEPENDS}
                    COMMENT "nocontract capabilities ${rel}${suffix}"
                    VERBATIM)
            endif()
            add_custom_command(
                OUTPUT "${header}"
                COMMAND "${CMAKE_COMMAND}" -DINPUT=${spv} -DOUTPUT=${header} -DSYMBOL=${symbol} -DSOURCE=shaders/${rel}
                        -P "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/Embed.cmake"
                DEPENDS "${spv}" "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/Embed.cmake"
                COMMENT "embed ${symbol}"
                VERBATIM)
            # where the tests are built, each compute entry point measured alone (the test spirv_budget)
            if(ATLAS_TESTS)
                set(budget "${base}.budget")
                string(JOIN " " flags_text ${fp_mode} ${define_flags})
                set(route_entry "")
                if(route)
                    set(route_entry "${entry}")
                endif()
                add_custom_command(
                    OUTPUT "${budget}"
                    COMMAND "${CMAKE_COMMAND}" -DMODE=budget-measure -DSOURCE=${src} -DREFLECTION=${json} -DRAW=${raw}
                            -DBASE=${base} -DSYMBOL=${symbol} -DROUTE=${route_entry} "-DFLAGS=${flags_text}"
                            -DSLANGC=${ATLAS_SLANGC} -DNOCONTRACT=${ATLAS_NOCONTRACT} -DROOT=${CMAKE_SOURCE_DIR}
                            -DSHADERS=${shaders_dir} -DOUTPUT=${budget} -P "${CMAKE_CURRENT_FUNCTION_LIST_FILE}"
                    DEPENDS "${raw}" "${json}" ${ATLAS_NOCONTRACT_DEPENDS} "${CMAKE_CURRENT_FUNCTION_LIST_FILE}"
                    COMMENT "budget ${rel}${suffix}"
                    VERBATIM)
                list(APPEND budgets "${budget}")
            endif()
            list(APPEND headers "${header}")
            list(APPEND reflections "${json}")
            string(APPEND module_includes "#include \"gen/spirv/${rel_dir}/${name}${suffix}.hpp\"\n")
            string(APPEND module_entries "    Module{\"${symbol}\", ${symbol}},\n")
            string(APPEND module_hashes "    ${symbol}_sha256,\n")
            string(APPEND manifest "${symbol}|${spv}|${json}|${shaders_dir}/${rel_dir}/spirv.lock\n")
        endforeach()
    endforeach()

    # The manifest the script modes read, rewritten only when the set of modules changes.
    set(manifest_file "${work_dir}/manifest.txt")
    file(CONFIGURE OUTPUT "${manifest_file}" CONTENT "${manifest}" @ONLY)
    # gen/spirv/modules.hpp: every module as embedded, so a test can hand each to the validation layer, whose SPIRV-Tools
    # validates the words nocontract last rewrote (slangc's validator saw them before that); and every module's SHA-256
    # alone, which the core's build ID and its pipeline cache's key hash without taking any module's words into the
    # library.
    list(LENGTH headers module_count)
    file(CONFIGURE OUTPUT "${gen_dir}/spirv/modules.hpp" @ONLY CONTENT
"// Generated by cmake/Shaders.cmake: every embedded SPIR-V module. Do not edit.
#pragma once

${module_includes}
#include <array>
#include <cstdint>
#include <span>
#include <string_view>

namespace atlas::spirv {

/// One embedded module: its symbol and its words.
struct Module {
    std::string_view name;
    std::span<const std::uint32_t> words;
};

inline constexpr std::array<Module, ${module_count}> modules = {
${module_entries}};

/// Every module's SHA-256, in the order of `modules`.
inline constexpr std::array<std::string_view, ${module_count}> hashes = {
${module_hashes}};

} // namespace atlas::spirv
")
    add_custom_command(
        OUTPUT "${gen_dir}/layouts.hpp"
        COMMAND "${CMAKE_COMMAND}" -DMODE=layouts -DMANIFEST=${manifest_file} -DOUTPUT=${gen_dir}/layouts.hpp
                -P "${CMAKE_CURRENT_FUNCTION_LIST_FILE}"
        DEPENDS ${reflections} "${manifest_file}" "${CMAKE_CURRENT_FUNCTION_LIST_FILE}"
        COMMENT "layouts from Slang's reflection"
        VERBATIM)
    # the compute entry points' measures, which the test spirv_budget reads
    list(JOIN budgets "\n" budget_list)
    file(CONFIGURE OUTPUT "${work_dir}/budgets.txt" CONTENT "${budget_list}\n" @ONLY)
    add_custom_target(${target}_shaders DEPENDS ${headers} "${gen_dir}/layouts.hpp" ${budgets})
    add_dependencies(${target} ${target}_shaders)
    target_include_directories(${target} PUBLIC "${CMAKE_BINARY_DIR}")
    set_property(GLOBAL APPEND PROPERTY ATLAS_SHADER_MANIFESTS "${manifest_file}")

    add_custom_target(atlas_spirv_lock
        COMMAND "${CMAKE_COMMAND}" -DMODE=lock-update -DMANIFEST=${manifest_file} -P "${CMAKE_CURRENT_FUNCTION_LIST_FILE}"
        DEPENDS ${target}_shaders
        COMMENT "writing shaders/*/spirv.lock"
        VERBATIM)
    if(ATLAS_TESTS)
        add_test(NAME spirv_lock
            COMMAND "${CMAKE_COMMAND}" -DMODE=lock-check -DMANIFEST=${manifest_file}
                    -DKEEP=${CMAKE_BINARY_DIR}/spirv-lock-mismatch -DHOST=${ATLAS_HOST_PLATFORM}
                    -DSLANG=${ATLAS_SLANG_VERSION} -P "${CMAKE_CURRENT_FUNCTION_LIST_FILE}")
        add_test(NAME spirv_budget
            COMMAND "${CMAKE_COMMAND}" -DMODE=budget-check -DMANIFEST=${work_dir}/budgets.txt
                    -DCEILINGS=${shaders_dir}/spirv.budget -P "${CMAKE_CURRENT_FUNCTION_LIST_FILE}")
    endif()
endfunction()
