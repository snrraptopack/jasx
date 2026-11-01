// Tests for web-target pages without a browser (like Phoenix's LiveViewTest): render a page, click, type and
// submit, and check its HTML. It goes through WebApp's public API as a browser would, and keeps the page's HTML
// as a small DOM that it patches with the server's answers. No dependencies besides jasx-web.

plugins {
    `java-library`
}

dependencies {
    api(project(":jasx-web"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
