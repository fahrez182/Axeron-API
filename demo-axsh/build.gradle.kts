import com.android.build.api.variant.impl.VariantOutputImpl
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.frb.demo_axsh"

    defaultConfig {
        applicationId = "dev.frb.demo_axsh"
        // The demo's own version. The code stays the API's (from the root build): demos up to
        // 2.1.x carried the API's 21xxx, and a lower code would refuse to install over them.
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
        // IUserService, the demo services' interface.
        aidl = true
    }
    packaging {
        // libaxsh.so is exec'd, so it must be extracted to nativeLibraryDir.
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Named like the manager's APKs, so a release says which API version it was built from.
androidComponents {
    onVariants { variant ->
        val ts = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyMMddHHmm"))
        val buildTypeName = variant.buildType ?: variant.name
        variant.outputs.forEach { output ->
            if (output is VariantOutputImpl) {
                val verName = output.versionName.orNull
                val verCode = output.versionCode.orNull
                output.outputFileName.set(
                    "DemoAxsh_v${verName}_${verCode}-${buildTypeName}_$ts.apk"
                )
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.topjohnwu.libsu.core)
    // RootService, to see whether libsu's own root process starts through an axsh shell.
    implementation(libs.topjohnwu.libsu.service)
    // Our own libaxsh.so and the in-process broker, for axsh_direct_connection.
    implementation(project(":axsh"))
}
