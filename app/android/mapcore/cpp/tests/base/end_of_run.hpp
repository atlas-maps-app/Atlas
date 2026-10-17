// Work to do when the test run ends, before the process exits: shared fixtures that hold driver objects (a Vulkan
// device opened once for many tests) are released here, while the driver and its layers are still whole. Releasing
// them from static destructors instead runs into the layers' own exit-time teardown, in an order that depends on what
// else the run loaded.
#pragma once

namespace atlas::test {

/// Calls `release` once the test run ends (in reverse order of registration), before main returns.
void at_end_of_run(void (*release)());

} // namespace atlas::test
