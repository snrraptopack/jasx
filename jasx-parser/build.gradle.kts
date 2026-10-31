// The parser has no third-party runtime dependencies (AGENTS.md).

plugins {
    application
}

application {
    mainClass.set("jasx.parser.Main")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
