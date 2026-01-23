plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
}

allprojects {
    tasks.withType<Javadoc> {
        (options as StandardJavadocDocletOptions).addStringOption(
            "Xdoclint:none", "-quiet"
        )
    }
}

apply(from = "manifest.gradle.kts")

subprojects {
    plugins.withId("com.android.library") {
        plugins.apply("maven-publish")

        extensions.findByName("android")?.let {
            (it as com.android.build.gradle.LibraryExtension).publishing {
                singleVariant("release")
            }
        }

        afterEvaluate {
            println(
                """
            === PROJECT DEBUG ===
            path            : ${project.path}
            name            : ${project.name}
            parent          : ${project.parent?.name}
            plugins         : ${plugins.map { it.javaClass.simpleName }}
            androidExt      : ${extensions.findByName("android")?.javaClass?.simpleName}
            publishLibrary  : ${findProperty("publishLibrary")}
            ====================
            """.trimIndent()
            )

            val publishLibrary =
                (findProperty("publishLibrary") as? Boolean) ?: false

            if (!publishLibrary) return@afterEvaluate

            val pub = extensions.findByName("publishing")
            println("PROJECT ${project.path} publishingExt = ${pub != null}")

            val group = if (project.parent == rootProject) {
                groupIdBase
            } else {
                "${groupIdBase}.${project.parent?.name}"
            }

            version = findProperty("api_version_code")!!

            println("${project.displayName}: ${group}:${project.name}:${version}")

            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("release") {
                        from(components["release"])
                        groupId = group
                        artifactId = project.name
                        version = version
                    }
                }
                repositories {
                    mavenLocal()
                }
            }
        }

    }

}

val groupIdBase = "dev.frb.axeron"


