plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.gms.google-services")
}

android {
    namespace = "cz.courierledger"
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.courierledger"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "2.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }

    // Direct APK distribution: use the same local debug keystore for release builds on this machine.
    // This makes assembleRelease produce an installable APK without Play Console.
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kapt { correctErrorTypes = true }

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.0")
    implementation("androidx.room:room-ktx:2.8.0")
    kapt("androidx.room:room-compiler:2.8.0")

    implementation("net.zetetic:sqlcipher-android:4.17.0@aar")
    implementation("androidx.sqlite:sqlite:2.6.2")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
