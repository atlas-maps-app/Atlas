package app.atlas.ui.go

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin

// Generated from the boards' own SVG (canvas/project/*.dc.html) by the go package's art.py: do not edit by hand.

/**
 * The boards' drawings that the board symbol set leaves out, ported element by element: the climb wedge (Ride), the
 * bridge (Sail), the rack and its motorbike (Twowheel), the skyline and the gantry (Go). The icons are board symbols.
 */
internal object BoardArt {
    /** The red wedge of a climb (Ride). */
    val climbWedge = Art(0f, 0f, 40f, 28f, listOf(
        ArtPart("M2 26L38 6V26z", fill = 0xFFFF6B3D),
        ArtPart("M2 26L38 6", fill = 0xFF000000, stroke = 0xFFFFB08F, width = 2f, cap = StrokeCap.Round),
    ))

    /** The bridge with your mast under it (Sail). */
    val bridge = Art(0f, 0f, 170f, 98f, listOf(
        ArtPart("M0 84c12-3 22 3 34 0s22-3 34 0 22 3 34 0 22-3 34 0 22 3 34 0V98H0z", fill = 0xFFA7D3EC),
        ArtPart("M0 84c12-3 22 3 34 0s22-3 34 0 22 3 34 0 22-3 34 0 22 3 34 0", stroke = 0xFF5B8FB5, width = 1.5f),
        ArtPart("M18 8h8v78h-8z", fill = 0xFF8E97AE),
        ArtPart("M144 8h8v78h-8z", fill = 0xFF8E97AE),
        ArtPart("M0 22H170", fill = 0xFF000000, stroke = 0xFF5E6780, width = 7f),
        ArtPart("M22 8C60 40 110 40 148 8", stroke = 0xFF5E6780, width = 1.6f),
        ArtPart("M40 22v-4M56 22v-10M72 22v-14M88 22v-15M104 22v-13M120 22v-9M136 22v-4", fill = 0xFF000000, stroke = 0xFF5E6780, width = 1f),
        ArtPart("M60 26v54", fill = 0xFF000000, stroke = 0xFF2E5BFF, width = 1.4f, dash = floatArrayOf(3f, 3f)),
        ArtPart("M56 30l4-4 4 4M56 76l4 4 4-4", stroke = 0xFF2E5BFF, width = 1.4f),
        ArtPart("M92 84h34l-6 6H98z", fill = 0xFFFFFFFF, stroke = 0xFF14161B, width = 1.4f, join = StrokeJoin.Round),
        ArtPart("M108 84V50", fill = 0xFF000000, stroke = 0xFF14161B, width = 1.6f),
        ArtPart("M108 52l14 30h-14z", fill = 0xFFFFFFFF, stroke = 0xFF14161B, width = 1.2f),
        ArtPart("M106 50l-10 32h10z", fill = 0xFFE4EAFF, stroke = 0xFF14161B, width = 1.2f),
        ArtPart("M108 50V26", fill = 0xFF000000, stroke = 0xFF178A4E, width = 1.4f, dash = floatArrayOf(2f, 3f)),
    ))

