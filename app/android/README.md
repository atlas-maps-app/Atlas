# Atlas on Android

The app, as a Kotlin and Jetpack Compose project. Everything runs from the command line on this Mac; no Android Studio.

- Versions, checked against the official release pages on 13 October 2026 (they are listed in `gradle/libs.versions.toml`): Android Gradle Plugin 9.4.0 with its built-in Kotlin, the Compose compiler plugin at Kotlin 2.4.21, Compose BOM 2026.09.00, Gradle 9.8.1, JDK 17. `compileSdk` and `targetSdk` are 37 (Android 17), which Compose 1.12 requires; `minSdk` 29.
- SDK: `~/Library/Android/sdk` with platforms 35 to 37, build tools 35 and 36, the emulator and an Android 15 arm64 system image. `local.properties` points at it and stays out of git.
- Gradle comes with the wrapper (`./gradlew`), which fetches its own distribution.

```
./gradlew assembleDebug                          # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n app.atlas/.MainActivity
```

The emulator cannot run from this session: the Hypervisor framework reports unsupported here, so the virtual device `atlas` boots only from a terminal of your own (`emulator -avd atlas`). A real phone over USB works with `adb` as above.

What is here: one module, `:app`, one activity, one composable. The map comes next: our own vector renderer, a port of `render/`.
