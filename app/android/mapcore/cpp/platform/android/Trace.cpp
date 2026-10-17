// Systrace sections (platform/android/Android.hpp): the core's work as Perfetto and simpleperf show it.
#if defined(__ANDROID__)

#include "platform/android/Android.hpp"

#include <android/trace.h>

namespace atlas::platform {

TraceSection::TraceSection(const char* name) noexcept : on_(ATrace_isEnabled()) {
    if (on_) ATrace_beginSection(name);
}

TraceSection::~TraceSection() {
    if (on_) ATrace_endSection();
}

} // namespace atlas::platform

#endif
