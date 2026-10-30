import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing. android/keystore.properties (git-ignored) names the keystore and its passwords;
// without it the release build falls back to the debug key, as the previews up to v0.5.0 were signed.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().also { props -> f.inputStream().use { props.load(it) } }
}

android {
    namespace = "org.openchat.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.openchat.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.5.0"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        // Tor ships native code per ABI. Phones are ARM; x86_64 is only for the emulator.
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
        // The published preview: shrunk by R8, ARM only. Signed with the release key when
        // keystore.properties is present. Previews up to v0.5.0 carried the debug key, so the
        // first release-signed build does not install over them: uninstall first.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystoreProps != null) "release" else "debug")
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // Compress native libraries inside the APK: much smaller to pass phone to phone over
        // Bluetooth, at the cost of unpacking them once on install.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("info.guardianproject:tor-android:0.4.8.16")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
