// The group id is the Maven Central namespace verified for the project's GitHub account. It is temporary:
// once the domain jasx.dev is bought, the artifacts move to dev.jasx (docs/publishing.md). The Java
// packages are jasx.* either way, so the move only changes users' build files.
allprojects {
    group = "io.github.snrraptopack"
    version = "0.1.0-SNAPSHOT"
}

/**
 * The modules that are published as artifacts (io.github.snrraptopack:<module>), with the description that
 * goes in each POM. The other modules are test-only.
 */
val publishedModules = mapOf(
    "jasx-parser" to "JASX parser: tokenizes Java and parses the JSX-style markup of .jasx files into an AST.",
    "jasx-runtime" to "JASX runtime: the Element type and the @Component, @Optional and @Default annotations.",
    "jasx-compiler" to "JASX compiler: lowers the markup of .jasx files to plain Java and reports javac's errors at .jasx locations.",
    "jasx-target-html" to "JASX html reference target: renders markup to HTML.",
    "jasx-processor" to "JASX annotation processor: records a library's components for the projects that use it.",
    "jasx-gradle-plugin" to "JASX Gradle plugin: compiles .jasx files to Java before compileJava.",
    "jasx-maven-plugin" to "JASX Maven plugin: compiles .jasx files to Java in Maven builds.",
    "jasx-ui" to "JASX UI: reactive state, scheduling and typed events shared by interactive targets.",
    "jasx-web" to "JASX web target: server-driven pages whose reactive parts are updated over HTTP and server-sent events.",
    "jasx-web-servlet" to "JASX web target on Jakarta Servlet 6 containers.",
    "jasx-web-spring-boot-starter" to "JASX web target in Spring Boot: controller methods that return pages.",
    "jasx-web-test" to "Tests of JASX web pages without a browser.",
)

/**
 * Where every published module writes its artifacts, signatures and checksums before they are zipped into
 * the bundle that Maven Central receives (`publishToMavenCentral` below).
 */
val centralStaging = layout.buildDirectory.dir("central-staging")

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

    // Publishing: `./gradlew publishToMavenLocal` installs every published module in ~/.m2, and
    // `./gradlew publishToMavenCentral` uploads them to Maven Central (docs/publishing.md).
    if (name in publishedModules) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        // Maven Central requires a sources jar and a javadoc jar next to every jar.
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }
        // The comments are written for readers of the code, not as complete Javadoc, so javadoc's lint
        // (missing @param tags and the like) is turned off.
        tasks.withType<Javadoc>().configureEach {
            options.encoding = "UTF-8"
            (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
            options.quiet()
        }
        // Every jar carries the license.
        tasks.withType<Jar>().configureEach {
            metaInf {
                from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
            }
        }

        val moduleDescription = publishedModules.getValue(name)
        extensions.configure<PublishingExtension> {
            // The Gradle plugin module gets its publications (the jar and the plugin marker) from the
            // java-gradle-plugin plugin; the other modules publish their Java component.
            if (project.name != "jasx-gradle-plugin") {
                publications {
                    create<MavenPublication>("maven") {
                        from(components["java"])
                        // Maven recognizes a plugin by this packaging (its jar holds META-INF/maven/plugin.xml).
                        if (project.name == "jasx-maven-plugin") {
                            pom.packaging = "maven-plugin"
                        }
                    }
                }
            }

            // The metadata Maven Central requires in every POM. Name and description are conventions, so
            // the plugin marker keeps the name and description java-gradle-plugin gives it.
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.convention(project.name)
                    description.convention(moduleDescription)
                    url.set("https://github.com/snrraptopack/jasx")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("snrraptopack")
                            name.set("snrraptopack")
                            url.set("https://github.com/snrraptopack")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/snrraptopack/jasx.git")
                        developerConnection.set("scm:git:ssh://git@github.com/snrraptopack/jasx.git")
                        url.set("https://github.com/snrraptopack/jasx")
                    }
                }
            }

            repositories {
                maven {
                    name = "centralStaging"
                    url = uri(centralStaging)
                }
            }
        }

        // Every artifact is signed when a signing key is configured (signing.keyId, signing.password and
        // signing.secretKeyRingFile in ~/.gradle/gradle.properties). Without one, signing is skipped, so
        // publishToMavenLocal works on any machine; the Central bundle then refuses to be built.
        extensions.configure<SigningExtension> {
            isRequired = providers.gradleProperty("signing.keyId").isPresent
            sign(extensions.getByType<PublishingExtension>().publications)
        }

        // Staging starts from an empty directory, so the bundle holds only this build's files.
        tasks.withType<PublishToMavenRepository>().configureEach {
            if (name.endsWith("ToCentralStagingRepository")) {
                mustRunAfter(":cleanCentralStaging")
            }
        }
    }
}

