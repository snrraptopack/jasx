// The web target in Spring Boot (3.x and 4.x, Spring MVC on a servlet container): auto-configuration of a
// WebApp from jasx.web.* properties, the runtime's requests under /_jasx, and controller methods that return
// pages. The application brings Spring Boot and its web starter; this module only compiles against them.
//
// Spring Boot 4 moved WebMvcRegistrations to another package. The one class that names it for Boot 4 is in its
// own source set (src/boot4), compiled against Boot 4 and packaged into the same jar; everything else is
// compiled against Boot 3 and works with both. The tests run twice: `test` on Boot 3, `springBoot4Test` on
// Boot 4, from the same sources.

plugins {
    `java-library`
}

val springBoot = "3.4.1"
val springBoot4 = "4.1.2"

/** Boot4MvcRegistrations, compiled against Spring Boot 4 only. */
val boot4: SourceSet = sourceSets.create("boot4")

dependencies {
    api(project(":jasx-web-servlet"))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:$springBoot")
    compileOnly("org.springframework:spring-webmvc:6.2.1")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")
    // Spring 6's null-safety annotations are meta-annotated with JSR 305's; without them javac warns.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    "boot4CompileOnly"(files(sourceSets.main.get().output.classesDirs))
    "boot4CompileOnly"(project(":jasx-web-servlet"))
    "boot4CompileOnly"("org.springframework.boot:spring-boot-webmvc:$springBoot4")
    "boot4CompileOnly"("org.springframework.boot:spring-boot-autoconfigure:$springBoot4")
    "boot4CompileOnly"("org.jspecify:jspecify:1.0.0")

    testImplementation(boot4.output) // as in the jar: present, and skipped on Boot 3
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:$springBoot"))
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<JavaCompile>("compileBoot4Java") {
    dependsOn(tasks.compileJava)
}

// One jar for both versions; its sources jar has both source sets too.
tasks.jar {
    from(boot4.output)
}
tasks.named<Jar>("sourcesJar") {
    from(boot4.allSource)
}

testing {
    suites {
        /** The starter's tests on Spring Boot 4, from the same sources as `test`. */
        register<JvmTestSuite>("springBoot4Test") {
            useJUnitJupiter()
            sources {
                java.setSrcDirs(listOf("src/test/java"))
                resources.setSrcDirs(listOf("src/test/resources"))
            }
            dependencies {
                implementation(project())
                implementation(boot4.output)
                implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBoot4"))
                implementation("org.springframework.boot:spring-boot-starter-webmvc")
                implementation("org.springframework.boot:spring-boot-starter-test")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("springBoot4Test"))
}
