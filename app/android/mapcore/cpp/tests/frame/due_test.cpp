// Which vsyncs draw the map (frame/Due.hpp, the frame loop's rule in jni/Bridge.cpp): a new camera, or a change the
// core was handed at the camera last written (a category's places, the area's names: on alpha 25 they waited for a
// touch), and nothing at rest otherwise.
#include "frame/Due.hpp"

#include <doctest/doctest.h>

using namespace atlas;

namespace {

/// A vsync at which Kotlin's camera source says nothing moved, the map on and not mid-fade: the map at rest.
[[nodiscard]] bool at_rest(frame::Due& d) { return d.at_vsync(false, true, false); }

} // namespace

TEST_CASE("due: at rest with nothing changed, no frame") {
    frame::Due d;
    d.attached();
    CHECK(d.at_vsync(true, true, false)); // the window's first camera
    CHECK_FALSE(at_rest(d));
    CHECK_FALSE(at_rest(d));
}

TEST_CASE("due: a change handed over at rest draws one frame at the camera last written, then the map rests") {
    frame::Due d;
    d.attached();
    CHECK(d.at_vsync(true, true, false));
    CHECK_FALSE(at_rest(d));
    // A category chosen: its places, handed to the core as markers, ask for a frame with the camera still.
    d.changed();
    CHECK(at_rest(d));
    CHECK_FALSE(at_rest(d));
    // The area's names after the map stopped, the same.
    d.changed();
    CHECK(at_rest(d));
    // Their fade in draws on while it lasts, then nothing.
    CHECK(d.at_vsync(false, true, true));
    CHECK(d.at_vsync(false, true, true));
    CHECK_FALSE(at_rest(d));
    // Several changes before a vsync: one frame.
    d.changed();
    d.changed();
    CHECK(at_rest(d));
    CHECK_FALSE(at_rest(d));
}

TEST_CASE("due: a new camera draws whatever else holds") {
    frame::Due d;
    CHECK(d.at_vsync(true, true, false));
    CHECK(d.at_vsync(true, false, false)); // VK bench's stand-in, the map off
    d.changed();
    CHECK(d.at_vsync(true, true, false)); // the change goes with the camera's frame
    CHECK_FALSE(at_rest(d));
}

TEST_CASE("due: a change before the window's first camera draws nothing, and the camera then draws it") {
    frame::Due d;
    d.attached();
    // The markers and labels handed over as the map binds, before Kotlin wrote any camera: nothing to draw at.
    d.changed();
    CHECK_FALSE(at_rest(d));
    CHECK_FALSE(at_rest(d));
    CHECK(d.at_vsync(true, true, false));
    CHECK_FALSE(at_rest(d));
    // A new window has a new camera buffer: the camera written for the last one is not drawn at.
    d.attached();
    d.changed();
    CHECK_FALSE(at_rest(d));
    CHECK_FALSE(d.at_vsync(false, true, true));
    CHECK(d.at_vsync(true, true, false));
    d.changed();
    CHECK(at_rest(d));
}

TEST_CASE("due: with the map off, only a new camera draws") {
    frame::Due d;
    CHECK(d.at_vsync(true, false, false));
    d.changed();
    CHECK_FALSE(d.at_vsync(false, false, false));
    // taken by that vsync: the map turned on later draws when it is asked again
    CHECK_FALSE(at_rest(d));
    d.changed();
    CHECK(at_rest(d));
}
