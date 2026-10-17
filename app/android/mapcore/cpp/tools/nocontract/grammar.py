#!/usr/bin/env python3
"""Derives tools/nocontract/grammar.hpp, the SPIR-V grammar nocontract refuses specialization constants by, from the
grammar the pinned Slang carries: the table of instruction names and opcodes its compiler library embeds from the
SPIRV-Headers it bundles (every name its spirv_asm takes), and the SpecId decoration's value from its table of
qualified enumerants. Where a spirv.core.grammar.json is given (NDK r30's SPIRV-Headers have one), every instruction
both know must have the same opcode in each, and SpecId the same value, or nothing is written.

Usage (from mapcore/cpp, after ./gradlew :mapcore:fetchSlang):
  python3 -I tools/nocontract/grammar.py <slang release dir> <slang version> [--grammar <spirv.core.grammar.json>]
      [--out tools/nocontract/grammar.hpp] [--clang-format <NDK r30's clang-format>]

The library is the macOS release's (lib/libslang-compiler.<version>.dylib, a 64-bit Mach-O whose pointers are chained
fixups: a rebase's low 36 bits are its target). Every platform's release of a version is built from the same sources,
so the grammar is the same. Run it again when tools.properties pins another Slang: CMakeLists.txt refuses grammar.hpp
under any other version.
"""
import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import textwrap

LC_SEGMENT_64 = 0x19
MH_MAGIC_64 = 0xFEEDFACF


def segments(data):
    """The Mach-O's segments: (name, vmaddr, vmsize, fileoff, filesize)."""
    magic, _, _, _, ncmds, _, _, _ = struct.unpack_from('<IiiIIIII', data, 0)
    if magic != MH_MAGIC_64:
        sys.exit('not a 64-bit little-endian Mach-O (a universal binary is not read): magic 0x%08x' % magic)
    out = []
    at = 32
    for _ in range(ncmds):
        cmd, size = struct.unpack_from('<II', data, at)
        if cmd == LC_SEGMENT_64:
            name = data[at + 8:at + 24].rstrip(b'\0').decode()
            vmaddr, vmsize, fileoff, filesize = struct.unpack_from('<QQQQ', data, at + 24)
            out.append((name, vmaddr, vmsize, fileoff, filesize))
        at += size
    return out


def file_offset(segs, vmaddr):
    for _, va, vs, fo, fs in segs:
        if va <= vmaddr < va + min(vs, fs):
            return fo + vmaddr - va
    return None


def string_table(data, segs, pattern):
    """vmaddr -> string, for every NUL-terminated string matching `pattern` in __TEXT."""
    text = next(s for s in segs if s[0] == '__TEXT')
    _, va, _, fo, fs = text
    out = {}
    for m in re.finditer(rb'(?<=\x00)(' + pattern + rb')(?=\x00)', data[fo:fo + fs]):
        out[va + m.start(1)] = m.group(1).decode()
    return out