val cleanCentralStaging = tasks.register<Delete>("cleanCentralStaging") {
    delete(centralStaging)
}

/**
 * The bundle for Maven Central: every published module in the Maven repository layout, each file with its
 * signature (.asc) and checksums (.md5, .sha1, written by Gradle), zipped. Central rejects a bundle with a
 * missing signature or a -SNAPSHOT version, so both are checked here first, where the message is clearer.
 */
val centralBundle = tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Stages and zips every published module for Maven Central."
    dependsOn(cleanCentralStaging)
    dependsOn(publishedModules.keys.map { ":$it:publishAllPublicationsToCentralStagingRepository" })

    from(centralStaging) {
        // Central computes its own metadata.
        exclude("**/maven-metadata.xml*")
    }
    destinationDirectory.set(layout.buildDirectory.dir("central"))
    archiveFileName.set("central-bundle.zip")

    val releaseVersion = version.toString()
    val stagingDir = centralStaging
    doFirst {
        check(!releaseVersion.endsWith("-SNAPSHOT")) {
            "Maven Central takes releases only: set a version without -SNAPSHOT in build.gradle.kts (now $releaseVersion)."
        }
        val checksumOrSignature = Regex(""".*\.(asc|md5|sha1|sha256|sha512)$""")
        val unsigned = stagingDir.get().asFile.walkTopDown()
            .filter { it.isFile && !it.name.startsWith("maven-metadata.xml") && !checksumOrSignature.matches(it.name) }
            .filter { !File(it.path + ".asc").exists() }
            .toList()
        check(unsigned.isEmpty()) {
            "Not signed: ${unsigned.joinToString { it.name }}. Configure the signing key in ~/.gradle/gradle.properties (docs/publishing.md)."
        }
    }
}

/**
 * Uploads the bundle to Maven Central's Publisher API as a deployment that waits for a person: Central
 * validates it, and it goes public only when "Publish" is pressed at central.sonatype.com/publishing/deployments.
 * The credentials are a user token (mavenCentralUsername, mavenCentralPassword) from ~/.gradle/gradle.properties.
 */
tasks.register("publishToMavenCentral") {
    group = "publishing"
    description = "Uploads every published module to Maven Central, to be published from the Central Portal."
    val bundle = centralBundle.flatMap { it.archiveFile }
    inputs.file(bundle)
    val username = providers.gradleProperty("mavenCentralUsername")
    val password = providers.gradleProperty("mavenCentralPassword")
    val deploymentName = "jasx $version"

    doLast {
        check(username.isPresent && password.isPresent) {
            "Set mavenCentralUsername and mavenCentralPassword (a user token from central.sonatype.com) in ~/.gradle/gradle.properties."
        }
        val token = java.util.Base64.getEncoder()
            .encodeToString("${username.get()}:${password.get()}".toByteArray(Charsets.UTF_8))

        // The API takes the zip as the "bundle" part of a multipart form.
        val boundary = "jasx-" + java.util.UUID.randomUUID()
        val partHeader = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"bundle\"; filename=\"central-bundle.zip\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val partEnd = "\r\n--$boundary--\r\n"
        val body = java.net.http.HttpRequest.BodyPublishers.concat(
            java.net.http.HttpRequest.BodyPublishers.ofString(partHeader),
            java.net.http.HttpRequest.BodyPublishers.ofFile(bundle.get().asFile.toPath()),
            java.net.http.HttpRequest.BodyPublishers.ofString(partEnd),
        )

        val name = java.net.URLEncoder.encode(deploymentName, Charsets.UTF_8)
        val request = java.net.http.HttpRequest.newBuilder()
            .uri(uri("https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED&name=$name"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(body)
            .build()
        val response = java.net.http.HttpClient.newHttpClient()
            .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())

        check(response.statusCode() in 200..299) {
            "Maven Central refused the upload (HTTP ${response.statusCode()}): ${response.body()}"
        }
        println("Uploaded deployment ${response.body()}.")
        println("Check and publish it at https://central.sonatype.com/publishing/deployments")
    }
}
