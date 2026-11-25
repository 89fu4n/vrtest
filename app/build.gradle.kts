plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.orionlite.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.orionlite.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // signed with the debug key so the APK installs straight away
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "task" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.ar:core:1.44.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}

// Downloads Google's hand tracking model into the app the first time you build
val downloadHandModel by tasks.registering {
    val out = file("src/main/assets/hand_landmarker.task")
    outputs.file(out)
    doLast {
        if (!out.exists()) {
            out.parentFile.mkdirs()
            val url = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
            uri(url).toURL().openStream().use { input -> out.outputStream().use { output -> input.copyTo(output) } }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadHandModel) }
