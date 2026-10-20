// The packs' own stage T (plan-vector D13's canal names; K7's hook): what a pass derives from a whole pack rather than
// from a tile, such as each canal name's first way in source order across the pack's label layer, which a tile's own
// records cannot tell. When a pack becomes the active or the coarser one (scene/World.hpp), a thread of the world's
// runs every pass's build_pack over it once (frame::Pass::build_pack, reading its tiles through PackBuild::tiles) and
// publishes what each kept (PackBuild::keep) as the pack's ResidentPack; the render thread takes it between frames
// and the passes read it in frame::FrameState (active_pack, coarser_pack), like a tile's PassTile::cpu: made on the
// worker, const once published, freed when the world lets the pack go.
#pragma once

#include "frame/Pass.hpp"
#include "tiles/Store.hpp"

#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace atlas::scene {

/// What one pass kept of a pack: its own CPU-side data (cpu_as), and the bytes it holds.
struct PassPack {
    std::shared_ptr<const void> cpu;
    std::size_t cpu_bytes = 0;

    template <class T>
    [[nodiscard]] const T* cpu_as() const noexcept {
        return static_cast<const T*>(cpu.get());
    }
};

/// A pack's stage T, as the render thread sees it once published: per pass of the frame builder (Pass::index), what
/// its build_pack kept.
struct ResidentPack {
    std::string id; ///< the store's id
    std::shared_ptr<const tiles::StorePack>
        source; ///< the store's pack it was built from (a pack added again is another)
    std::vector<PassPack> passes;
    double build_ms = 0;
};

/// What a pass's build_pack sees of a pack and keeps of it, on the world's pack thread.
class PackBuild {
public:
    PackBuild(const tiles::Store& store, std::shared_ptr<const tiles::StorePack> pack, PassPack& out,
              std::uint32_t pass) noexcept
        : store_(store), pack_(std::move(pack)), out_(out), pass_(pass) {}

    [[nodiscard]] const tiles::StorePack& pack() const noexcept { return *pack_; }
    [[nodiscard]] const tiles::RenderHead& head() const noexcept { return *pack_->pack.head(); }
    /// The pass's place among the frame builder's passes (its PassPack in ResidentPack::passes).
    [[nodiscard]] std::uint32_t pass() const noexcept { return pass_; }

    /// Every tile of zoom `z` (the pack's finest level when none is given), decoded in the pack's chunk order and
    /// handed to `each` with its chunk; the tile views memory valid during the call alone. Stops at the first tile that
    /// does not decode.
    [[nodiscard]] gpu::Result<void> tiles(const std::function<void(const tiles::Tile&, const tiles::ChunkRef&)>& each,
                                          std::optional<std::uint8_t> z = std::nullopt);

    /// Keeps `data` as the pass's CPU-side data for the pack (PassPack::cpu), `bytes` of it: const from here on.
    template <class T>
    void keep(std::shared_ptr<const T> data, std::size_t bytes) {
        out_.cpu = std::move(data);
        out_.cpu_bytes = bytes;
    }

private:
    const tiles::Store& store_;
    std::shared_ptr<const tiles::StorePack> pack_;
    PassPack& out_;
    std::uint32_t pass_;
};

/// The packs' stage T on a thread of its own.
class PackStage {
public:
    /// Builds packs of `store` with `passes`' build_pack (the frame builder's: they outlive the stage).
    PackStage(const tiles::Store& store, std::span<const std::unique_ptr<frame::Pass>> passes);
    ~PackStage();
    PackStage(const PackStage&) = delete;
    PackStage& operator=(const PackStage&) = delete;
    PackStage(PackStage&&) = delete;
    PackStage& operator=(PackStage&&) = delete;

    /// Asks for `pack`'s stage T (render thread): nothing when it is built, under way or asked for already. A pack
    /// added to the store again is another pack, built again.
    void want(const std::shared_ptr<const tiles::StorePack>& pack);
    /// The packs built since the last call (render thread).
    void take(std::vector<std::shared_ptr<const ResidentPack>>& out);
    /// Called on the stage's thread as each pack is published (cheap and never blocking: the frame loop's wake).
    void set_published(std::function<void()> callback);
    /// Waits until no pack is asked for or under way (tests).
    void wait_idle();

private:
    void work(const std::stop_token& stop);

    const tiles::Store& store_;
    std::span<const std::unique_ptr<frame::Pass>> passes_;
    std::mutex mutex_;
    std::condition_variable_any wake_;
    std::condition_variable idle_;
    std::vector<std::shared_ptr<const tiles::StorePack>> queue_;
    std::vector<std::weak_ptr<const tiles::StorePack>> known_; ///< asked for, under way or built (while they are open)
    std::vector<std::shared_ptr<const ResidentPack>> published_;
    std::function<void()> published_callback_;
    bool busy_ = false;
    std::jthread thread_; ///< last: it starts once every member above is made, and stops first
};

} // namespace atlas::scene
