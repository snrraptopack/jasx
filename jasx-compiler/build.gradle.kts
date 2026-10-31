// The JASX compiler (jasx-spec/semantics.md): component index, name resolution, factory lowering,
// line preservation, span maps and javac diagnostic remapping. It loads targets from the classpath and
// depends on no target at compile time.

plugins {
    `java-library`
    application
}

application {
    mainClass.set("jasx.compiler.Main")
}

dependencies {
    api(project(":jasx-parser"))
    // The html target is on the CLI's runtime classpath so `run` works out of the box; the compiler
    // itself never references it.
    runtimeOnly(project(":jasx-target-html"))
    testImplementation(project(":jasx-target-html"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
