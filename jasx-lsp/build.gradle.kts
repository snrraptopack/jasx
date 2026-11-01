// The JASX language server: reports JASX errors while you type, completes tag names and props, and offers
// go-to-definition and hover for components, over the Language Server Protocol (stdin/stdout). It has no
// third-party dependencies.
//
// `./gradlew :jasx-lsp:installServer` builds one self-contained jar and copies it into the VS Code
// extension (editors/vscode/server/jasx-lsp.jar), which starts it with `java -jar`.

plugins {
    application
}

application {
    mainClass.set("jasx.lsp.Main")
}

dependencies {
    implementation(project(":jasx-compiler"))
    // The html reference target is bundled, so that the server can check markup against its schema.
    implementation(project(":jasx-target-html"))
    // The web target, for tests of Java completion with its typed events and state.
    testImplementation(project(":jasx-web"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/** One jar with the server and everything it needs, runnable with `java -jar`. */
val serverJar = tasks.register<Jar>("serverJar") {
    archiveFileName.set("jasx-lsp.jar")
    manifest {
        attributes("Main-Class" to "jasx.lsp.Main")
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
}

/** Copies the server jar into the VS Code extension. */
tasks.register<Copy>("installServer") {
    from(serverJar)
    into(rootProject.layout.projectDirectory.dir("editors/vscode/server"))
}
