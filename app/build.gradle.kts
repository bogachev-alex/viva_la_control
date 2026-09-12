plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val shippedVersionCode = providers.gradleProperty("versionCode")
    .map { it.toInt() }
    .orElse(1)
val shippedVersionName = providers.gradleProperty("versionName")
    .orElse("1.0")
val keystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH")
val keystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
val keyAliasName = providers.environmentVariable("ANDROID_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")
val canSignRelease = keystorePath.isPresent &&
    keystorePassword.isPresent &&
    keyAliasName.isPresent &&
    releaseKeyPassword.isPresent

android {
    namespace = "viva.la.circle"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "viva.la.circle"
        minSdk = 23
        targetSdk = 37
        versionCode = shippedVersionCode.get()
        versionName = shippedVersionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (canSignRelease) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath.get())
                storePassword = keystorePassword.get()
                keyAlias = keyAliasName.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
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

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("viva_la_control.apk")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
