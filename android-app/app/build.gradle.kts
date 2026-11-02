import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Design previews (Paparazzi) and, later, Robolectric launch tests cannot share one test classpath.
// `gradle -Ppreviews=true :app:testDebugUnitTest` renders ../previews/*.png.
val previews = hasProperty("previews")
if (previews) apply(plugin = "app.cash.paparazzi")

// Release signing: secrets live OUTSIDE the repository, in ~/keys/radd-release.properties
// (storeFile, storePassword, keyAlias, keyPassword). Override the location with -PkeystoreProps=/path.
val signingProps = Properties().apply {
    val f = file((findProperty("keystoreProps") as String?) ?: "${System.getProperty("user.home")}/keys/radd-release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.radd"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.radd"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        if (signingProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false // nothing here relies on reflection tricks; size is small anyway
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    sourceSets.getByName("test").java.setSrcDirs(listOf(if (previews) "src/previewTest/java" else "src/test/java"))
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    if (!previews) {
        testImplementation("org.robolectric:robolectric:4.14.1")
        testImplementation("androidx.test:core-ktx:1.6.1")
        testImplementation("androidx.test.ext:junit:1.2.1")
        testImplementation(bom)
        testImplementation("androidx.compose.ui:ui-test-junit4")
        debugImplementation("androidx.compose.ui:ui-test-manifest")
    }
}
