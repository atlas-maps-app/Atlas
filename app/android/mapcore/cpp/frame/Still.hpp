// Whether a frame of the app's map is its final picture at its camera, the sun's shadows in: the question a test or a
// tool asks before it reads a frame (tests/frame/palette_vk_test.cpp's settling, tests/frame/look_compare_vk_test.cpp,
// jni/TestLook.cpp's capture for :mapcorecheck's likeness). The map's own picture is complete without the sun
// (MapFrame::complete): no frame waits for it, a tile is drawn by day until its bits come, and they cross-fade in over
// 300 ms (scene/SunGlobals.hpp). complete() stays the picture the app shows and reports; nothing in the app asks this,
// which waits for the sun's workers.
#pragma once

#include <array>
#include <cstdint>

namespace atlas::frame {

class MapFrame; // frame/MapFrame.hpp

/// One frame's stillness: made just before the frame is drawn, asked once it is.
class Still {
public:
    /// Before the frame: the sun's work so far.
    explicit Still(MapFrame& map);

    /// After it: whether that frame is complete (MapFrame::complete) and the sun is done with it. Waits for the sun's
    /// workers to be idle, then asks that the sun did nothing since the frame began (no scene built or made whole, no
    /// bucket run: the frame drew every bit published before it and asked for no more), that no tile cross-fades to its
    /// bits, and that none of the active pack's tiles is drawn by day while its lane holds bits for it (bits published
    /// after the frame took its lane's, or held back: SunGlobals::hold).
    [[nodiscard]] bool final_frame();

private:
    MapFrame& map_;
    /// The sun's scenes built, scenes made whole and buckets run before the frame (scene::SunStats).
    std::array<std::uint64_t, 3> before_{};
};

} // namespace atlas::frame
