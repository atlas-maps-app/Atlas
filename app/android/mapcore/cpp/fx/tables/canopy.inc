// Written by mapview's ByteTablesExportTest: the first 8 bytes of the SHA-256 of the canopy's matrix's alpha for
// each red (256 bytes), then of its filter's result over a pixel its path covers for each alpha (256 premultiplied
// sRGB a r g b ints, little-endian), as SvgFilterRaster computes them (render/regions.py's canopy). A plain
// source since that test went with the CPU raster (cut 1 of the parity checks, 17 November 2026).
ATLAS_CANOPY(0x09e6a69310c15b4dULL, 0xe5bce69bc3a60175ULL)
