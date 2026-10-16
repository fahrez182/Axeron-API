plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "frb.axeron.axsh"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = project.file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        prefab = true
    }
}

dependencies {
    implementation(libs.libcxx)
    // AxshBroker starts the remote shell through IAxeronService.
    implementation(project(":aidl"))
    implementation(project(":shared"))
    // AxshBootstrap sends the binder request from app_process, where there is no Context.
    implementation(libs.rikka.hidden.compat)
    // Its full broadcastIntent overload names hidden framework types (IIntentReceiver).
    compileOnly(libs.rikka.hidden.stub)
}

extra["publishLibrary"] = true
