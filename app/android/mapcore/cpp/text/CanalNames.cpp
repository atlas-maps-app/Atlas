#include "text/CanalNames.hpp"

#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Container.hpp"
#include "tiles/layers/Labels.hpp"

#include <algorithm>
#include <unordered_set>

namespace atlas::text {

namespace {

/// amsterdam.py's far: the point lies beyond the renders' radius from the view's target.
[[nodiscard]] bool far(const CanalView& view, double x, double z) noexcept {
    return pymath::hypot(x - view.target_x, z - view.target_z) > view.radius;
}

/// The canal names' run of `c` (amsterdam.py:735-740), or nothing where ground_text draws none.
[[nodiscard]] std::optional<TextRun> canal_run(const CanalCandidate& c, const CanalView& view) {
    double ux = c.mid_x - c.prev_x;
    double uz = c.mid_z - c.prev_z;
    double length = pymath::hypot(ux, uz);
    if (length == 0.0) length = 1.0;
    ux /= length;
    uz /= length;
    const double y = view.weather ? view.water_level + 0.05 : 0.1;
    return ground_text(*view.camera, upper(c.name), {c.mid_x, y, c.mid_z}, {ux, 0.0, uz}, {-uz, 0.0, ux}, 3.2,
                       0xEAF2FAU, 0.85, 700, 0.3);
}

[[nodiscard]] bool drawn_at_all(const CanalView& view) noexcept { return !view.night && view.pitch > 20; }

[[nodiscard]] float ramp(double t) noexcept {
    const double f = t / CanalNames::fade_seconds;
    return static_cast<float>(f > 0.0 ? (f < 1.0 ? f : 1.0) : 0.0);
}

} // namespace

void canal_candidates(const tiles::Layer& labels, const tiles::Frame& frame, std::vector<CanalCandidate>& out) {
    namespace lb = tiles::label;
    for (std::uint32_t i = 0; i < labels.count; ++i) {
        if (labels.integer(lb::source, i) != lb::source_canal) continue;
        const tiles::GridPoint mid = labels.point(lb::pos, i);
        const tiles::GridPoint prev = labels.point(lb::prev, i);
        out.push_back({.index = static_cast<std::uint32_t>(labels.integer(lb::index, i)),
                       .name = std::string(labels.string(lb::name, i).value_or("")),
                       .mid_x = frame.x(mid.lon),
                       .mid_z = frame.z(mid.lat),
                       .prev_x = frame.x(prev.lon),
                       .prev_z = frame.z(prev.lat),
                       .count = static_cast<std::uint32_t>(labels.integer(lb::count, i)),
                       .ground_key = static_cast<std::uint32_t>(labels.integer(lb::ground_key, i))});
    }
}

tiles::Result<PackCanals> pack_canals(const tiles::Pack& pack) {
    PackCanals out;
    const tiles::RenderHead* head = pack.head();
    if (head == nullptr || head->levels.empty()) return out;
    if (const tiles::StyleTables* style = pack.style()) {
        if (const auto state = style->table("state")) {
            if (const auto level = state->find("water_y")) out.water_level = level->number().value_or(-1.5);
        }
    }
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    const std::uint8_t finest = head->levels.front();
    for (const tiles::ChunkRef& c : pack.chunks().chunks()) {
        if (c.tile.z != finest) continue;
        Arena arena(std::size_t{1} << 20U, Growth::grows, "canal names");
        auto tile = pack.decode(c, zstd, decoder, arena, raw, scratch);
        if (!tile) return std::unexpected(tile.error());
        if (const tiles::Layer* labels = tile->layer(tiles::LayerId::label)) {
            canal_candidates(*labels, head->frame, out.candidates);
        }
    }
    std::ranges::sort(out.candidates, {}, &CanalCandidate::index);
    const auto [first, last] = std::ranges::unique(out.candidates, {}, &CanalCandidate::index);
    out.candidates.erase(first, last);
    return out;
}

std::vector<RuleName> canal_rule(std::span<const CanalCandidate> candidates, const CanalView& view) {
    std::vector<RuleName> out;
    if (!drawn_at_all(view) || view.camera == nullptr) return out;
    std::unordered_set<std::string> seen;
    for (std::uint32_t i = 0; i < candidates.size(); ++i) {
        const CanalCandidate& c = candidates[i];
        if (seen.contains(c.name)) continue;
        if (far(view, c.mid_x, c.mid_z) || c.count < 2) continue;
        if (auto run = canal_run(c, view)) {
            out.push_back({.run = std::move(*run), .candidate = i});
            seen.insert(c.name);
        }
    }
    return out;
}

bool beyond(const std::array<double, 6>& matrix, double cosine) noexcept {
    const double length = pymath::hypot(matrix[0], matrix[1]);
    return length > 0.0 && matrix[0] / length < cosine;
}

void CanalNames::set_candidates(std::vector<CanalCandidate> candidates) {
    std::ranges::sort(candidates, {}, &CanalCandidate::index);
    const auto [first, last] = std::ranges::unique(candidates, {}, &CanalCandidate::index);
    candidates.erase(first, last);
    // The states name candidates by position: keep each name's way where it still is.
    std::vector<std::uint32_t> moved(candidates_.size(), UINT32_MAX);
    for (std::uint32_t i = 0; i < candidates_.size(); ++i) {
        const auto at = std::ranges::lower_bound(candidates, candidates_[i].index, {}, &CanalCandidate::index);
        if (at != candidates.end() && at->index == candidates_[i].index) {
            moved[i] = static_cast<std::uint32_t>(at - candidates.begin());
        }
    }
    for (State& s : states_) {
        if (s.present) {
            if (moved[s.candidate] == UINT32_MAX) {
                s.present = false;
            } else {
                s.candidate = moved[s.candidate];
            }
        }
        if (s.leaving) {
            if (moved[s.left] == UINT32_MAX) {
                s.leaving = false;
            } else {
                s.left = moved[s.left];
            }
        }
    }
    std::erase_if(states_, [](const State& s) { return !s.present && !s.leaving; });
    candidates_ = std::move(candidates);
}

std::optional<TextRun> CanalNames::run_of(const CanalCandidate& c, const CanalView& view) const {
    if (far(view, c.mid_x, c.mid_z) || c.count < 2) return std::nullopt;
    return canal_run(c, view);
}

std::span<const PlacedName> CanalNames::update(const CanalView& view) {
    const double now = view.seconds;
    const bool first = !started_;
    started_ = true;
    placed_.clear();
    fading_ = false;
    const std::vector<RuleName> rule = canal_rule(candidates_, view);

    // Which way each name takes this frame: at rest the rule's. Moving, its own while the rule could still draw it
    // there (its middle within the radius, its map whole); a name whose way no longer can, or that comes, takes the
    // way of its name nearest the view's target among those the rule could draw within nine tenths of the radius: the
    // one that will hold the longest as the camera goes on, and never one at the radius's edge, so that a name does
    // not hop there. The screen plays no part, as in the rule: a moving frame keeps the names the rest frame drew.
    std::vector<std::pair<std::string, std::uint32_t>> chosen; // name, candidate
    chosen.reserve(rule.size());
    if (view.rest || !drawn_at_all(view)) {
        for (const RuleName& r : rule) chosen.emplace_back(candidates_[r.candidate].name, r.candidate);
    } else {
        auto drawable = [&](std::uint32_t c) { return run_of(candidates_[c], view).has_value(); };
        for (const State& s : states_) {
            if (s.present && drawable(s.candidate)) chosen.emplace_back(s.name, s.candidate);
        }
        for (const RuleName& r : rule) {
            const std::string& name = candidates_[r.candidate].name;
            if (std::ranges::find(chosen, name, &std::pair<std::string, std::uint32_t>::first) != chosen.end()) {
                continue;
            }
            std::uint32_t best = UINT32_MAX;
            double nearest = 0.0;
            for (std::uint32_t c = 0; c < candidates_.size(); ++c) {
                const CanalCandidate& k = candidates_[c];
                if (k.name != name) continue;
                const double d = pymath::hypot(k.mid_x - view.target_x, k.mid_z - view.target_z);
                if (d > view.radius * 0.9 || (best != UINT32_MAX && d >= nearest) || !drawable(c)) continue;
                best = c;
                nearest = d;
            }
            if (best != UINT32_MAX) chosen.emplace_back(name, best);
        }
    }

    // The states follow the choices: a new way fades in as the old fades out; a name no longer chosen fades out.
    for (State& s : states_) {
        const auto at = std::ranges::find(chosen, s.name, &std::pair<std::string, std::uint32_t>::first);
        if (at == chosen.end()) {
            if (s.present) {
                s.leaving = true;
                s.left = s.candidate;
                s.left_turned = s.turned;
                s.left_at = now;
                s.present = false;
            }
            continue;
        }
        if (!s.present) {
            s.present = true;
            s.candidate = at->second;
            s.since = now;
        } else if (s.candidate != at->second) {
            s.leaving = true;
            s.left = s.candidate;
            s.left_turned = s.turned;
            s.left_at = now;
            s.candidate = at->second;
            s.since = now;
            ++changes_;
        }
    }
    for (const auto& [name, candidate] : chosen) {
        if (std::ranges::any_of(states_, [&](const State& s) { return s.name == name; })) continue;
        // A name coming: at once on the first frame (a rest frame must be the renders' own), faded in after it.
        states_.push_back({.name = name, .candidate = candidate, .present = true, .since = first ? now - 1.0 : now});
    }

    // The frame's names, in their source order, each turned for reading with hysteresis.
    for (State& s : states_) {
        if (s.present) {
            if (auto run = run_of(candidates_[s.candidate], view)) {
                if (!s.turned && beyond(run->matrix, turn_cos)) {
                    s.turned = true;
                } else if (s.turned && !beyond(run->matrix, unturn_cos)) {
                    s.turned = false;
                }
                const float alpha = ramp(now - s.since);
                placed_.push_back({.rule = *run,
                                   .drawn = s.turned ? run->turned() : *run,
                                   .candidate = s.candidate,
                                   .ground_key = candidates_[s.candidate].ground_key,
                                   .turned = s.turned,
                                   .alpha = alpha});
                fading_ = fading_ || alpha < 1.0F;
            }
        }
        if (s.leaving) {
            // The way it left, beyond the radius or not, fading out where the camera still sees it.
            const float alpha = 1.0F - ramp(now - s.left_at);
            const auto run = alpha > 0.0F && drawn_at_all(view) ? canal_run(candidates_[s.left], view) : std::nullopt;
            if (!run) {
                s.leaving = false;
                continue;
            }
            placed_.push_back({.rule = *run,
                               .drawn = s.left_turned ? run->turned() : *run,
                               .candidate = s.left,
                               .ground_key = candidates_[s.left].ground_key,
                               .turned = s.left_turned,
                               .leaving = true,
                               .alpha = alpha});
            fading_ = true;
        }
    }
    std::erase_if(states_, [](const State& s) { return !s.present && !s.leaving; });
    std::ranges::sort(placed_, {}, &PlacedName::candidate);
    return placed_;
}

} // namespace atlas::text
