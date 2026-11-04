// AGP 8.13 bundles R8 8.13, which only understands Kotlin up to 2.3. Kotlin 2.4 needs R8 9.1.29+
// (developer.android.com/build/kotlin-support); the bundled one shrank v0.6.0 without reading its
// metadata. A newer R8 on the buildscript classpath takes precedence over the bundled one.
buildscript {
    repositories { google() }
    dependencies { classpath("com.android.tools:r8:9.5.29") }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
