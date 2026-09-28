import com.oppzippy.openscq30.gradle.CopyNativeLibTask

plugins {
    alias(libs.plugins.android.application)
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
    }
}

val rustProjectDir: File = layout.projectDirectory.asFile.parentFile
val rustWorkspaceDir: File = rustProjectDir.parentFile
val cargoTargetDirectory: File = rustWorkspaceDir.resolve("target")

val cargoBuildLite = tasks.register<Exec>("cargo-build-lite") {
    description = "Building core for armeabi-v7a"
    workingDir = rustWorkspaceDir
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

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.jniLibs!!.addGeneratedSourceDirectory(
            copyNativeLibLite,
            CopyNativeLibTask::outputDirectory,
        )
    }
}
