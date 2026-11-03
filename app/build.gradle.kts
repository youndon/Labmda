plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
}

// Android Maps keys are public client identifiers, not server secrets. Keep the
// value out of source control and restrict it by Android package + signing SHA-1
// and by the minimum required APIs in Google Cloud Console.
val labmdaMapsApiKey = providers.gradleProperty("LABMDA_MAPS_API_KEY")
    .orElse(providers.environmentVariable("LABMDA_MAPS_API_KEY"))
    .getOrElse("")

android {
    namespace = "com.zouitel.labmda"
    compileSdk {
        version = release(37)
    }

    flavorDimensions += "environment"
    productFlavors {
        create("development") {
            dimension = "environment"
        }
        create("production") {
            dimension = "environment"
        }
    }

    defaultConfig {
        applicationId = "com.zouitel.labmda"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["LABMDA_MAPS_API_KEY"] = labmdaMapsApiKey
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
    implementation(projects.presentation)
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.material3)
    implementation(libs.compose.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.coroutines)
    implementation(libs.koin.startup)

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

    // App Check: production attestation in all variants, debug provider only in debug.
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)
}