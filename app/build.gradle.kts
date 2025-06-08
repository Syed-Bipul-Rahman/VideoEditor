plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "me.bipul.videoeditor"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.bipul.videoeditor"
        minSdk = 29
        targetSdk = 35
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
    buildFeatures {
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
//    splits {
//        abi {
//            isEnable = true
//            reset()
//            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
//            isUniversalApk = false
//        }
//    }
    packaging {
        resources {
            pickFirsts.add("META-INF/native-image/android-arm64/jnijavacpp/reflect-config.json")
            pickFirsts.add("META-INF/native-image/android-arm/jnijavacpp/reflect-config.json")
            pickFirsts.add("META-INF/native-image/android-x86/jnijavacpp/reflect-config.json")
            pickFirsts.add("META-INF/native-image/android-x86_64/jnijavacpp/reflect-config.json")
            // Existing pickFirsts entries
            pickFirsts.add("META-INF/native-image/android-arm64/jnijavacpp/jni-config.json")
            pickFirsts.add("META-INF/native-image/android-arm/jnijavacpp/jni-config.json")
            pickFirsts.add("META-INF/native-image/android-x86/jnijavacpp/jni-config.json")
            pickFirsts.add("META-INF/native-image/android-x86_64/jnijavacpp/jni-config.json")
            // Existing excludes
            excludes += listOf(
                "META-INF/native-image/linux-*/**",
                "META-INF/native-image/macosx-*/**",
                "META-INF/native-image/windows-*/**",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    // Optimized FFmpeg dependencies
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11")
    implementation("org.bytedeco:ffmpeg-platform:7.1-1.5.11")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation(libs.play.services.vision.common)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}