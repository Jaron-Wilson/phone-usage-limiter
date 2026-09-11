import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Version lives in version.properties at the repo root so it can be bumped
// without editing the build script. tools/bump-version.sh moves it on.
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

// Release signing. Absent on a fresh clone, in which case release builds fall
// back to the debug key and Gradle says so rather than failing.
val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    namespace = "dev.jaronwilson.modes"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.jaronwilson.modes"
        minSdk = 31
        targetSdk = 35
        versionCode = versionProps.getProperty("versionCode").trim().toInt()
        versionName = versionProps.getProperty("versionName").trim()

        // This is a phone-specific tool, and a Pixel is arm64. Shipping four
        // architectures tripled the download for nothing.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // Every scheme the platform understands. v1 is redundant above
                // API 24 but costs little and rules out one class of installer
                // complaint.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            // Left off deliberately. R8 and a NotificationListenerService that
            // the system instantiates by name are a bad combination to debug on
            // a phone, and the size saved is not worth the risk here.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isDebuggable = false
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "No keystore.properties: release build will use the debug key, " +
                        "which many devices refuse to install. See the README."
                )
            }
        }
        debug {
            applicationIdSuffix = ""
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
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

// Name the APK after its version, so two builds are never confused.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName.set(
                    "Modes-v${versionProps.getProperty("versionName").trim()}-${variant.buildType}.apk"
                )
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}
