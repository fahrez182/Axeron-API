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

// ---- libaxshboot.so: the bootstrap's own dex ----
//
// The native client starts AxshBootstrap in app_process. With the whole host APK as its
// classpath that takes over a second (a 32 MB APK measured ~1.1 s, against ~0.4 s for a tiny dex),
// so the bootstrap gets a dex of its own: R8-shrunk to what AxshBootstrap.main reaches, shipped
// as lib<abi>/libaxshboot.so next to libaxsh.so, which looks for it there. ART goes by the dex
// magic, not the name.

val bootstrapAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val bootstrapLibDir = layout.buildDirectory.dir("generated/bootstrapLib")
val bootstrapDexDir = layout.buildDirectory.dir("intermediates/bootstrapDex")

android.sourceSets.getByName("main").jniLibs.srcDir(bootstrapLibDir)

fun classesJars(configuration: String) = configurations.getByName(configuration).incoming
    .artifactView {
        attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
        lenient(true)
    }.files

val bootstrapDex = tasks.register<JavaExec>("bootstrapDex") {
    val ownJar = tasks.named("bundleLibRuntimeToJarRelease")
    val runtimeJars = classesJars("releaseRuntimeClasspath")
    // Compile-only (hidden-api stubs): referenced, provided by the framework at runtime.
    val libraryJars = classesJars("releaseCompileClasspath").minus(runtimeJars)
    val bootClasspath = androidComponents.sdkComponents.bootClasspath
    val rules = file("bootstrap-rules.pro")
    val outDir = bootstrapDexDir

    inputs.files(ownJar, runtimeJars, libraryJars, rules)
    outputs.dir(outDir)

    classpath = files(
        android.sdkDirectory.resolve("build-tools/${android.buildToolsVersion}/lib/d8.jar")
    )
    mainClass.set("com.android.tools.r8.R8")
    argumentProviders.add(CommandLineArgumentProvider {
        outDir.get().asFile.mkdirs()
        buildList {
            addAll(listOf("--release", "--min-api", "26", "--output", outDir.get().asFile.path))
            addAll(listOf("--pg-conf", rules.path))
            bootClasspath.get().forEach { addAll(listOf("--lib", it.asFile.path)) }
            libraryJars.forEach { addAll(listOf("--lib", it.path)) }
            ownJar.get().outputs.files.filter { it.name.endsWith(".jar") }.forEach { add(it.path) }
            runtimeJars.forEach { add(it.path) }
        }
    })
}

val bootstrapLib = tasks.register<Sync>("bootstrapLib") {
    into(bootstrapLibDir)
    bootstrapAbis.forEach { abi ->
        from(bootstrapDex.map { it.outputs.files.singleFile.resolve("classes.dex") }) {
            into(abi)
            rename { "libaxshboot.so" }
        }
    }
}

tasks.matching { it.name.matches(Regex("merge\\w*JniLibFolders")) }.configureEach {
    dependsOn(bootstrapLib)
}
