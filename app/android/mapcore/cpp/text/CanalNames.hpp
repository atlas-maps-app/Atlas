// Canal names on the water (plan-vector D13, K13; amsterdam.py:729-741): the renders' rule at rest, a placed name kept
// on its way while the camera moves, cross-fades, and the readability flip.
//
// - At rest, the renders' rule: none at night or with the engine's pitch at 20° or less; per name, the first canal way
//   in source order whose middle vertex lies within the radius, anchored at that vertex at the water's level + 0.05 m
//   (0.1 m without weather), the baseline along the segment into it, the name in capitals in Geist in the style's
//   canal names (700, 3.2 m with letter spacing 0.3, #EAF2FA at 0.85), centred, by ground_text; a way whose map
//   degenerates is passed over, so the next way of that name may take it.
// - While moving, a placed name keeps its way while the rule could still draw it there (its middle within the radius,
//   its map whole); then it takes the way of that name nearest the view's target among those the rule could draw
//   within nine tenths of the radius (the one that will hold the longest as the camera goes on, never one at the
//   radius's edge), and a name coming takes its way so too. The screen plays no part, as in the rule, so the first
//   moving frame keeps every name the rest frame drew. A change of way cross-fades over 250 ms, and so does a name
//   coming or going, and the rest frame's own choice (the renders' rule) once the camera stops.
// - Upside down: a name whose baseline points more than 100° from screen right is turned by 180° about its anchor, and
//   back below 80° (Q3, answered: upright with hysteresis); the renders never turn them, so L1 compares the rule's
//   unturned run and counts the turned ones as an intended deviation.
#pragma once

#include "text/GroundText.hpp"
#include "tiles/Error.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Style.hpp"

#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::tiles {
class Pack; // tiles/Container.hpp
} // namespace atlas::tiles

namespace atlas::text {

/// A canal name candidate (the tiles' label layer, source 0): its source order in S['labels'], its name, the way's
/// middle vertex and the one before it in the region's metres (the frame drawn's, once a frame reads it), its vertex
/// count, its slot among the marks; and its source's group, which it is known by with its index: 0 within one pack's
/// own source order (a region's S['labels']), a window's tile within a frame cell's windows (each window is a source of
/// its own, its labels numbered from 0), a pack's own (canal_group) where a frame reads several packs' names. The
/// renders' rule takes the candidates by group, then index.
struct CanalCandidate {
    std::uint64_t group = 0;
    std::uint32_t index = 0;
    std::string name;
    double mid_x = 0.0;
    double mid_z = 0.0;
    double prev_x = 0.0;
    double prev_z = 0.0;
    std::uint32_t count = 0;
    std::uint32_t ground_key = 0;
};

/// A tile's canal name candidates: its label layer's source-0 features, their points in `frame`'s metres.
void canal_candidates(const tiles::Layer& labels, const tiles::Frame& frame, std::vector<CanalCandidate>& out);

/// A render pack's canal names, whole (what the renders' S['labels'] holds of them): every candidate of the label
/// layers of its finest level, by source order, each once; the region's canal water level (the style's state.water_y,
/// amsterdam.py's WATER_Y) and the style's canal names.
struct PackCanals {
    std::vector<CanalCandidate> candidates;
    double water_level = -1.5;
    tiles::CanalNamesStyle look;
};

/// `pack`'s canal names (a pack-level stage T: some milliseconds for the canal ring's 652), or the first tile's error.
/// `windows`: the pack is a frame cell's windows, each tile a source of its own (CanalCandidate::group its tile).
[[nodiscard]] tiles::Result<PackCanals> pack_canals(const tiles::Pack& pack, bool windows = false);

/// The group of a pack's own candidates where a frame reads several packs' names (CanalCandidate::group): its id
/// hashed (FNV-1a), below every window's tile key, so a render pack's names come before the windows'.
[[nodiscard]] std::uint64_t canal_group(std::string_view pack) noexcept;

/// What the canal names read of a frame.
struct CanalView {
    const view::Camera* camera = nullptr;
    /// The radius cut: the view's target and the renders' radius (1e9 where the view has none).
    double target_x = 0.0;
    double target_z = 0.0;
    double radius = 1e9;
    /// The engine's pitch, degrees (90 straight down).
    double pitch = 90.0;
    bool night = false;
    /// The theme has weather (th['wx']): the names sit on the water, else 0.1 m up.
    bool weather = true;
    /// The canal water's level (amsterdam.py's WATER_Y), and the names' look (the pack's style).
    double water_level = -1.5;
    tiles::CanalNamesStyle look;
    bool rest = true;
    /// The frame's time, seconds (for the fades).
    double seconds = 0.0;
};

/// A name as the frame draws it.
struct PlacedName {
    /// The renders' run (unturned), and the run drawn (turned when `turned`).
    TextRun rule;
    TextRun drawn;
    std::uint32_t candidate = 0; ///< index into the candidates
    std::uint32_t ground_key = 0;
    bool turned = false;
    /// The way its name left, fading out (the name's own way is the entry without it).
    bool leaving = false;
    /// 0 to 1, the cross-fade's.
    float alpha = 1.0F;
};

/// The renders' rule alone: each name's run as amsterdam.py draws it at `view`, in the order it draws them, with the
/// candidate it took.
struct RuleName {
    TextRun run;
    std::uint32_t candidate = 0;
};
[[nodiscard]] std::vector<RuleName> canal_rule(std::span<const CanalCandidate> candidates, const CanalView& view);

/// cos 100° and cos 80°: a baseline turned past the first is read upside down, and back once within the second.
inline constexpr double turn_cos = -0.17364817766693033;
inline constexpr double unturn_cos = 0.17364817766693041;

/// Whether a run's baseline, its matrix's first column, points more than `cosine`'s angle from screen right.
[[nodiscard]] bool beyond(const std::array<double, 6>& matrix, double cosine) noexcept;

/// The canal names of the frames, with their history.
class CanalNames {
public:
    /// The fade of a change of way, and of a name coming or going, seconds.
    static constexpr double fade_seconds = 0.25;

    /// The candidates of the loaded tiles, in any order: kept sorted by source order, each index once.
    void set_candidates(std::vector<CanalCandidate> candidates);
    [[nodiscard]] std::span<const CanalCandidate> candidates() const noexcept { return candidates_; }

    /// This frame's names: the renders' rule at rest, each placed name kept on its way while moving, fading.
    [[nodiscard]] std::span<const PlacedName> update(const CanalView& view);

    /// Whether a fade is still running (the frame loop asks for another frame).
    [[nodiscard]] bool fading() const noexcept { return fading_; }

    /// How many times a name has changed its way, over the life of these names.
    [[nodiscard]] std::uint64_t changes() const noexcept { return changes_; }

private:
    /// A name's history: the way it stands on, its turn, and the fade of the way it left.
    struct State {
        std::string name;
        std::uint32_t candidate = 0;
        bool present = false;
        bool turned = false;
        double since = -1e9; ///< when it took its way
        // the way it left, fading out
        bool leaving = false;
        std::uint32_t left = 0;
        bool left_turned = false;
        double left_at = -1e9;
    };

    /// The run of candidate `c` at `view`, or nothing (behind the camera, degenerate, outside the radius).
    [[nodiscard]] std::optional<TextRun> run_of(const CanalCandidate& c, const CanalView& view) const;

    std::vector<CanalCandidate> candidates_;
    std::vector<State> states_;
    std::vector<PlacedName> placed_;
    bool started_ = false;
    bool fading_ = false;
    std::uint64_t changes_ = 0;
};

} // namespace atlas::text
