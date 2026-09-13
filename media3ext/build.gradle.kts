import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.mavenPublish)
}

android {
    namespace = "io.github.anilbeesetti.nextlib.media3ext"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    buildFeatures { prefab = true }

    defaultConfig {

        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags("")
                arguments("-DANDROID_STL=c++_shared")
            }
        }

        ndk {
            abiFilters += listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

androidComponents {
    onVariants { variant ->
        // The prefab dependency already ships these in its AAR; don't publish a second copy.
        variant.packaging.jniLibs.excludes.addAll("**/libass.so", "**/libc++_shared.so")
        variant.androidTest?.packaging?.jniLibs?.pickFirsts?.addAll("**/libass.so", "**/libc++_shared.so")
        variant.androidTest?.sources?.assets?.addStaticSourceDirectory("src/test/cpp/fixtures")
    }
}

tasks.configureEach {
    if (name == "preBuild" || name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(":ffmpegSetup")
    }
}

dependencies {
    implementation(libs.libass.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.google.errorprone.annotations)
    implementation(libs.androidx.annotation)
    compileOnly(libs.checker.qual)
    compileOnly(libs.kotlin.annotations.jvm)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
