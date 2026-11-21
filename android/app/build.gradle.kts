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
            // The key file is encrypted; its password lives only in GitHub's private Secrets.
            storeFile = rootProject.file("njr-release.p12")
            storePassword = System.getenv("NJR_KS_PASS")
            keyAlias = "njr"
            keyPassword = System.getenv("NJR_KS_PASS")
            storeType = "pkcs12"
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
    implementation("androidx.media3:media3-cast:$m3")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.guava:guava:33.2.1-android")
}
