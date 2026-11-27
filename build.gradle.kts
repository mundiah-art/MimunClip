// Top-level build file for MimunClip.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    // Wajib sejak Kotlin 2.0 bila compose diaktifkan (run #2 gagal tanpa ini).
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
