plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.megix.hdhub4u"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.megix.hdhub4u"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.github.recloudstream:cloudstream:3.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.json:json:20231013")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

cloudstream {
    language = "hi"
    description = "HDHub4u — HD Movies & Web Series (Bollywood, Hollywood, South, Anime, K-Drama)"
    authors = listOf("megix")
    status = 3
    tvTypes = listOf("TvSeries", "Movie", "AsianDrama", "Anime")
    iconUrl = "https://new6.hdhub4u.cl/wp-content/uploads/2021/05/cropped-cropped-1-1-1-2-1-192x192.png"
}

