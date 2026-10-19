plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
}

android {
    namespace = "frb.axeron.api"

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
    implementation(project(":aidl"))
    implementation(project(":shared"))
    // Commands run through Axsh (newAxshProcess).
    implementation(project(":axsh"))

    implementation(libs.androidx.annotation.jvm)
    implementation(libs.androidx.core.ktx)
    implementation(libs.gson)
    implementation(libs.rikka.parcelablelist)
    implementation(libs.rikka.hidden.compat)
    compileOnly(libs.rikka.hidden.stub)

    implementation(libs.topjohnwu.libsu.core)
}

// Not published: only AxManager uses it, as a Gradle project. Apps connect through axsh.