#!/usr/bin/env python3
"""The local checks a change needs: its changed paths (git diff --name-only <base>...HEAD, and what is not committed
yet) mapped to the core's ctest entries and the Gradle test tasks that cover them. The full suites are CI's.

  python3 -I app/android/ci/affected.py [--base app] [--build app/android/mapcore/cpp/build/host-debug]

The core (app/android/mapcore/cpp): a test file is its own ctest entry (<area>/<name>, CMakeLists.txt); a changed
header picks every test file that includes it, from the build folder's dependency records (ninja -t deps) where
there is a build, else the test files that include it by name; a changed source picks its area's tests (the label of
the same name) and the test files that include its header; the shaders pick the Vulkan tests (label vk) and the
SPIR-V locks; the build's configuration or third-party code picks every entry. Every change of the core adds the JNI
tests of :mapcorecheck that hold its area to the port. A Kotlin module picks its own unit tests and those of each
module that depends on it. Prints the commands; runs nothing.

Python 3's standard library only.
"""

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CORE = Path("app/android/mapcore/cpp")
ANDROID = Path("app/android")

# :mapcorecheck's test packages (app.atlas.mapcorecheck.<package>) that hold each of the core's areas to the port.
CHECK_PACKAGES = {
    "base": ["numerics"], "osm": ["osm", "load"], "load": ["load"], "tiles": ["tiles", "load"],
    "style": ["style", "likeness"], "sun": ["sun"], "text": ["text", "likeness"], "fx": ["fx", "likeness"],
    "geometry": ["buildings", "twin", "likeness"], "stagep": ["load"],
}
# the areas whose code draws the frame: the twin, the likeness candidates, the frame's own checks
for _area in ["frame", "passes", "scene", "gpu", "view", "labels", "markers", "routes", "standin", "twin", "shaders"]:
    CHECK_PACKAGES[_area] = ["twin", "likeness", "frame", "buildings"]


def git(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True, check=True).stdout


def changed_paths(base):
    paths = set(git("diff", "--name-only", f"{base}...HEAD").split())
    paths |= set(git("diff", "--name-only", "HEAD").split())
    paths |= set(git("ls-files", "--others", "--exclude-standard").split())
    return sorted(paths)


def gradle_modules():
    """{module: (kind, {modules it depends on})}, from each module's build.gradle.kts."""
    modules = {}
    settings = (ROOT / ANDROID / "settings.gradle.kts").read_text(encoding="utf-8")
    for name in re.findall(r'":([a-z]+)"', settings):
        script = ROOT / ANDROID / name / "build.gradle.kts"
        if not script.exists():
            continue
        text = script.read_text(encoding="utf-8")
        android = "android.application" in text or "android.library" in text
        deps = set(re.findall(r'project\(":([a-z]+)"\)', text)) - {name}
        modules[name] = ("android" if android else "jvm", deps)
    return modules


def dependents(modules, changed):
    """The changed modules and every module that depends on one of them, however indirectly."""
    found = set(changed)
    grew = True
    while grew:
        grew = False
        for name, (_, deps) in modules.items():
            if name not in found and deps & found:
                found.add(name)
                grew = True
    return found


