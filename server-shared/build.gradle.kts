import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.rikka.tools.refine)
    id("kotlin-parcelize")
}

android {
    namespace = "frb.axeron.server"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    implementation(libs.rikka.parcelablelist)
    annotationProcessor(libs.rikka.refine.annotation.processor)
    implementation(libs.rikka.refine.runtime)
    implementation(libs.rikka.refine.annotation)
    implementation(libs.rikka.hidden.compat)
    compileOnly(libs.rikka.hidden.stub)

    implementation(project(":api"))
    implementation(project(":aidl"))
    implementation(project(":shared"))
}

extra["publishLibrary"] = true