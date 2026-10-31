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

dependencies {
    testTargetClasspath(project(":jasx-target-html"))
}

tasks.test {
    inputs.files(testTargetClasspath)
    doFirst {
        systemProperty("jasx.test.targetClasspath", testTargetClasspath.asPath)
    }
}
