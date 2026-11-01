// Which vsyncs draw the map: the frame loop draws when Kotlin's camera source wrote a new camera for the vsync, or
// where the map's picture changed at the same camera (the app handed the core new labels, markers, routes, the building
// asked about or the screen's effects; a tile of its world was published) or is mid-fade, at the camera Kotlin last
// wrote; else it rests, and nothing is drawn until a frame is asked for again. Kotlin's source answers only for what
// Kotlin knows (the camera, the chrome's shapes): at rest it says nothing changed, so what the core was handed must
// count on its own. Until 1 November it did not, and on alpha 25 a category's places and the area's names came only
// once the map was touched (the user: "when you switch categories you need to slighly move the map for th different
// places to appear"). Portable and pure, so the hosts test it; the frame loop (jni/Bridge.cpp) asks it at each vsync.
#pragma once

namespace atlas::frame {

/// Whether a frame is due at a vsync: a new camera, or a change to the picture at the camera last written.
class Due {
public:
    /// The map's picture changed at the same camera (anything the app handed the core, or a world tile published):
    /// the next vsync draws it, at rest too.
    void changed() noexcept { changed_ = true; }
    /// A new window, with a new camera buffer: no camera written yet to draw a change at.
    void attached() noexcept { written_ = false; }
    /// At a vsync: whether to draw, given whether Kotlin wrote a new camera for it (`camera`), whether the app's map is
    /// on (`map`) and whether its picture is mid-fade (`animating`). A change is drawn at the camera last written,
    /// never before the window's first; it is taken by this vsync whether drawn or not (a camera to come draws it
    /// anyway).
    [[nodiscard]] bool at_vsync(bool camera, bool map, bool animating) noexcept {
        if (camera) written_ = true;
        const bool again = map && written_ && (changed_ || animating);
        changed_ = false;
        return camera || again;
    }

private:
    bool changed_ = false;
    bool written_ = false;
};

} // namespace atlas::frame
