// The JASX Maven plugin (io.github.snrraptopack:jasx-maven-plugin): compiles .jasx files to Java in Maven builds.
//
// Maven finds a plugin's goals in META-INF/maven/plugin.xml. Maven projects generate that file with the
// maven-plugin-plugin; here it is written by hand (src/main/resources), and the version is filled in when
// the resources are processed. The Maven API is provided by Maven at run time, so it is compileOnly.

plugins {
    `java-library`
}

dependencies {
    implementation(project(":jasx-compiler"))
    compileOnly("org.apache.maven:maven-plugin-api:3.9.9")
    compileOnly("org.apache.maven:maven-core:3.9.9")
    testImplementation(project(":jasx-compiler"))
    // CompilerConfigurationTest reads POM configuration with Maven's own XML classes.
    testImplementation("org.apache.maven:maven-core:3.9.9")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("META-INF/maven/plugin.xml") {
        filter { line -> line.replace("@version@", version) }
    }
}

// The functional tests run real Maven builds of examples/hello-maven. Maven itself is downloaded as an
// ordinary dependency (the official distribution zip) and unpacked into build/maven, and the tests start it
// with java, as the mvn script does. The plugin and the JASX artifacts it needs are installed in ~/.m2 first.
val mavenDistribution = configurations.create("mavenDistribution") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    mavenDistribution("org.apache.maven:apache-maven:3.9.9:bin@zip")
}

val unpackMaven = tasks.register<Sync>("unpackMaven") {
    from({ zipTree(mavenDistribution.singleFile) })
    into(layout.buildDirectory.dir("maven"))
}

tasks.test {
    dependsOn(unpackMaven)
    // Everything examples/hello-maven resolves from ~/.m2: this plugin, its dependencies and the target.
    dependsOn(
        ":jasx-maven-plugin:publishToMavenLocal",
        ":jasx-compiler:publishToMavenLocal",
        ":jasx-parser:publishToMavenLocal",
        ":jasx-runtime:publishToMavenLocal",
        ":jasx-target-html:publishToMavenLocal",
    )
    val mavenHome = layout.buildDirectory.dir("maven/apache-maven-3.9.9")
    val example = rootProject.layout.projectDirectory.dir("examples/hello-maven")
    inputs.files(fileTree(example) { exclude("target/**") })
    doFirst {
        systemProperty("jasx.test.mavenHome", mavenHome.get().asFile.absolutePath)
        systemProperty("jasx.test.example", example.asFile.absolutePath)
    }
}
