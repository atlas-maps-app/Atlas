// Trace sections (ENGINE.md, gain 21): the core's work as Perfetto and simpleperf show it on Android, where a section
// is ATrace's (free while no trace runs: ATrace_isEnabled), and nothing on the hosts. A section's name is a string that
// outlives it, a literal or a name the caller keeps.
#pragma once

#if defined(__ANDROID__)
#include <android/trace.h>
#endif

namespace atlas::trace {

/// A trace section for as long as it lives.
class Section {
public:
#if defined(__ANDROID__)
    explicit Section(const char* name) noexcept : on_(ATrace_isEnabled()) {
        if (on_) ATrace_beginSection(name);
    }
    ~Section() {
        if (on_) ATrace_endSection();
    }
#else
    explicit Section(const char* /*name*/) noexcept {}
    ~Section() = default;
#endif
    Section(const Section&) = delete;
    Section& operator=(const Section&) = delete;
    Section(Section&&) = delete;
    Section& operator=(Section&&) = delete;

private:
#if defined(__ANDROID__)
    bool on_ = false;
#endif
};

} // namespace atlas::trace
