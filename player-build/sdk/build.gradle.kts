plugins { `java-library` }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
sourceSets.main { java.srcDir("../../sdk/src") }
dependencies { compileOnly(files(System.getenv("ANDROID_HOME") + "/platforms/android-36/android.jar")) }
