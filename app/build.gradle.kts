plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.calltracker.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.calltracker.app"
        // Targeting 13-15 to match the OEM test matrix (Samsung/Xiaomi/OnePlus/Pixel)
        // discussed in planning. minSdk 26 covers TelephonyCallback's API 31 path
        // plus the PhoneStateListener fallback below it.
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-poc"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // No signingConfigs block on purpose. The POC is installed as a debug build
    // signed with the local development key. Release signing material (*.jks,
    // key.properties) must never enter this repository — see .gitignore.

    testOptions {
        unitTests {
            // The pure-logic unit tests below never call a real Android method,
            // but Room/Compose types on the classpath can pull android.jar stubs
            // in transitively. Returning defaults keeps the JVM tests runnable
            // without Robolectric.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Kept from the original scaffold. Deliberately UNUSED by the POC: the sync
    // layer is behind CallSyncClient and the only implementation is local.
    // These stay so wiring a real backend later is a dependency no-op.
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")

    // The unit tests cover pure logic only, so plain JUnit is enough - no
    // Robolectric, no coroutines-test, no mocking framework.
    testImplementation("junit:junit:4.13.2")
}
