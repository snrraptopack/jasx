// Runs the conformance suite in jasx-spec/tests as JUnit dynamic tests.

dependencies {
    testImplementation(project(":jasx-parser"))
    testImplementation(project(":jasx-compiler"))
    testImplementation(project(":jasx-target-html"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    testImplementation("com.networknt:json-schema-validator:1.5.6")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    systemProperty("jasx.spec.dir", rootProject.file("jasx-spec").absolutePath)
    inputs.dir(rootProject.file("jasx-spec/tests"))
}
