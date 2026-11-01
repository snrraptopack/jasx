// The shared runtime of interactive targets (jasx-spec/ui.md): reactive values (State, Derived, Effect and the
// tracking engine under them) and typed events with their handlers. A target such as `web` implements it for
// its platform: it schedules re-runs and delivers events. Dependency-free, like the core.

plugins {
    `java-library`
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
