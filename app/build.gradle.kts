plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
}

// Supply this through ~/.gradle/gradle.properties or the MAPS_API_KEY
// environment variable. The key is a restricted client key, not a secret.
val mapsApiKey = providers.gradleProperty("MAPS_API_KEY")
    .orElse(providers.environmentVariable("MAPS_API_KEY"))
    .orElse("")

android {
    namespace = "com.zouitel.labmda"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.zouitel.labmda"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey.get()
    }

    buildTypes {
        release {
            optimization {
                enable = false
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

dependencies {
    //Modules.
    implementation(projects.presentation)

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.material3)
    implementation(libs.compose.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
//    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
//    debugImplementation(libs.androidx.compose.ui.test.manifest)
//    debugImplementation(libs.androidx.compose.ui.tooling)

    //Koin.
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.coroutines)
    implementation(libs.koin.startup)

    //Voyager.
    implementation(libs.voyager.navigator)
    implementation(libs.voyager.screenModel)
    implementation(libs.voyager.koin)
    implementation(libs.voyager.transitions)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    implementation(libs.ktor.client.okhttp)

    // KMP Firebase Auth
    api(libs.firebase.auth)
    api(libs.firebase.firestore)
    api(libs.firebase.database)
}