plugins {
    id("com.android.application") version "9.1.0" apply false
    id("com.android.library") version "9.1.0" apply false
    id("com.android.kotlin.multiplatform.library") version "9.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
}

// Isolate Robolectric fixtures and never overlap test forks in the same project.
allprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        forkEvery = 1
        maxParallelForks = 1
    }
}
