plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
}