    /** Motorbike (Truck). */
    val vMoto = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M2.5 16.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M15.5 16.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M5.5 16.5l4-6h5l4 6M14.5 10.5L13 7h3M9.5 10.5h-3", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Covered bike stands (Twowheel). */
    val rack = Art(0f, 0f, 348f, 130f, listOf(
        ArtPart("M4 20h340", fill = 0xFF000000, stroke = 0xFF15251C, width = 6f, cap = StrokeCap.Round),
        ArtPart("M20 20v96M328 20v96", fill = 0xFF000000, stroke = 0xFF8A929C, width = 4f),
        ArtPart("M26 112V76a14 14 0 0 1 28 0v36", stroke = 0xFF8A929C, width = 6f, cap = StrokeCap.Round),
        ArtPart("M-32 96a16 16 0 1 0 32 0a16 16 0 1 0 -32 0z", stroke = 0xFFC8322A, width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round, dx = 34f, dy = 0f),
        ArtPart("M14 96a16 16 0 1 0 32 0a16 16 0 1 0 -32 0z", stroke = 0xFFC8322A, width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round, dx = 34f, dy = 0f),
        ArtPart("M-16 96l14-26h24l8 26M-2 70l-4-8h-6M22 70l-10 26M-2 70l14 26M22 70l2-8h8", stroke = 0xFFC8322A, width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round, dx = 34f, dy = 0f),
        ArtPart("M84 112V76a14 14 0 0 1 28 0v36", stroke = 0xFF8A929C, width = 6f, cap = StrokeCap.Round),
        ArtPart("M-32 96a16 16 0 1 0 32 0a16 16 0 1 0 -32 0z", stroke = 0xFF2E6BFF, width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round, dx = 92f, dy = 0f),
        ArtPart("M14 96a16 16 0 1 0 32 0a16 16 0 1 0 -32 0z", stroke = 0xFF2E6BFF, width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round, dx = 92f, dy = 0f),
        ArtPart("M-16 96l14-26h24l8 26M-2 70l-4-8h-6M22 70l-10 26M-2 70l14 26M22 70l2-8h8", stroke = 0xFF2E6BFF, width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round, dx = 92f, dy = 0f),
        ArtPart("M142 48h28a8 8 0 0 1 8 8v56a8 8 0 0 1 -8 8h-28a8 8 0 0 1 -8 -8v-56a8 8 0 0 1 8 -8z", fill = 0xFF7CFF6B, alpha = 0.25f),
        ArtPart("M142 112V76a14 14 0 0 1 28 0v36", stroke = 0xFF8A929C, width = 6f, cap = StrokeCap.Round),
        ArtPart("M200 112V76a14 14 0 0 1 28 0v36", stroke = 0xFF8A929C, width = 6f, cap = StrokeCap.Round),
        ArtPart("M-32 96a16 16 0 1 0 32 0a16 16 0 1 0 -32 0z", stroke = 0xFF15251C, width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round, dx = 208f, dy = 0f),
        ArtPart("M14 96a16 16 0 1 0 32 0a16 16 0 1 0 -32 0z", stroke = 0xFF15251C, width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round, dx = 208f, dy = 0f),
        ArtPart("M-16 96l14-26h24l8 26M-2 70l-4-8h-6M22 70l-10 26M-2 70l14 26M22 70l2-8h8", stroke = 0xFF15251C, width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round, dx = 208f, dy = 0f),
        ArtPart("M258 48h28a8 8 0 0 1 8 8v56a8 8 0 0 1 -8 8h-28a8 8 0 0 1 -8 -8v-56a8 8 0 0 1 8 -8z", fill = 0xFF7CFF6B, alpha = 0.25f),
        ArtPart("M258 112V76a14 14 0 0 1 28 0v36", stroke = 0xFF8A929C, width = 6f, cap = StrokeCap.Round),
        ArtPart("M316 112V76a14 14 0 0 1 28 0v36", stroke = 0xFF8A929C, width = 6f, cap = StrokeCap.Round),
        ArtPart("M-32 96a16 16 0 1 0 32 0a16 16 0 1 0 -32 0z", stroke = 0xFFE8922A, width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round, dx = 324f, dy = 0f),
        ArtPart("M14 96a16 16 0 1 0 32 0a16 16 0 1 0 -32 0z", stroke = 0xFFE8922A, width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round, dx = 324f, dy = 0f),
        ArtPart("M-16 96l14-26h24l8 26M-2 70l-4-8h-6M22 70l-10 26M-2 70l14 26M22 70l2-8h8", stroke = 0xFFE8922A, width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round, dx = 324f, dy = 0f),
        ArtPart("M0 116h348", fill = 0xFF000000, stroke = 0xFF15251C, width = 3f),
    ))

    /** The city behind the gantry (Go). */
    val skyline = Art(0f, 0f, 412f, 150f, listOf(
        ArtPart("M0 150V92h18V70h22v30h14V58h10v-8h12v8h8v42h16V80h26v70zM150 150V96h20V64h8V50h6v14h8v36h18V84h22v66zM260 150V74h16V60h24v26h12V40h6v-8h4v8h6v60h16V88h22v62z", fill = 0xFF19222F),
    ))

    /** The gantry's truss (Go). */
    val gantry = Art(0f, 0f, 412f, 44f, listOf(
        ArtPart("M-4 4h420v3h-420z", gradient = Gradient(0f, 0f, 0f, 1f, listOf(0f to 0xFFB7C0CB, 1f to 0xFF6F7986))),
        ArtPart("M-4 17h420v3h-420z", gradient = Gradient(0f, 0f, 0f, 1f, listOf(0f to 0xFFB7C0CB, 1f to 0xFF6F7986))),
        ArtPart("M-4 7l10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10 10-10 10 10", stroke = 0xFF8C96A3, width = 1.4f),
        ArtPart("M52 20v24M150 20v24M258 20v24M356 20v24", fill = 0xFF000000, stroke = 0xFF8C96A3, width = 3f),
        ArtPart("M50.2 40a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", fill = 0xFF5B6470),
        ArtPart("M148.2 40a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", fill = 0xFF5B6470),
        ArtPart("M256.2 40a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", fill = 0xFF5B6470),
        ArtPart("M354.2 40a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", fill = 0xFF5B6470),
    ))
}
