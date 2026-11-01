plugins {
    application
    id("dev.jasx")
}

jasx {
    target.set("web") // markup becomes live pages instead of HTML strings
}

dependencies {
    implementation("dev.jasx:jasx-web:0.1.0-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("web.Main")
}
