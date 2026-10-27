// The camera state's reader.
#include "style/CameraState.hpp"

#include "base/pymath/PyMath.hpp"

#include <array>
#include <bit>
#include <cmath>
#include <cstring>

namespace atlas::style {

static_assert(std::endian::native == std::endian::little,
              "the blocks are little-endian, as every Android ABI and host is");

namespace {

/// lo <= v <= hi (false for NaN).
[[nodiscard]] constexpr bool within(double v, double lo, double hi) noexcept { return v >= lo && v <= hi; }

/// A dp size is the px size over the density, rounded either way: |px - dp * density| <= density.
[[nodiscard]] bool dp_of(std::uint32_t px, std::uint32_t dp, double density) noexcept {
    return dp > 0U && std::abs(static_cast<double>(px) - (static_cast<double>(dp) * density)) <= density;
}

/// Every byte of an unused slot is zero, so a stale shape or location can never be read as one in use.
template <class T>
[[nodiscard]] bool cleared(const T& k) noexcept {
    std::array<std::byte, sizeof(T)> raw{};
    std::memcpy(raw.data(), &k, sizeof k);
    for (const std::byte x : raw) {
        if (x != std::byte{0}) {
            return false;
        }
    }
    return true;
}

/// The location the map shows: within the globe, an accuracy of 0 to 100 km, a heading within ten turns of north (0
/// without one), its reserved words 0.
[[nodiscard]] std::expected<void, CameraError> check_location(const Location& l, bool heading) noexcept {
    if (!std::isfinite(l.latitude) || !std::isfinite(l.longitude) || !std::isfinite(l.accuracy) ||
        !std::isfinite(l.heading)) {
        return std::unexpected(CameraError::not_finite);
    }
    if (l.reserved0 != 0U || l.reserved1 != 0U) {
        return std::unexpected(CameraError::reserved);
    }
    if (!within(l.latitude, -90.0, 90.0) || !within(l.longitude, -180.0, 180.0) ||
        !within(static_cast<double>(l.accuracy), 0.0, 1e5) ||
        !within(static_cast<double>(l.heading), heading ? -3600.0 : 0.0, heading ? 3600.0 : 0.0)) {
        return std::unexpected(CameraError::location);
    }
    return {};
}

/// A shape in use: finite, with area, within one frame's size of the frame, a radius at most half its shorter side
/// (and half a dp for rounding), a blur up to 256 dp (its flags, frosted and the fade, take every value).
[[nodiscard]] std::expected<void, CameraError> check_shape(const KeepOut& k, double width, double height) noexcept {
    const std::array box = {k.left, k.top, k.right, k.bottom, k.radius};
    for (const float v : box) {
        if (!std::isfinite(v)) {
            return std::unexpected(CameraError::not_finite);
        }
    }
    const auto left = static_cast<double>(k.left);
    const auto top = static_cast<double>(k.top);
    const auto right = static_cast<double>(k.right);
    const auto bottom = static_cast<double>(k.bottom);
    if (!(right > left) || !(bottom > top) || !within(left, -width, 2.0 * width) ||
        !within(right, -width, 2.0 * width) || !within(top, -height, 2.0 * height) ||
        !within(bottom, -height, 2.0 * height)) {
        return std::unexpected(CameraError::keep_outs);
    }
    const double shorter = pymath::min(right - left, bottom - top);
    if (!within(static_cast<double>(k.radius), 0.0, (shorter / 2.0) + 0.5) || k.blur16 > 4096U) {
        return std::unexpected(CameraError::keep_outs);
    }
    return {};
}

} // namespace

std::expected<CameraState, CameraError> read_camera_state(std::span<const std::byte> bytes) noexcept {
    if (bytes.size() != camera_state_size) {
        return std::unexpected(CameraError::size);
    }
    CameraState s{};
    std::memcpy(&s, bytes.data(), sizeof s);
    if (s.version != camera_state_version) {
        return std::unexpected(CameraError::version);
    }
    if ((s.flags & ~camera_flag::known) != 0U || (s.has(camera_flag::heading) && !s.has(camera_flag::location))) {
        return std::unexpected(CameraError::flags);
    }
    if (s.reserved != 0U) {
        return std::unexpected(CameraError::reserved);
    }
    if (static_cast<std::uint32_t>(s.profile) > static_cast<std::uint32_t>(Profile::world_base)) {
        return std::unexpected(CameraError::profile);
    }
    const std::array doubles = {s.latitude, s.longitude,   s.bearing,     s.pitch,
                                s.distance, s.principal_x, s.principal_y, s.density};
    for (const double v : doubles) {
        if (!std::isfinite(v)) {
            return std::unexpected(CameraError::not_finite);
        }
    }
    if (!within(s.latitude, -90.0, 90.0) || !within(s.longitude, -180.0, 180.0)) {
        return std::unexpected(CameraError::target);
    }
    if (!within(s.bearing, -3600.0, 3600.0) || !within(s.pitch, 0.0, 90.0) || !within(s.distance, 0.6, 1e9)) {
        return std::unexpected(CameraError::view);
    }
    if (s.width_px == 0U || s.height_px == 0U || s.width_px > 16384U || s.height_px > 16384U || s.density <= 0.25 ||
        s.density >= 16.0 || !dp_of(s.width_px, s.width_dp, s.density) || !dp_of(s.height_px, s.height_dp, s.density)) {
        return std::unexpected(CameraError::frame);
    }
    const double width = s.width_dp;
    const double height = s.height_dp;
    if (!within(s.principal_x, -width, 2.0 * width) || !within(s.principal_y, -height, 2.0 * height)) {
        return std::unexpected(CameraError::principal);
    }
    if (s.keep_out_count > max_keep_outs) {
        return std::unexpected(CameraError::keep_outs);
    }
    for (std::size_t i = 0; i < max_keep_outs; ++i) {
        const KeepOut& k = s.keep_out[i];
        if (i >= s.keep_out_count) {
            if (!cleared(k)) {
                return std::unexpected(CameraError::reserved);
            }
            continue;
        }
        if (auto shape = check_shape(k, width, height); !shape) {
            return std::unexpected(shape.error());
        }
    }
    if (!s.has(camera_flag::location)) {
        if (!cleared(s.location)) {
            return std::unexpected(CameraError::reserved);
        }
    } else if (auto where = check_location(s.location, s.has(camera_flag::heading)); !where) {
        return std::unexpected(where.error());
    }
    return s;
}

const char* name_of(CameraError error) noexcept {
    switch (error) {
    case CameraError::size: return "size";
    case CameraError::version: return "version";
    case CameraError::flags: return "flags";
    case CameraError::reserved: return "reserved";
    case CameraError::profile: return "profile";
    case CameraError::keep_outs: return "keep_outs";
    case CameraError::frame: return "frame";
    case CameraError::not_finite: return "not_finite";
    case CameraError::target: return "target";
    case CameraError::view: return "view";
    case CameraError::principal: return "principal";
    case CameraError::location: return "location";
    }
    return "unknown";
}

} // namespace atlas::style
