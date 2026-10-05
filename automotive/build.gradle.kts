plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

@Suppress("UNCHECKED_CAST")
val androidReleaseSigning =
    rootProject.extra["androidReleaseSigning.values"] as Map<String, String>
val releaseKeystorePath = androidReleaseSigning["ANDROID_KEYSTORE_PATH"]
    ?: ".private/release-signing-not-configured.jks"

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shilapi.xcertplay"
        minSdk = 28
        targetSdk = 37
        versionCode = 1201
        versionName = "1.2.1"

    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(releaseKeystorePath)
            storePassword = androidReleaseSigning["ANDROID_KEYSTORE_PASSWORD"].orEmpty()
            keyAlias = androidReleaseSigning["ANDROID_KEY_ALIAS"].orEmpty()
            keyPassword = androidReleaseSigning["ANDROID_KEY_PASSWORD"].orEmpty()
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.automotive)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
