import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Load local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

android {
    namespace = "com.example.aisee_livekit_example"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.aisee_livekit_example"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Add LiveKit config as build config fields
        buildConfigField("String", "LIVEKIT_WS_URL", "\"${localProperties.getProperty("livekit.wsUrl", "")}\"")
        buildConfigField("String", "LIVEKIT_TOKEN", "\"${localProperties.getProperty("livekit.token", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("io.livekit:livekit-android:2.23.1")
    implementation("io.livekit:livekit-android-camerax:2.23.1")
    implementation("io.livekit:livekit-android-track-processors:2.23.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
}