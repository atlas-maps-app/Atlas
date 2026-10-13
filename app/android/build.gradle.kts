// Atlas, the Android app. One module, :app. Build from the command line: ./gradlew assembleDebug (see README.md).
// AGP 9 compiles Kotlin itself (built-in Kotlin): no org.jetbrains.kotlin.android plugin here, only the Compose compiler plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
