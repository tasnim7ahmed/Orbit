import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing details, kept outside the repository. Point ORBIT_KEYSTORE_PROPERTIES
 * at the file, or place it at ../../orbit-signing/keystore.properties. Without it the
 * release build falls back to the debug key, so anyone can still clone and build.
 */
val keystoreProperties: Properties? = run {
    val path = System.getenv("ORBIT_KEYSTORE_PROPERTIES")
        ?: rootProject.file("../../orbit-signing/keystore.properties").path
    val file = File(path)
    if (!file.exists()) null else Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.wearos.ancsbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wearos.ancsbridge"
        minSdk = 33 // Wear OS 4
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Published builds are signed with the private release key. Without it
            // (a fresh clone) the debug key is used, which still installs over adb.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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
        compose = true
    }

    testOptions {
        // android.util.Log calls in protocol code return defaults in JVM unit tests
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Compose for Wear OS
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")

    // Activity (also brings ViewModel + viewModelScope)
    implementation("androidx.activity:activity-compose:1.13.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Core
    implementation("androidx.core:core-ktx:1.17.0")

    // Watch face complications, Tiles, Ongoing Activity (iPhone battery / Now Playing surfaces)
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material:1.4.2")
    implementation("androidx.wear:wear-ongoing:1.1.0")
    // Media session + MediaStyle, so the watch's own media controls can drive the iPhone's player
    implementation("androidx.media:media:1.8.0")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.3.0")

    // Installs the Compose libraries' baseline profiles on sideload (AOT-compiles hot paths)
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
