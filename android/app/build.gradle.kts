plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val vCode = (project.findProperty("vCode") as String?)?.toIntOrNull() ?: 1
android {
    namespace = "io.github.daniturn3.nightjarradio"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.github.daniturn3.nightjarradio"
        minSdk = 24
        targetSdk = 34
        versionCode = vCode
        versionName = "1.$vCode"
    }
    signingConfigs {
        create("rel") {
            storeFile = rootProject.file("njr.keystore")
            storePassword = "nightjarradio"
            keyAlias = "njr"
            keyPassword = "nightjarradio"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rel")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
dependencies {
    val m3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$m3")
    implementation("androidx.media3:media3-exoplayer-hls:$m3")
    implementation("androidx.media3:media3-session:$m3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.guava:guava:33.2.1-android")
}