def records(data, segs, strings):
    """Runs of 16-byte records {a rebased pointer to one of `strings`, a 32-bit value, 32 bits of zero} in the data
    segments: [(file offset of the first, [(string, value)])], the longest first."""
    runs = []
    for name, _, _, fo, fs in segs:
        if not name.startswith('__DATA'):
            continue
        at = fo
        end = fo + fs - 16

        def record(off):
            pointer = struct.unpack_from('<Q', data, off)[0]
            if pointer >> 63:  # a bind, not a rebase
                return None
            target = strings.get(pointer & ((1 << 36) - 1))
            value, pad = struct.unpack_from('<II', data, off + 8)
            return None if target is None or pad != 0 else (target, value)

        while at < end:
            first = record(at)
            if first is None:
                at += 8
                continue
            start, run = at, []
            while at < end and (r := record(at)) is not None:
                run.append(r)
                at += 16
            runs.append((start, run))
    runs.sort(key=lambda r: -len(r[1]))
    return runs


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    ap.add_argument('release', help="the Slang release's folder (slang-<version>-macos-aarch64)")
    ap.add_argument('version', help="its version, as tools.properties pins it (2026.19)")
    ap.add_argument('--grammar', help='a spirv.core.grammar.json to check the table against')
    ap.add_argument('--out', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'grammar.hpp'))
    ap.add_argument('--clang-format', help='formats the header in place with it, as core.yml checks it')
    args = ap.parse_args()

    lib_name = 'libslang-compiler.0.%s.dylib' % args.version
    lib = os.path.join(args.release, 'lib', lib_name)
    data = open(lib, 'rb').read()
    segs = segments(data)

    ops = records(data, segs, string_table(data, segs, rb'Op[A-Z][A-Za-z0-9]*'))
    if not ops or len(ops[0][1]) < 500 or (len(ops) > 1 and len(ops[1][1]) * 4 > len(ops[0][1])):
        sys.exit('no single table of instruction names in %s' % lib)
    table = ops[0][1]
    by_name = dict(table)
    if len(by_name) != len(table):
        sys.exit('the table names an instruction twice')
    for name, opcode in (('OpNop', 0), ('OpTypeVoid', 19), ('OpSpecConstantTrue', 48), ('OpDecorate', 71)):
        if by_name.get(name) != opcode:
            sys.exit('the table gives %s opcode %s, not %d' % (name, by_name.get(name), opcode))
    enums = records(data, segs, string_table(data, segs, rb'Decoration[A-Z][A-Za-z0-9]*'))
    spec_ids = {v for run in enums for n, v in run[1] if n == 'DecorationSpecId'}
    if len(spec_ids) != 1:
        sys.exit('the table of decorations gives SpecId %s' % sorted(spec_ids))
    spec_id = spec_ids.pop()

    checked = ''
    if args.grammar:
        raw = open(args.grammar, 'rb').read()
        grammar = json.loads(raw)
        agree = 0
        for ins in grammar['instructions']:
            for name in [ins['opname']] + ins.get('aliases', []):
                if name in by_name:
                    if by_name[name] != ins['opcode']:
                        sys.exit('%s: opcode %d in Slang, %d in %s' % (name, by_name[name], ins['opcode'], args.grammar))
                    agree += 1
        decoration = next(k for k in grammar['operand_kinds'] if k['kind'] == 'Decoration')
        json_spec_id = next(int(e['value']) for e in decoration['enumerants'] if e['enumerant'] == 'SpecId')
        if json_spec_id != spec_id:
            sys.exit('SpecId is %d in Slang, %d in %s' % (spec_id, json_spec_id, args.grammar))
        where = args.grammar
        marker = '/sources/third_party/'
        if marker in where:
            where = where[where.index(marker) + 1:]
        checked = ('Checked against SPIRV-Headers\' grammar as NDK r30 carries it (%s, SPIR-V %d.%d revision %d, SHA-256 '
                   '%s): the %d instruction names both know have the same opcodes in each, and SpecId the same value; '
                   'Slang knows %d names more (newer instructions, and aliases).'
                   % (where, grammar['major_version'], grammar['minor_version'], grammar['revision'],
                      hashlib.sha256(raw).hexdigest(), agree, len(by_name) - agree))

    def opcodes(prefix):
        """{opcode: [names]} of the names beginning `prefix`, the names (aliases) shortest first."""
        out = {}
        for name, opcode in table:
            if name.startswith(prefix):
                out.setdefault(opcode, []).append(name)
        return {op: sorted(names, key=lambda n: (len(n), n)) for op, names in sorted(out.items())}

    spec = opcodes('OpSpecConstant')
    decorating = {**opcodes('OpDecorate'), **opcodes('OpMemberDecorate')}
    types = opcodes('OpType')
    lines = []
    w = lines.append
    w('// The SPIR-V grammar nocontract refuses specialization constants by (constants.hpp), as the pinned Slang carries it.')
    w('// Generated by tools/nocontract/grammar.py: do not edit; run it again when tools.properties pins another Slang')
    w('// (CMakeLists.txt refuses this file under any other version).')
    w('//')
    source = ('Source: the table of instruction names and opcodes Slang %s\'s compiler embeds from the SPIRV-Headers it '
              'bundles (every name its spirv_asm takes, %d with the aliases), and its table of decorations, read from '
              'lib/%s of the %s release (SHA-256 %s).' % (args.version, len(by_name), lib_name,
                                                          os.path.basename(os.path.normpath(args.release)),
                                                          hashlib.sha256(data).hexdigest()))
    for paragraph in [source] + ([checked] if checked else []):
        lines.extend(textwrap.wrap(paragraph, 120, initial_indent='// ', subsequent_indent='// ',
                                   break_long_words=False, break_on_hyphens=False))
    w('#pragma once')
    w('')
    w('#include <array>')
    w('#include <cstdint>')
    w('#include <string_view>')
    w('')
    w('namespace atlas::spirv::grammar {')
    w('')
    w('/// The Slang release whose grammar this is.')
    w('inline constexpr std::string_view slang_version = "%s";' % args.version)
    w('')
    w('/// An instruction: its opcode and its name (the shortest of its aliases).')
    w('struct Named {')
    w('    std::uint16_t opcode;')
    w('    std::string_view name;')
    w('};')
    w('')
    w('/// Every instruction whose name begins OpSpecConstant: each makes a specialization constant.')
    w('inline constexpr std::array<Named, %d> spec_constant_instructions{{' % len(spec))
    for op, names in spec.items():
        w('    {.opcode = %d, .name = "%s"},' % (op, names[0]))
    w('}};')
    w('')
    w('/// An instruction that decorates: its opcode and name, and whether it decorates a member (the decoration is then its')
    w('/// third operand, after the target and the member, else its second).')
    w('struct Decorating {')
    w('    std::uint16_t opcode;')
    w('    std::string_view name;')
    w('    bool member;')
    w('};')
    w('')
    w('/// Every instruction whose name begins OpDecorate or OpMemberDecorate.')
    w('inline constexpr std::array<Decorating, %d> decorating_instructions{{' % len(decorating))
    for op, names in sorted(decorating.items()):
        w('    {.opcode = %d, .name = "%s", .member = %s},' % (
            op, names[0], 'true' if names[0].startswith('OpMemberDecorate') else 'false'))
    w('}};')
    w('')
    w('/// The SpecId decoration (the constant_id a specialization constant takes).')
    w('inline constexpr std::uint32_t decoration_spec_id = %d;' % spec_id)
    w('')
    w('/// Every type declaration (its name begins OpType): its first operand is a type\'s id.')
    w('inline constexpr std::array<std::uint16_t, %d> type_declarations{' % len(types))
    for op, names in types.items():
        w('    %d, // %s' % (op, ', '.join(names)))
    w('};')
    w('')
    w('} // namespace atlas::spirv::grammar')
    with open(args.out, 'w') as f:
        f.write('\n'.join(lines) + '\n')
    if args.clang_format:
        subprocess.run([args.clang_format, '-i', args.out], check=True)
    print('%s: %d instructions make specialization constants (%s), %d decorate, %d declare types; SpecId %d' % (
        args.out, len(spec), ', '.join(str(o) for o in spec), len(decorating), len(types), spec_id))


if __name__ == '__main__':
    main()
