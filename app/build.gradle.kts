plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "dev.sarvenaz.biosignalbridge"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "dev.sarvenaz.biosignalbridge"; minSdk = 26; targetSdk = 36; versionCode = 2; versionName = "1.1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildTypes {
        release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
        create("preview") { initWith(getByName("release")); signingConfig = signingConfigs.getByName("debug"); matchingFallbacks += "release" }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:signal")); implementation(project(":core:capture")); implementation(project(":core:storage"))
    implementation(platform(libs.compose.bom)); implementation(libs.compose.ui); implementation(libs.compose.material3); implementation(libs.compose.preview); implementation(libs.compose.icons)
    implementation(libs.activity); implementation(libs.lifecycle); implementation(libs.viewmodel); implementation(libs.hilt); ksp(libs.hilt.compiler); implementation(libs.coroutines.android)
    debugImplementation(libs.compose.tooling); debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit); testImplementation(libs.robolectric); testImplementation(libs.test.core); testImplementation(platform(libs.compose.bom)); testImplementation(libs.compose.test)
    testImplementation(libs.room.runtime); testImplementation(libs.room.ktx)
}