def ninja_deps(build):
    """{test entry: {repository paths its object depends on}} from the build folder's dependency records."""
    ninja = shutil.which("ninja") or next(
        (str(p) for p in sorted(Path.home().glob("Library/Android/sdk/cmake/*/bin/ninja"))), None)
    if ninja is None or not (build / ".ninja_deps").exists():
        return None
    out = subprocess.run([ninja, "-C", str(build), "-t", "deps"], capture_output=True, text=True).stdout
    deps, entry = {}, None
    for line in out.splitlines():
        if not line.startswith(" "):
            m = re.search(r"\.dir/tests/(.+)\.cpp\.o:", line)
            entry = m.group(1) if m else None
            if entry:
                deps.setdefault(entry, set())
        elif entry and line.strip():
            # a path in the repository, whichever checkout the build folder belongs to
            path = str((build / line.strip()).resolve())
            at = path.find("/app/android/")
            if at >= 0:
                deps[entry].add(path[at + 1:])
    return deps


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="app", help="the branch the change goes onto (default: app)")
    parser.add_argument("--build", default=str(CORE / "build/host-debug"),
                        help="a configured and built host folder of the core, for its dependency records")
    args = parser.parse_args()

    paths = changed_paths(args.base)
    if not paths:
        print(f"Nothing changed against {args.base}.")
        return
    tests_dir = ROOT / CORE / "tests"
    entries = {str(p.relative_to(tests_dir).with_suffix("")) for p in tests_dir.glob("*/*.cpp")
               if p.parent.name != "planted"}
    areas = {e.split("/")[0] for e in entries}
    build = Path(args.build) if Path(args.build).is_absolute() else ROOT / args.build
    deps = ninja_deps(build)

    def users(header):
        """The test entries whose file includes the header (a repository path): by the build's dependency records where
        there is a build, else the test files that name it."""
        if deps is not None:
            return {e for e, d in deps.items() if header in d}
        include = str(Path(header).relative_to(CORE))
        return {str(t.relative_to(tests_dir).with_suffix("")) for t in tests_dir.glob("*/*.cpp")
                if f'"{include}"' in t.read_text(encoding="utf-8", errors="replace")}

    labels, names, packages = set(), set(), set()
    everything = spirv = check_all = fuzz = bench = False
    gradle_changed, other = set(), []
    for path in paths:
        p = Path(path)
        if p.is_relative_to(CORE):
            rel = p.relative_to(CORE)
            top = rel.parts[0]
            if top == "tests":
                if rel.suffix == ".cpp" and len(rel.parts) == 3 and rel.parts[1] != "planted":
                    names.add(str(rel.relative_to("tests").with_suffix("")))
                elif rel.parts[1] in areas:
                    labels.add(rel.parts[1])
                if rel.suffix in (".hpp", ".h"):
                    names.update(users(path))
                continue
            if top in ("CMakeLists.txt", "CMakePresets.json", "cmake", "third_party") or top.startswith("."):
                everything = True
                continue
            if top == "shaders":
                labels.add("vk")
                spirv = True
                packages.update(CHECK_PACKAGES["shaders"])
                continue
            if top == "fuzz":
                fuzz = True
                continue
            if top == "bench":
                bench = True
                continue
            if top == "jni":
                check_all = True
                continue
            if top == "tools" or top == "rules":
                other.append(path)
                continue
            # a core area's source or header
            if top in areas:
                labels.add(top)
            names.update(users(path if rel.suffix in (".hpp", ".h") else str(p.with_suffix(".hpp"))))
            if top in CHECK_PACKAGES:
                packages.update(CHECK_PACKAGES[top])
            else:
                check_all = True
        elif p.is_relative_to(ANDROID):
            rel = p.relative_to(ANDROID)
            if rel.parts[0] in ("gradle", "build.gradle.kts", "settings.gradle.kts", "gradle.properties"):
                gradle_changed.add("*")
            elif rel.parts[0] == "mapcore" and len(rel.parts) > 1 and rel.parts[1] == "style":
                gradle_changed.update(["mapcore", "packer"])
                check_all = True
                labels.add("style")
            elif rel.parts[0] == "ci":
                other.append(path)
            else:
                gradle_changed.add(rel.parts[0])
        else:
            other.append(path)

    print(f"{len(paths)} paths changed against {args.base}...HEAD (with what is not committed).")
    core_changed = everything or labels or names or spirv or fuzz or bench or check_all or packages
    if core_changed:
        print()
        print(f"The core (in {CORE}, after cmake --build --preset host-debug -j3; ATLAS_REQUIRE_VULKAN=1, LP_NUM_THREADS=2):")
        if everything:
            print("  ctest --preset host-debug -j2 -L atlas_tests   # the build's configuration or third-party code: every entry")
        else:
            if labels:
                print(f"  ctest --preset host-debug -j2 -L '^({'|'.join(sorted(labels))})$'   # the areas changed")
            if names:
                # a file's entry, or its parts (<area>/<name>:<k>, CMakeLists.txt's _test_parts)
                pattern = "|".join(re.escape(n) for n in sorted(names))
                print(f"  ctest --preset host-debug -j2 -R '^({pattern})(:[0-9]+)?$'   # the test files changed, or including a changed header")
            if deps is None and (labels or names):
                print(f"  (no dependency records in {build}: the headers' users are the test files that name them)")
        if spirv or everything:
            print("  ctest --preset host-debug -R '^spirv_(lock|budget)$'   # the shaders' locks and budgets")
        if fuzz or everything:
            print("  ctest --preset host-debug -R '_corpus$'   # the fuzz targets over their corpora")
        if bench or everything:
            print("  cmake --build --preset host-release -j3   # the benchmarks build with the release flags")
    modules = gradle_modules()
    tasks = []
    if "*" in gradle_changed:
        chosen = set(modules)
    else:
        chosen = dependents(modules, {m for m in gradle_changed if m in modules})
    for name in sorted(chosen):
        if name in ("mapcorecheck", "likeness"):
            continue
        kind = modules[name][0]
        tasks.append(f":{name}:testDebugUnitTest" if kind == "android" else f":{name}:test")
    check = check_all or "mapcorecheck" in chosen or "*" in gradle_changed
    if tasks or check or packages or "likeness" in chosen:
        print()
        print(f"Gradle (in {ANDROID}, with ENGINE.md's flags):")
        if tasks:
            print("  ./gradlew " + " ".join(tasks))
        if check:
            print("  ./gradlew :mapcorecheck:test   # the JNI tests against the core's host build")
        elif packages:
            filters = " ".join(f"--tests 'app.atlas.mapcorecheck.{p}.*'" for p in sorted(packages))
            print(f"  ./gradlew :mapcorecheck:test {filters}")
        if "likeness" in chosen or "likeness" in packages or check:
            print("  ./gradlew :likeness:testDebugUnitTest   # the likeness references (then :mapcorecheck's likeness.*)")
    if other:
        print()
        print("Other paths, which no local suite covers (CI's do): " + ", ".join(other[:12]) + (" ..." if len(other) > 12 else ""))
        if any(o.startswith("render/") or o.startswith(str(CORE / "rules")) for o in other):
            print("  python3 -I app/android/mapcore/rules/check_rules.py check   # the rule table against the renderer")
    print()
    print("The full suites are CI's: every ctest entry under ASan, TSan and the release flags on x86_64 and arm64, both")
    print("tidy databases, the fuzzers, the canaries, every Gradle test and the emulator's screens run on each push to app.")


if __name__ == "__main__":
    sys.exit(main())
