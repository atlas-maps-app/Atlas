package app.atlas.ui.go

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin

// Generated from the boards' own SVG (canvas/project/*.dc.html) by the go package's art.py: do not edit by hand.

/** The boards' icons and drawn objects for the go package, ported element by element. */
internal object BoardArt {
    /** Voice through headphones (Ride). */
    val rideHeadphones = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M4 15v-3a8 8 0 0 1 16 0v3", stroke = CURRENT, width = 2f, join = StrokeJoin.Round),
        ArtPart("M4.5 14h1.5a1.5 1.5 0 0 1 1.5 1.5v3.5a1.5 1.5 0 0 1 -1.5 1.5h-1.5a1.5 1.5 0 0 1 -1.5 -1.5v-3.5a1.5 1.5 0 0 1 1.5 -1.5z", stroke = CURRENT, width = 2f, join = StrokeJoin.Round),
        ArtPart("M18 14h1.5a1.5 1.5 0 0 1 1.5 1.5v3.5a1.5 1.5 0 0 1 -1.5 1.5h-1.5a1.5 1.5 0 0 1 -1.5 -1.5v-3.5a1.5 1.5 0 0 1 1.5 -1.5z", stroke = CURRENT, width = 2f, join = StrokeJoin.Round),
    ))

    /** Haptic turn cues (Ride). */
    val rideHaptic = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M10 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-4a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round),
        ArtPart("M4.5 8v8M19.5 8v8M2 10v4M22 10v4", stroke = CURRENT, width = 2f, cap = StrokeCap.Round),
    ))

    /** The screen sleeps between cues (Ride). */
    val rideScreenOff = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M8.5 2.5h7a2.5 2.5 0 0 1 2.5 2.5v14a2.5 2.5 0 0 1 -2.5 2.5h-7a2.5 2.5 0 0 1 -2.5 -2.5v-14a2.5 2.5 0 0 1 2.5 -2.5z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round),
        ArtPart("M4 4l16 16", stroke = CURRENT, width = 2f, cap = StrokeCap.Round),
    ))

    /** Offline: routed on the phone (Ride). */
    val cloudCheck = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M7 18a4.5 4.5 0 0 1-.6-9A6 6 0 0 1 18 9.5a4 4 0 0 1-.5 8.5", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M9 14.5l2.2 2.2 4-4.2", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** The big right turn (Ride). */
    val turnRight = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M6 21v-8a4 4 0 0 1 4-4h9", stroke = 0xFFFFFFFF, width = 2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M15 5l4 4-4 4", stroke = 0xFFFFFFFF, width = 2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** The haptic pill's phone (Ride). */
    val hapticSmall = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M10 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-4a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2z", stroke = 0xFF8A8F99, width = 2f, cap = StrokeCap.Round),
        ArtPart("M4.5 8v8M19.5 8v8", stroke = 0xFF8A8F99, width = 2f, cap = StrokeCap.Round),
    ))

    /** A chevron right (Ride). */
    val chevronRight = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M9 5l7 7-7 7", stroke = 0xFFB8F36A, width = 2.6f, cap = StrokeCap.Round),
    ))

    /** Cobbles ahead (Ride). */
    val cobbles = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M4.2 4h5.6a1.2 1.2 0 0 1 1.2 1.2v2.6a1.2 1.2 0 0 1 -1.2 1.2h-5.6a1.2 1.2 0 0 1 -1.2 -1.2v-2.6a1.2 1.2 0 0 1 1.2 -1.2z", fill = 0xFFFFB547),
        ArtPart("M14.2 4h5.6a1.2 1.2 0 0 1 1.2 1.2v2.6a1.2 1.2 0 0 1 -1.2 1.2h-5.6a1.2 1.2 0 0 1 -1.2 -1.2v-2.6a1.2 1.2 0 0 1 1.2 -1.2z", fill = 0xFFFFB547),
        ArtPart("M8.2 11h7.6a1.2 1.2 0 0 1 1.2 1.2v2.6a1.2 1.2 0 0 1 -1.2 1.2h-7.6a1.2 1.2 0 0 1 -1.2 -1.2v-2.6a1.2 1.2 0 0 1 1.2 -1.2z", fill = 0xFFFFB547),
        ArtPart("M4 18h5a1 1 0 0 1 1 1v1a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-1a1 1 0 0 1 1 -1z", fill = 0xFFFFB547),
        ArtPart("M13 18h7a1 1 0 0 1 1 1v1a1 1 0 0 1 -1 1h-7a1 1 0 0 1 -1 -1v-1a1 1 0 0 1 1 -1z", fill = 0xFFFFB547),
    ))

    /** The red wedge of a climb (Ride). */
    val climbWedge = Art(0f, 0f, 40f, 28f, listOf(
        ArtPart("M2 26L38 6V26z", fill = 0xFFFF6B3D),
        ArtPart("M2 26L38 6", fill = 0xFF000000, stroke = 0xFFFFB08F, width = 2f, cap = StrokeCap.Round),
    ))

    /** Wind (Ride). */
    val wind = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M3 8h11a3 3 0 1 0-3-3M3 12h16a3 3 0 1 1-3 3M3 16h7", stroke = CURRENT, width = 2f, cap = StrokeCap.Round),
    ))

    /** End (Ride). */
    val close = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M6 6l12 12M18 6L6 18", stroke = CURRENT, width = 2.4f, cap = StrokeCap.Round),
    ))

    /** The big left turn (Drive). */
    val turnLeft = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M18 21v-8a4 4 0 0 0-4-4H5", stroke = 0xFFFFFFFF, width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M9 5L5 9l4 4", stroke = 0xFFFFFFFF, width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** A lane turning left (Drive). */
    val laneLeft = Art(0f, 0f, 20f, 24f, listOf(
        ArtPart("M12 23v-9a4 4 0 0 0-4-4H4M7 6.5L3.5 10 7 13.5", stroke = CURRENT, width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** A lane going straight (Drive). */
    val laneStraight = Art(0f, 0f, 16f, 24f, listOf(
        ArtPart("M8 23V3M3.5 7.5L8 3l4.5 4.5", stroke = CURRENT, width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** The destination flag (Drive). */
    val flag = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M5 21V4M5 4h11l-2.5 4L16 12H5", stroke = 0xFFFF8A4C, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** On the left (Drive). */
    val chevronLeft = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M15 5l-7 7 7 7", stroke = CURRENT, width = 2.4f, cap = StrokeCap.Round),
    ))

    /** Voice guidance (Drive). */
    val voice = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M4 9.5h3.5L12 5.5v13l-4.5-4H4z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M15.5 9a4.5 4.5 0 0 1 0 6M18 6.5a8 8 0 0 1 0 11", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Report a hazard (Drive). */
    val hazard = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M12 4L2.8 20h18.4L12 4z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M12 10v4.5M12 17.2v.3", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Route overview (Drive). */
    val overview = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M3 6.5l6-2.5 6 2.5 6-2.5v13.5l-6 2.5-6-2.5-6 2.5z", stroke = CURRENT, width = 1.9f, join = StrokeJoin.Round),
        ArtPart("M9 4v13.5M15 6.5V20", stroke = CURRENT, width = 1.9f, join = StrokeJoin.Round),
    ))

    /** Road works sign (Drive). */
    val roadWorks = Art(0f, 0f, 32f, 30f, listOf(
        ArtPart("M16 2L30.5 27.5H1.5z", fill = 0xFFFF8A1F, stroke = 0xFFFFFFFF, width = 2f, join = StrokeJoin.Round),
        ArtPart("M11 22l3-7 3 2 2-4 3 9z", fill = 0xFF1A1206),
    ))

    /** Then (Drive). */
    val arrowRight = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M5 12h14M13 6l6 6-6 6", stroke = 0xFFA9B1C7, width = 2f, cap = StrokeCap.Round),
    ))

    /** A truck (Drive). */
    val truck = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M2.5 6.5h11v10h-11z", stroke = 0xFFFFFFFF, width = 1.8f, join = StrokeJoin.Round),
        ArtPart("M13.5 10h4l3 3.5v3h-7", stroke = 0xFFFFFFFF, width = 1.8f, join = StrokeJoin.Round),
        ArtPart("M4.7 17.5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", stroke = 0xFFFFFFFF, width = 1.8f, join = StrokeJoin.Round),
        ArtPart("M15.2 17.5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", stroke = 0xFFFFFFFF, width = 1.8f, join = StrokeJoin.Round),
    ))

    /** Fits (Drive). */
    val check = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M5 12.5l4.5 4.5L19 7.5", stroke = 0xFFFFFFFF, width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** You, driving (Drive). */
    val vehicleArrow = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M12 2.5l7.5 18.5-7.5-4.5-7.5 4.5z", fill = 0xFF2E5BFF),
    ))

    /** Speaking (Drive). */
    val waveform = Art(0f, 0f, 36f, 20f, listOf(
        ArtPart("M1.5 7h0a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1 -1.5 1.5h0a1.5 1.5 0 0 1 -1.5 -1.5v-3a1.5 1.5 0 0 1 1.5 -1.5z", fill = 0xFF8FB0FF),
        ArtPart("M7.5 3h0a1.5 1.5 0 0 1 1.5 1.5v11a1.5 1.5 0 0 1 -1.5 1.5h0a1.5 1.5 0 0 1 -1.5 -1.5v-11a1.5 1.5 0 0 1 1.5 -1.5z", fill = 0xFF8FB0FF),
        ArtPart("M13.5 0h0a1.5 1.5 0 0 1 1.5 1.5v17a1.5 1.5 0 0 1 -1.5 1.5h0a1.5 1.5 0 0 1 -1.5 -1.5v-17a1.5 1.5 0 0 1 1.5 -1.5z", fill = 0xFF8FB0FF),
        ArtPart("M19.5 5h0a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1 -1.5 1.5h0a1.5 1.5 0 0 1 -1.5 -1.5v-7a1.5 1.5 0 0 1 1.5 -1.5z", fill = 0xFF8FB0FF),
        ArtPart("M25.5 2h0a1.5 1.5 0 0 1 1.5 1.5v13a1.5 1.5 0 0 1 -1.5 1.5h0a1.5 1.5 0 0 1 -1.5 -1.5v-13a1.5 1.5 0 0 1 1.5 -1.5z", fill = 0xFF8FB0FF),
        ArtPart("M31.5 7h0a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1 -1.5 1.5h0a1.5 1.5 0 0 1 -1.5 -1.5v-3a1.5 1.5 0 0 1 1.5 -1.5z", fill = 0xFF8FB0FF),
    ))

    /** Current or depth under the keel (Sail). */
    val drift = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M12 4v16M6 14l6 6 6-6", stroke = CURRENT, width = 2.2f, cap = StrokeCap.Round),
        ArtPart("M4 7c2-1.5 4-1.5 6 0M14 7c2-1.5 4-1.5 6 0", stroke = CURRENT, width = 2.2f, cap = StrokeCap.Round),
    ))

    /** VHF radio (Sail). */
    val vhf = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M8 7h8a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M9 7L15 2.5M9 12h6M9 15.5h6", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Nautical layers (Sail). */
    val layers = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M12 3l9 5-9 5-9-5 9-5z", stroke = CURRENT, width = 1.9f, join = StrokeJoin.Round),
        ArtPart("M3 13l9 5 9-5", stroke = CURRENT, width = 1.9f, join = StrokeJoin.Round),
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

    /** Your mast (Sail). */
    val mast = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M3 16.5l1.8 3.5h14.4l1.8-3.5H3z", stroke = CURRENT, width = 2f, join = StrokeJoin.Round),
        ArtPart("M12 3v13.5M12 4.5l6 10H12", stroke = CURRENT, width = 2f, join = StrokeJoin.Round),
    ))

    /** Your boat (Sail). */
    val boatMarker = Art(0f, 0f, 26f, 50f, listOf(
        ArtPart("M13 1C21 12 23 26 22 46H4C3 26 5 12 13 1z", fill = 0xFFFFFFFF, stroke = 0xFF14161B, width = 1.6f),
        ArtPart("M13 8C17 16 18 26 17.5 40h-9C8 26 9 16 13 8z", fill = 0xFF2E5BFF),
        ArtPart("M10.6 24a2.4 2.4 0 1 0 4.8 0a2.4 2.4 0 1 0 -4.8 0z", fill = 0xFFFFFFFF),
    ))

    /** Marina (Sail). */
    val marina = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M10 5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round),
        ArtPart("M12 7v14M8 10.5h8M4.5 13a7.5 7.5 0 0 0 15 0", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round),
    ))

    /** Water (Sail). */
    val drop = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M12 3.5s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11z", stroke = CURRENT, width = 2.2f, cap = StrokeCap.Round),
    ))

    /** Fuel (Sail). */
    val fuelSmall = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M5.5 4h7a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1 -1.5 1.5h-7a1.5 1.5 0 0 1 -1.5 -1.5v-14a1.5 1.5 0 0 1 1.5 -1.5z", stroke = CURRENT, width = 2.2f, cap = StrokeCap.Round),
        ArtPart("M4 10h10M14 8l3.5 2.5V17a1.5 1.5 0 0 0 3 0V8.5L18 6", stroke = CURRENT, width = 2.2f, cap = StrokeCap.Round),
    ))

    /** Back (Ears). */
    val back = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M15 5l-7 7 7 7", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Headphones, spatial audio (Ears). */
    val headphones = Art(0f, 0f, 52f, 44f, listOf(
        ArtPart("M6 30V24a20 20 0 0 1 40 0v6", stroke = 0xFFE6FBF6, width = 4f),
        ArtPart("M7 26h2a5 5 0 0 1 5 5v6a5 5 0 0 1 -5 5h-2a5 5 0 0 1 -5 -5v-6a5 5 0 0 1 5 -5z", fill = 0xFFE6FBF6),
        ArtPart("M43 26h2a5 5 0 0 1 5 5v6a5 5 0 0 1 -5 5h-2a5 5 0 0 1 -5 -5v-6a5 5 0 0 1 5 -5z", fill = 0xFFE6FBF6),
    ))

    /** Where am I? (Ears). */
    val whereAmI = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M9 10a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M12 21s-7-6-7-11a7 7 0 0 1 14 0c0 5-7 11-7 11z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Around me (Ears). */
    val aroundMe = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round, dash = floatArrayOf(3f, 3f)),
    ))

    /** Beacon (Ears). */
    val beacon = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M12 3v3M12 18v3M3 12h3M18 12h3", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** The door (WalkLiving). */
    val door = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M6 21V4h12v17M4 21h16", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M13.5 12.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Right turn on the walk banner (WalkLiving). */
    val turnRightWalk = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M5 20v-7a4 4 0 0 1 4-4h10", stroke = 0xFFFFFFFF, width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M15 5l4 4-4 4", stroke = 0xFFFFFFFF, width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Voice on (WalkLiving). */
    val speaker = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M4 9v6h4l5 4V5L8 9H4z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** All turns (WalkLiving). */
    val turnList = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M8 6h13M8 12h13M8 18h13", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M3 6a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M3 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M3 18a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Turn round (OffRouteLiving). */
    val uTurn = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M8 20V9a5 5 0 0 1 10 0v4", stroke = CURRENT, width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M14 10l4 4 4-4", stroke = CURRENT, width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** The destination pin (Route). */
    val pin = Art(0f, 0f, 24f, 28f, listOf(
        ArtPart("M12 27s-10-9-10-16a10 10 0 0 1 20 0c0 7-10 16-10 16z", fill = 0xFFE8622C),
        ArtPart("M8.2 11a3.8 3.8 0 1 0 7.6 0a3.8 3.8 0 1 0 -7.6 0z", fill = 0xFFFFFFFF),
    ))

    /** The destination pin on the map (Route). */
    val pinOutlined = Art(0f, 0f, 24f, 28f, listOf(
        ArtPart("M12 27s-10-9-10-16a10 10 0 0 1 20 0c0 7-10 16-10 16z", fill = 0xFFE8622C, stroke = 0xFFFFFFFF, width = 2f),
        ArtPart("M8.2 11a3.8 3.8 0 1 0 7.6 0a3.8 3.8 0 1 0 -7.6 0z", fill = 0xFFFFFFFF),
    ))

    /** My location (Route). */
    val myLocation = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M4 11l16-7-7 16-2-7-7-2z", stroke = 0xFF2E5BFF, width = 2f, join = StrokeJoin.Round),
    ))

    /** Swap start and destination (Route). */
    val swap = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M7 4v16M3.5 7.5L7 4l3.5 3.5M17 20V4M13.5 16.5L17 20l3.5-3.5", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** When (Route). */
    val clock = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round),
        ArtPart("M12 7.5V12l3 2", stroke = CURRENT, width = 2f, cap = StrokeCap.Round),
    ))

    /** Opens (Route). */
    val chevronDown = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M6 9l6 6 6-6", stroke = CURRENT, width = 2.4f, cap = StrokeCap.Round),
    ))

    /** Route preferences (Route). */
    val sliders = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M4 7h10M18 7h2M4 17h4M12 17h8", stroke = CURRENT, width = 2f, cap = StrokeCap.Round),
        ArtPart("M14 7a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round),
        ArtPart("M8 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round),
    ))

    /** Avoiding tolls (Route). */
    val noTolls = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round),
        ArtPart("M14.5 9.5c-.5-1-1.5-1.5-2.5-1.5-1.5 0-2.5.8-2.5 2s1 1.6 2.5 2 2.5.8 2.5 2-1 2-2.5 2c-1 0-2-.5-2.5-1.5M12 6.5V8M12 16v1.5", stroke = CURRENT, width = 2f, cap = StrokeCap.Round),
        ArtPart("M5 5l14 14", stroke = CURRENT, width = 2f, cap = StrokeCap.Round),
    ))

    /** Car (Route). */
    val carFront = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M5 13l1.6-4.2A2 2 0 0 1 8.5 7.5h7a2 2 0 0 1 1.9 1.3L19 13", stroke = CURRENT, width = 1.9f, join = StrokeJoin.Round),
        ArtPart("M5 13h14a1.5 1.5 0 0 1 1.5 1.5v2a1.5 1.5 0 0 1 -1.5 1.5h-14a1.5 1.5 0 0 1 -1.5 -1.5v-2a1.5 1.5 0 0 1 1.5 -1.5z", stroke = CURRENT, width = 1.9f, join = StrokeJoin.Round),
        ArtPart("M6.5 18v2M17.5 18v2", stroke = CURRENT, width = 1.9f, join = StrokeJoin.Round),
    ))

    /** Bus (Route). */
    val bus = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M8 3.5h8a3 3 0 0 1 3 3v8a3 3 0 0 1 -3 3h-8a3 3 0 0 1 -3 -3v-8a3 3 0 0 1 3 -3z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round),
        ArtPart("M5 11h14M8.5 21l1-3.5M15.5 21l-1-3.5", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round),
    ))

    /** Walk to the stop (Route). */
    val walkSmall = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M11.3 4.5a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z", stroke = CURRENT, width = 2.6f, cap = StrokeCap.Round),
        ArtPart("M10 21l2.2-6.5L10 12l1-4.5 3.2 2.5 3 .8M12.2 14.5L15 21", stroke = CURRENT, width = 2.6f, cap = StrokeCap.Round),
    ))

    /** Bike (Route). */
    val bike = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M2.5 16a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round),
        ArtPart("M14.5 16a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round),
        ArtPart("M6 16l3.5-7h5.5M9.5 9L12 16h-6M15 9l3 7M13.5 6.5H16", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round),
    ))

    /** Climb (Route). */
    val trendUp = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M3 19l7-7 4 3 7-8", stroke = CURRENT, width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M16 7h5v5", stroke = CURRENT, width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Green share (Route). */
    val leaf = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M5 19c0-8 5-13.5 14-14 0 9-5 14-13 14", stroke = CURRENT, width = 2.2f, cap = StrokeCap.Round),
    ))

    /** Walk (Route). */
    val walk = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M11.3 4.5a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round),
        ArtPart("M10 21l2.2-6.5L10 12l1-4.5 3.2 2.5 3 .8M9.3 10.2L7 13M12.2 14.5L15 21", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round),
    ))

    /** Trees on the way (Route). */
    val tree = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M12 3l-6 8.5h3L6 16h12l-3-4.5h3z", stroke = CURRENT, width = 2.2f, join = StrokeJoin.Round),
    ))

    /** Go (Route). */
    val navArrow = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M12 2.5l7.5 18.5-7.5-4.5-7.5 4.5z", fill = CURRENT),
    ))

    /** Who's coming (Route). */
    val sharePerson = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M5.8 8.5a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round),
        ArtPart("M3 19a6 6 0 0 1 12 0", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round),
        ArtPart("M17 8v6M14 11h6", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round),
    ))

    /** Car (Truck). */
    val vCar = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M5 17h14v-4l-2-5H7l-2 5zM3 13h18", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M6.4 17a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M14.4 17a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Motorbike (Truck). */
    val vMoto = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M2.5 16.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M15.5 16.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M5.5 16.5l4-6h5l4 6M14.5 10.5L13 7h3M9.5 10.5h-3", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Bicycle (Truck). */
    val vBicycle = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M2 16.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M15 16.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M5.5 16.5l4-8h6l3 8M9.5 8.5L8 6H6M15.5 8.5l-3 8", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Cargo bike (Truck). */
    val vCargo = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M2 17a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M16 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M5 17l3-8h4l2 8h4M8 9L7 6H5", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M14 9h6a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-6a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** E-scooter (Truck). */
    val vScooter = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M3.5 18a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M15.5 18a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M6 18h9l2-12h3M17 6h-2", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Camper (Truck). */
    val vCamper = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M2 17V8a2 2 0 0 1 2-2h11l5 5v6zM15 6v5h5M5 9h6v3H5z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M5.2 17.5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M15.2 17.5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Truck (Truck). */
    val vTruck = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M2 16V6h11v10M13 9h4l3 4v3h-7", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M4.2 17a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M15.2 17a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Coach (Truck). */
    val vCoach = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M5 4h14a2 2 0 0 1 2 2v9a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-9a2 2 0 0 1 2 -2z", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M3 10h18M7 20v-3M17 20v-3", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Boat (Truck). */
    val vBoat = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M3 16l2 4h14l2-4zM12 3v13M12 4l6 10h-6", stroke = CURRENT, width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
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

    /** Bike stands (Twowheel). */
    val stands = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M6 20V10a6 6 0 0 1 12 0v10", stroke = 0xFF15251C, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Covered stands (Twowheel). */
    val coveredStands = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M3 9l9-5 9 5M6 20V10a6 6 0 0 1 12 0v10", stroke = 0xFF15251C, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Bike lockers (Twowheel). */
    val lockers = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M6 3h12a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z", stroke = 0xFF15251C, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M12 3v18M9 11v2M15 11v2", stroke = 0xFF15251C, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Guarded garage (Twowheel). */
    val garage = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M3 10l9-6 9 6v10H3z", stroke = 0xFF2E6BFF, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M9.5 14a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0z", stroke = 0xFF2E6BFF, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Charging (Twowheel). */
    val bolt = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M13 2L4.5 13.5H11L10 22l8.5-11.5H12z", stroke = 0xFF1E9E5A, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Lock up (Twowheel). */
    val lock = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M7 11V7a5 5 0 0 1 10 0v4", stroke = 0xFF8A5A00, width = 2.2f, cap = StrokeCap.Round),
        ArtPart("M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z", stroke = 0xFF8A5A00, width = 2.2f, cap = StrokeCap.Round),
    ))

    /** The city behind the gantry (Go). */
    val skyline = Art(0f, 0f, 412f, 150f, listOf(
        ArtPart("M0 150V92h18V70h22v30h14V58h10v-8h12v8h8v42h16V80h26v70zM150 150V96h20V64h8V50h6v14h8v36h18V84h22v66zM260 150V74h16V60h24v26h12V40h6v-8h4v8h6v60h16V88h22v62z", fill = 0xFF19222F),
    ))

    /** Search (Go). */
    val search = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M20 20l-3.6-3.6", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Say where (Go). */
    val mic = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M12 3h0a3 3 0 0 1 3 3v5a3 3 0 0 1 -3 3h0a3 3 0 0 1 -3 -3v-5a3 3 0 0 1 3 -3z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M5 11a7 7 0 0 0 14 0M12 18v3", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
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

    /** A guide sign's arrow (Go). */
    val signArrow = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M12 2l7 8h-4.5v12h-5V10H5z", fill = 0xFFFFFFFF),
    ))

    /** Step-free (Go). */
    val stepFree = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M8.4 4.5a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M10 7.5v6h5l2.5 5.5M10 10.5h5", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M7.5 11.5a5 5 0 1 0 7.3 6.2", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Walk (Go). */
    val mWalk = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M11.3 4.5a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M10 21l2.2-6.5L10 12l1-4.5 3.2 2.5 3 .8M12.2 14.5L15 21M11 7.5L7.5 10v3", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Bike (Go). */
    val mBike = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M2 16.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0z", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M15 16.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0z", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M5.5 16.5l4-8h6l3 8M9.5 8.5L8 6H6M15.5 8.5l-3 8", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Car (Go). */
    val mCar = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M5 17h14v-4l-2-5H7l-2 5zM3 13h18", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M6.4 17a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M14.4 17a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Transit (Go). */
    val mTransit = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M8 3h8a3 3 0 0 1 3 3v8a3 3 0 0 1 -3 3h-8a3 3 0 0 1 -3 -3v-8a3 3 0 0 1 3 -3z", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M5 11h14M8.5 21l2-4M15.5 21l-2-4", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M7.7 14a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0z", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M14.7 14a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0z", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Moto (Go). */
    val mMoto = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M2.5 16.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M15.5 16.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M5.5 16.5l4-6h5l4 6M14.5 10.5L13 7h3M9.5 10.5h-3", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Truck (Go). */
    val mTruck = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M2 16V6h11v10M13 9h4l3 4v3h-7", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M4.2 17a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M15.2 17a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Camper (Go). */
    val mCamper = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M2 17V8a2 2 0 0 1 2-2h11l5 5v6zM15 6v5h5M5 9h6v3H5z", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M5.2 17.5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M15.2 17.5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Boat (Go). */
    val mBoat = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M3 16l2 4h14l2-4zM12 3v13M12 4l6 10h-6", stroke = CURRENT, width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Fuel (Go). */
    val fuel = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M4 21V5a2 2 0 0 1 2-2h7a2 2 0 0 1 2 2v16M3 21h13M6.5 7h6v4h-6z", stroke = 0xFFFFFFFF, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M15 9h2a2 2 0 0 1 2 2v6a1.5 1.5 0 0 0 3 0V8l-3-3", stroke = 0xFFFFFFFF, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Charge (Go). */
    val charge = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M13 2L4.5 13.5H11L10 22l8.5-11.5H12z", stroke = 0xFFFFFFFF, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Bike fix (Go). */
    val wrench = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M14.7 6.3a4.5 4.5 0 0 0-5.9 5.9L3 18l3 3 5.8-5.8a4.5 4.5 0 0 0 5.9-5.9l-2.8 2.8-2.9-.3-.3-2.9z", stroke = 0xFFFFFFFF, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Lockers (Go). */
    val padlock = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M6 10h12a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2z", stroke = 0xFFFFFFFF, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M8 10V7a4 4 0 0 1 8 0v3M12 14v3", stroke = 0xFFFFFFFF, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

    /** Road works (Go). */
    val cone = Art(0f, 0f, 24f, 24f, listOf(
        ArtPart("M9.5 4h5l4 15h-13z", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ArtPart("M8 10.5h8M6.8 15h10.4M3 19.5h18", stroke = CURRENT, width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    ))

}
