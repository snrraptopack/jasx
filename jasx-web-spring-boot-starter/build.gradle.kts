// The web target in Spring Boot (3.x, Spring MVC on a servlet container): auto-configuration of a WebApp
// from jasx.web.* properties, the runtime's requests under /_jasx, and controller methods that return pages.
// The application brings Spring Boot and spring-boot-starter-web; this module only compiles against them.

plugins {
    `java-library`
}

val springBoot = "3.4.1"

dependencies {
    api(project(":jasx-web-servlet"))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:$springBoot")
    compileOnly("org.springframework:spring-webmvc:6.2.1")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")
    // Spring 6's null-safety annotations are meta-annotated with JSR 305's; without them javac warns.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:$springBoot"))
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
