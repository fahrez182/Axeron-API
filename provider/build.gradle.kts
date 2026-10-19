plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "frb.axeron.provider"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":api"))
    implementation(project(":aidl"))
    implementation(libs.androidx.annotation)
}

// Not published: only AxManager uses it, as a Gradle project. Apps connect through axsh.