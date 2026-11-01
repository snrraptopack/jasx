plugins {
    application
    id("io.github.snrraptopack.jasx")
}

dependencies {
    // The target that gives markup its meaning: here, HTML strings.
    implementation("io.github.snrraptopack:jasx-target-html:0.1.0-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("hello.Main")
}
