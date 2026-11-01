// The web target in a Spring Boot application (jasx-web-spring-boot-starter): pages are controller methods,
// state lives in Spring beans, and other parts of the application (a REST API) update open pages.
//
// Like examples/web, it takes JASX straight from this repository's source through a composite build, so
// nothing has to be published first.

pluginManagement {
    includeBuild("../..")
}

includeBuild("../..")

rootProject.name = "web-spring"
