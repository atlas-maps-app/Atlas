// The façade's native side (plan-vector T9, Appendix D): the JNI entry points of app.atlas.mapcore.Native, registered
// by JNI_OnLoad (jni/Registry.cpp) after the class's ABI matches the library's, and the layouts both sides share.
#pragma once

#include "standin/FrameRecord.hpp"

#include <jni.h>

#include <cstdint>
#include <span>
#include <string>

namespace atlas::jni {

/// The façade's ABI (MapCore.ABI and Native.ABI in Kotlin): an APK built against another core refuses to load it. 2:
/// Native.resetGuard (VK facts' "Reset guard"). 3: Native.create and Native.facts take the app's process exits and
/// this process's start, which the crash guard reads a marker by (gpu/Guard.hpp). 4: Native.map, packs and moment
/// (the app's map, K11). 5: Native.painter (the renderer's painter's order, to compare with true depth). 6:
/// Native.labels (the map's labels drawn by the core, ENGINE.md step 2). 7: Native.screen (the screen's haze and
/// vignette on or off, the app's Look settings). 8: Native.labels' block holds each label at the band's top as well
/// (LBL2), which a grown label is drawn from. 9: Native.twinLine (VK facts' twin check, frame/TwinCheck.hpp). 10:
/// Native.compositeLine (VK facts' composite check, frame/CompositeCheck.hpp). 11: Native.wantedWindows, windowBox
/// and buildWindow (the windows the phone builds, ENGINE.md step 3). 12: Native.windowsInView and mergeWindow (a
/// window built again from Overpass's diff since its data's date). 13: Native.buildWindow and mergeWindow take the
/// window's data as files, which the core maps. 14: Native.windowVersion (a window of another version is built again
/// from its kept data). 15: Native.screen takes the paper grain as well (the app's Look settings). 16:
/// Native.editWindow (the user's own edits written into a window's data, ENGINE.md step 6). 17: the camera state's
/// version 2, 544 bytes, with the location the map shows (its puck drawn by the core, ENGINE.md step 6). 18:
/// Native.markers (the map's markers drawn by the core, ENGINE.md step 6). 19: Native.routes (the map's routes and
/// areas drawn by the core). 20: Native.pick (the building under a point of the view, with its OSM id).
inline constexpr jint abi = 20;

/// The Kotlin class whose static natives the library registers.
inline constexpr const char* native_class = "app/atlas/mapcore/Native";

/// The natives of Native, as RegisterNatives takes them.
[[nodiscard]] std::span<const JNINativeMethod> facade_methods() noexcept;

/// Registers the façade's natives on `cls` after checking its static int field ABI; "" on success, else the refusal.
[[nodiscard]] std::string register_facade(JNIEnv* env, jclass cls);

/// The offset tables of the layouts the façade shares with Kotlin (the camera state, the frame records), as text:
/// "<layout> version <v> size <n>" then "<layout>.<field> <offset> <size>" lines.
[[nodiscard]] std::string layout_tables();

/// One frame as the core reports it to VK bench (Native.records, MK/FrameRecords.kt).
using standin::FrameRecord;
namespace frame_flag = standin::frame_flag;

} // namespace atlas::jni
