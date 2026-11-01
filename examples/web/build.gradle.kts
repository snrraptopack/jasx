plugins {
    application
    id("io.github.snrraptopack.jasx")
}

jasx {
    target.set("web") // markup becomes live pages instead of HTML strings
}

dependencies {
    implementation("io.github.snrraptopack:jasx-web:0.1.0-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("web.Main")
}
