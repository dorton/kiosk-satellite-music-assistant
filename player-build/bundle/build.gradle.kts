plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "net.markandrew.kiosk.music.bundle"
    compileSdk = 36
    defaultConfig { applicationId = "net.markandrew.kiosk.music.bundle"; minSdk = 26; targetSdk = 36; versionCode = 69; versionName = "1.0.0" }
    sourceSets.getByName("main") {
        java.srcDirs("../../src", "../../receiver")
        manifest.srcFile("AndroidManifest.xml")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    buildTypes.getByName("release") { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "rules.pro") }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes.addAll(listOf("META-INF/**", "**/*.kotlin_metadata", "**/*.kotlin_builtins")) }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":player")); compileOnly(project(":sdk"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":sdk"))
    testImplementation("org.robolectric:robolectric:4.16.1")
}
tasks.register("writeRuntimeArtifacts") {
    doLast {
        val destination = layout.buildDirectory.file("runtime-artifacts.txt").get().asFile
        destination.parentFile.mkdirs()
        val artifacts = configurations.getByName("releaseRuntimeClasspath").incoming.artifactView {
            attributes.attribute(org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        }.files.files
        destination.writeText(artifacts.sortedBy { it.name }.joinToString("\n") { it.absolutePath })
    }
}
