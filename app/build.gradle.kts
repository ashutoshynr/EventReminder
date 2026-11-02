plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.eventreminder.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.eventreminder.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        resourceConfigurations += listOf("en")   // skip all other locale resources from libraries
    }
    buildTypes {
        release {
            isMinifyEnabled = true               // R8 full mode: smaller, faster, AOT-friendly
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal use: sign with the debug key so the APK is always installable.
            // For Google Play, replace this with your own release keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
        // "fast" = release-grade speed, but signed with the debug key so Android Studio can run it directly.
        // Pick it in: Build > Select Build Variant > fast.
        create("fast") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    lint { checkReleaseBuilds = false; abortOnError = false }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    implementation("androidx.room:room-paging:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.paging:paging-compose:3.3.0")

    // Installs the baseline profile (src/main/baseline-prof.txt) => pre-compiled startup code, much faster cold start
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
}
