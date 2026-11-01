plugins {
    application
    id("io.github.snrraptopack.jasx")
}

jasx {
    target.set("web") // markup becomes live pages
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.1"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("io.github.snrraptopack:jasx-web-spring-boot-starter:0.1.0-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Spring reads parameter names (@PathVariable long id). In your own project the Spring Boot Gradle plugin
// (`id("org.springframework.boot")`) does this, and gives you `bootRun`; this example uses the plain
// application plugin because this repository builds with a Gradle version newer than that plugin supports.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

application {
    mainClass.set("board.Application")
}

// Page tests without a browser (jasx-web-test), on JUnit.
dependencies {
    testImplementation("io.github.snrraptopack:jasx-web-test:0.1.0-SNAPSHOT")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
