
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Kotlin 2.4 metadata requires R8 9.1.29 or newer, including D8 for debug APKs.
        classpath("com.android.tools:r8:9.1.57")
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

