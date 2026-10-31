plugins {
    application
    id("dev.jasx")
}

dependencies {
    // The target that gives markup its meaning: here, HTML strings.
    implementation("dev.jasx:jasx-target-html:0.1.0-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("hello.Main")
}
