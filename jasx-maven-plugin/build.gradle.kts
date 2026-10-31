// The JASX Maven plugin (dev.jasx:jasx-maven-plugin): compiles .jasx files to Java in Maven builds.
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
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("META-INF/maven/plugin.xml") {
        filter { line -> line.replace("@version@", version) }
    }
}
