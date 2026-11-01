// The JASX plugin for IntelliJ IDEA: a thin client of the JASX language server (jasx-lsp), as the VS Code
// extension is. IntelliJ's own LSP support (com.intellij.modules.lsp) talks to the server, and its
// TextMate support highlights .jasx files with the same grammar as VS Code (editors/vscode/syntaxes).
//
//   ./gradlew -p editors/intellij buildPlugin   the plugin zip, in build/distributions/
//   ./gradlew -p editors/intellij runIde        an IDE with the plugin installed, to try it
//
// The IDE to compile against is downloaded once (IntelliJ IDEA 2025.3, the first unified release, whose
// free tier has the LSP API). To use an IDE already installed instead, pass its directory:
// -PintellijPlatformLocalPath="C:/Program Files/JetBrains/IntelliJ IDEA 2026.1.1"

import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.snrraptopack"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localPath = providers.gradleProperty("intellijPlatformLocalPath")
        if (localPath.isPresent) {
            local(localPath)
        } else {
            intellijIdea("2025.3.6")
        }
        bundledPlugin("org.jetbrains.plugins.textmate")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            // 2025.3: the first unified IntelliJ IDEA, where the LSP API is available without a subscription.
            sinceBuild = "253"
        }
    }
    // Searchable options index the plugin's settings pages; the plugin has none.
    buildSearchableOptions = false
}

/** The language server jar, built by the main build. */
val serverJar = gradle.includedBuild("jasx").task(":jasx-lsp:serverJar")
val serverJarFile = rootDir.resolve("../../jasx-lsp/build/libs/jasx-lsp.jar")

/**
 * The plugin's directory holds two things besides its jar: the server (server/jasx-lsp.jar), started with
 * java, and a TextMate bundle (bundles/jasx) in VS Code's extension layout. The bundle's grammar and
 * language configuration are copied from the VS Code extension, so both editors highlight alike.
 */
// Every sandbox gets them: the one buildPlugin zips, and the ones runIde and the tests use.
tasks.withType<PrepareSandboxTask>().configureEach {
    dependsOn(serverJar)
    from(serverJarFile) {
        into(pluginName.map { "$it/server" })
    }
    from(layout.projectDirectory.dir("src/bundle")) {
        into(pluginName.map { "$it/bundles/jasx" })
    }
    from(layout.projectDirectory.file("../vscode/language-configuration.json")) {
        into(pluginName.map { "$it/bundles/jasx" })
    }
    from(layout.projectDirectory.dir("../vscode/syntaxes")) {
        into(pluginName.map { "$it/bundles/jasx/syntaxes" })
    }
}
