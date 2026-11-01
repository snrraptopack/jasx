// The web target on any Jakarta Servlet 6 container (Tomcat 10.1+, Jetty 12, Undertow): an adapter between
// servlet requests and jasx.web.WebApp, and a ready servlet with pages. The container provides the servlet API.

plugins {
    `java-library`
}

dependencies {
    api(project(":jasx-web"))
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")

    // A real container for the tests.
    testImplementation("org.apache.tomcat.embed:tomcat-embed-core:10.1.30")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
