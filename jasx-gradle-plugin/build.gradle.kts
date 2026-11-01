// The JASX Gradle plugin (id "dev.jasx"): compiles .jasx files to Java before compileJava.

plugins {
    `java-gradle-plugin`
}

dependencies {
    implementation(project(":jasx-compiler"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("jasx") {
            id = "dev.jasx"
            implementationClass = "jasx.gradle.JasxPlugin"
            displayName = "JASX"
            description = "Compiles .jasx files (Java with JSX-style markup) to Java."
        }
    }
}

// The functional tests build a small project that depends on the html target. Its jars (and the
// runtime) are passed to the tests as a classpath string, so that nothing has to be published.
val testTargetClasspath = configurations.create("testTargetClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// The annotation processor and what it needs, for the multi-project test (a library that publishes its
// components to another project).
val testProcessorClasspath = configurations.create("testProcessorClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// The compiler's jars, for the test of a target with its own lowering (semantics.md §7.4): the target's
// lowering class is compiled against them, as a third-party target would depend on jasx-compiler.
val testCompilerClasspath = configurations.create("testCompilerClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    testTargetClasspath(project(":jasx-target-html"))
    testProcessorClasspath(project(":jasx-processor"))
    testCompilerClasspath(project(":jasx-compiler"))
}

tasks.test {
    inputs.files(testTargetClasspath, testProcessorClasspath, testCompilerClasspath)
    doFirst {
        systemProperty("jasx.test.targetClasspath", testTargetClasspath.asPath)
        systemProperty("jasx.test.processorClasspath", testProcessorClasspath.asPath)
        systemProperty("jasx.test.compilerClasspath", testCompilerClasspath.asPath)
    }
}
