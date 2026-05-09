plugins {
    // Без указания версии, так как версия уже в classpath
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.5.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.0")
    }
}


android {
    namespace = "com.example.useevtubingapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.useevtubingapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
    }
    packagingOptions {
        pickFirst("**/libc++_shared.so")
        pickFirst("**/libfilament-jni.so")
        pickFirst("**/libfilament-utils-jni.so")
        pickFirst("**/libgltfio-jni.so")
    }
}

val compose_version = "1.5.4"

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.genai.common)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")
    implementation("androidx.activity:activity-compose:1.3.1")
    implementation("androidx.compose.ui:ui:$compose_version")
    implementation("androidx.compose.ui:ui-tooling-preview:$compose_version")
    implementation("androidx.compose.material3:material3:1.0.0-alpha11")
    // Material 3
    implementation("androidx.compose.material3:material3:1.4.0")

    // Иконки Material
    implementation("androidx.compose.material:material-icons-core:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.4.0")
    implementation("com.google.firebase:firebase-firestore-ktx:25.1.3")
    implementation("androidx.compose.foundation:foundation:1.10.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.constraintlayout:constraintlayout-core:1.1.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:$compose_version")
    debugImplementation("androidx.compose.ui:ui-tooling:$compose_version")
    debugImplementation("androidx.compose.ui:ui-test-manifest:$compose_version")

    // Навигация между окнами
    implementation("androidx.navigation:navigation-compose:2.6.0-alpha04")
    // Анимация навигации
    implementation("com.google.accompanist:accompanist-navigation-animation:0.35.0-alpha")

    //camera
    implementation("androidx.camera:camera-camera2:1.2.3")
    implementation("androidx.camera:camera-lifecycle:1.2.3")
    implementation("androidx.camera:camera-view:1.3.0-beta01")

    // CameraX core libraries
    implementation("androidx.camera:camera-core:1.0.0-beta07")

    // Image loading and GPU-accelerated filtering
    implementation("com.github.bumptech.glide:glide:4.15.1")
    implementation("jp.co.cyberagent.android:gpuimage:2.1.0")

    //Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.18.0")

    // Core Android extensions
    implementation("androidx.core:core-ktx:1.10.1")

    // Coil
    implementation("io.coil-kt:coil-compose:1.4.0")

    implementation("com.google.guava:guava:31.1-android")

    // base sdk ML KIT
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    // the accurate sdk ML KIT
    implementation("com.google.mlkit:pose-detection-accurate:18.0.0-beta5")

    // ML Kit Face Detection
    implementation("com.google.mlkit:face-detection:16.1.6")
    implementation("com.google.mlkit:face-detection:16.1.6")

    // ML Kit Common
    implementation("com.google.mlkit:common:18.10.0")

    // Google Play Services
    implementation("com.google.android.gms:play-services-tasks:18.1.0")

    // Filament
    implementation("com.google.android.filament:filament-android:1.28.0")
    implementation("com.google.android.filament:filament-utils-android:1.28.0")
    implementation("com.google.android.filament:gltfio-android:1.28.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-common:2.8.7")

    // WebView
    implementation("androidx.webkit:webkit:1.7.0")
}