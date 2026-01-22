import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

apply(from = "../manifest.gradle.kts")

android {
    namespace = "frb.axeron.shared"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 27

        consumerProguardFiles("consumer-rules.pro")

        buildConfigField(
            "String",
            "SERVER_VERSION_NAME",
            "\"${findProperty("api_version_name")}\""
        )
        buildConfigField(
            "int",
            "SERVER_VERSION_CODE",
            "${findProperty("api_version_code")}"
        )
        buildConfigField(
            "int",
            "SERVER_PATCH_CODE",
            "${findProperty("api_version_code")}"
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.androidx.annotation.jvm)
}

extra["publishLibrary"] = true