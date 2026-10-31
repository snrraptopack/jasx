allprojects {
    group = "dev.jasx"
    version = "0.1.0-SNAPSHOT"
}

/** The modules that are published as artifacts (dev.jasx:<module>). The others are test-only. */
val publishedModules = setOf(
    "jasx-parser",
    "jasx-runtime",
    "jasx-compiler",
    "jasx-target-html",
    "jasx-processor",
    "jasx-gradle-plugin",
    "jasx-maven-plugin",
)

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    // Publishing: `./gradlew publishToMavenLocal` installs every published module in ~/.m2.
    if (name in publishedModules) {
        apply(plugin = "maven-publish")
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
        }
        // The Gradle plugin module gets its publications (the jar and the plugin marker) from the
        // java-gradle-plugin plugin; the other modules publish their Java component.
        if (name != "jasx-gradle-plugin") {
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("maven") {
                        from(components["java"])
                        pom {
                            name.set(project.name)
                            description.set("JASX: JSX-style markup for Java")
                            url.set("https://github.com/snrraptopack/jasx")
                            // Maven recognizes a plugin by this packaging (its jar holds META-INF/maven/plugin.xml).
                            if (project.name == "jasx-maven-plugin") {
                                packaging = "maven-plugin"
                            }
                        }
                    }
                }
            }
        }
    }
}
