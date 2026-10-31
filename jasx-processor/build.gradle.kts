// The JASX annotation processor: writes META-INF/jasx/components.json for every compiled artifact, so
// that components can be used from jars (semantics.md §5.3).

plugins {
    `java-library`
}

dependencies {
    implementation(project(":jasx-compiler"))
    testImplementation(project(":jasx-target-html"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
