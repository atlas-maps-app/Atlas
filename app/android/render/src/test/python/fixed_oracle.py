#!/usr/bin/env python3
"""Oracle for the port's fast fixed-point formatting (PyFmt.appendFixed): '%.{p}f' % x for many floats.

    fixed_oracle.py <in> <out>

<in> holds one sample per line: the precision, then the float's IEEE bits as a signed int64. <out> gets one line per
sample: '%.{p}f' % x, so the Kotlin side compares text with text.
"""
import struct, sys


def main(src, dst):
    with open(src) as f, open(dst, 'w') as o:
        for line in f:
            p, b = line.split()
            x = struct.unpack('<d', struct.pack('<q', int(b)))[0]
            o.write(('%.' + p + 'f') % x)
            o.write('\n')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
