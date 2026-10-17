// The layouts the façade shares with Kotlin, as offset tables (plan-vector T9): the camera state (defined once, in
// style/CameraState.hpp, K5) and the frame records VK bench reads. MT/facade/FacadeLayoutTest.kt asks the host build
// for these tables and holds MK/state/CameraWriter.kt and MK/FrameRecords.kt to them.
#include "style/Layouts.hpp"

#include "jni/Bridge.hpp"
#include "style/CameraState.hpp"

#include <cstddef>
#include <format>

namespace atlas::jni {

namespace {

#define ATLAS_RECORD_FIELD(member) {#member, offsetof(FrameRecord, member), sizeof(FrameRecord::member)}

struct RecordField {
    const char* name;
    std::size_t offset;
    std::size_t size;
};

constexpr RecordField record_fields[] = {
    ATLAS_RECORD_FIELD(frame),        ATLAS_RECORD_FIELD(vsync_ns),
    ATLAS_RECORD_FIELD(start_ns),     ATLAS_RECORD_FIELD(end_ns),
    ATLAS_RECORD_FIELD(presented_ns), ATLAS_RECORD_FIELD(desired_ns),
    ATLAS_RECORD_FIELD(gpu_frame),    ATLAS_RECORD_FIELD(gpu_ms),
    ATLAS_RECORD_FIELD(gpu_main_ms),  ATLAS_RECORD_FIELD(gpu_offscreen_ms),
    ATLAS_RECORD_FIELD(gpu_after_ms), ATLAS_RECORD_FIELD(record_ms),
    ATLAS_RECORD_FIELD(prepare_ms),   ATLAS_RECORD_FIELD(draws),
    ATLAS_RECORD_FIELD(triangles_k),  ATLAS_RECORD_FIELD(fragments_k),
    ATLAS_RECORD_FIELD(flags),
};

#undef ATLAS_RECORD_FIELD

// The fields tile the record: each starts where the last ended, and the last ends at its size.
constexpr bool tiles() {
    std::size_t at = 0;
    for (const RecordField& f : record_fields) {
        if (f.offset != at) return false;
        at += f.size;
    }
    return at == sizeof(FrameRecord);
}
static_assert(tiles());

} // namespace

std::string layout_tables() {
    std::string out =
        std::format("camera_state version {} size {}\n", style::camera_state_version, style::camera_state_size);
    for (const style::Field& f : style::camera_state_fields())
        out += std::format("camera_state.{} {} {}\n", f.name, f.offset, f.size);
    out += std::format("frame_record version {} size {}\n", FrameRecord::version, sizeof(FrameRecord));
    for (const RecordField& f : record_fields) out += std::format("frame_record.{} {} {}\n", f.name, f.offset, f.size);
    return out;
}

} // namespace atlas::jni
