plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pulse.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pulse.player"
        minSdk = 23
        targetSdk = 35
        versionCode = 4
        versionName = "1.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { viewBinding = true; buildConfig = true }
    kotlinOptions { jvmTarget = "17" }
    applicationVariants.all {
        outputs.all {
            if (name == "debug") {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "pulse-v1.2.apk"
            }
        }
    }
}

dependencies {
    implementation(files("../DiscordSocialSdk-1.10.19337/discord_social_sdk/lib/release/discord_partner_sdk.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
}
