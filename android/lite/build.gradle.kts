import com.oppzippy.openscq30.gradle.CopyNativeLibTask
import com.oppzippy.openscq30.gradle.GenerateUniffiBindingsTask

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

android {
    namespace = "com.oppzippy.openscq30.lite"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.oppzippy.openscq30.lite"
        minSdk = 19
        targetSdk = 19
        versionCode = 1
        versionName = "0.1"
        multiDexEnabled = true // NEW
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.multidex:multidex:2.0.1") // NEW
}

val rustProjectDir: File = layout.projectDirectory.asFile.parentFile
val rustWorkspaceDir: File = rustProjectDir.parentFile
val cargoTargetDirectory: File = rustWorkspaceDir.resolve("target")

val cargoBuildLite = tasks.register<Exec>("cargo-build-lite") {
    description = "Building core for armeabi-v7a"
    workingDir = rustProjectDir
    commandLine(
        "cargo",
        "ndk",
        "--target",
        "armv7-linux-androideabi",
        "--platform",
        "19",
        "build",
        "--profile",
        "dev",
    )
}

val copyNativeLibLite = tasks.register<CopyNativeLibTask>("rust-deploy-lite") {
    dependsOn(cargoBuildLite)
    description = "Copy rust lib to jniLibs"
    this.gradleBuildProfile = "debug"
    this.cargoProfile = "debug"
    this.inputFile = File("$cargoTargetDirectory/armv7-linux-androideabi/debug/libopenscq30_android.so")
    this.androidAbi = "armeabi-v7a"
    this.outputDirectory = layout.buildDirectory.get().asFile.resolve("generated/native/debug-armeabi-v7a/jniLibs")
}

val generateBindingsLite = tasks.register<GenerateUniffiBindingsTask>("generate-uniffi-bindings-lite") {
    dependsOn(cargoBuildLite)
    description = "Generate kotlin bindings using uniffi-bindgen"
    this.rustAbi = "armv7-linux-androideabi"
    this.cargoProfile = "debug"
    this.rustWorkspaceDirectory = rustWorkspaceDir
    this.rustProjectDirectory = rustProjectDir
    this.outputDirectory = layout.buildDirectory.get().asFile.resolve("generated/source/uniffi/debug/java")
}

val wrapperDir: String = layout.projectDirectory
    .dir("../app/src/main/java/com/oppzippy/openscq30/lib/wrapper")
    .asFile.absolutePath

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.jniLibs!!.addGeneratedSourceDirectory(
            copyNativeLibLite,
            CopyNativeLibTask::outputDirectory,
        )
        variant.sources.java!!.addGeneratedSourceDirectory(
            generateBindingsLite,
            GenerateUniffiBindingsTask::outputDirectory,
        )
        variant.sources.java!!.addStaticSourceDirectory(wrapperDir)
    }
}
