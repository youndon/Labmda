plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "com.zouitel.presentaion"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.material3)
    implementation(libs.compose.material)
    implementation(libs.compose.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)

    //Modules.
    implementation(projects.domain)

    //Kotlin-Coroutines.
    implementation(libs.coroutines.core)

    //Koin.
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.coroutines)

    //Voyager.
    implementation(libs.voyager.navigator)
    implementation(libs.voyager.screenModel)
    implementation(libs.voyager.koin)
    implementation(libs.voyager.transitions)

    // Google Maps wrapper for Compose Multiplatform
    implementation(libs.kmp.maps.compose)
    // Optional clustering / marker tools
    implementation(libs.kmp.maps.compose.utils)

    // Google Sign-In for Compose Multiplatform
    //noinspection UseTomlInstead
    api("io.github.mirzemehdi:kmpauth-google:3.0.6")

    // KMP Firebase Auth
    api(libs.firebase.auth)
    api(libs.firebase.firestore)
    api(libs.firebase.database)
    implementation("dev.gitlive:firebase-messaging:2.7.0")

    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.kotlinx.serialization.json)

    //Location(Native).
implementation("com.google.android.gms:play-services-location:21.4.0")
}