plugins {
    id("org.gradle.experimental.android-library")
}

androidLibrary {
    jdkVersion = 17
    compileSdk = 34
    namespace = "org.gradle.experimental.android.library.kotlin"

    dependencies {
        api("com.google.guava:guava:32.1.3-jre")
    }

    targets {
        create("debug") {
            dependencies {
                implementation("org.apache.commons:commons-lang3:3.14.0")
            }
        }
    }
}