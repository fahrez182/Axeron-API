plugins {
    alias(libs.plugins.android.library)
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
    implementation(libs.androidx.core.ktx)
}

// Not published: only AxManager uses it, as a Gradle project. Apps connect through axsh.
