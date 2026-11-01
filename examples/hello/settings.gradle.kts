// A standalone example project that uses JASX.
//
// It takes JASX straight from this repository's source, through a Gradle composite build: the
// `io.github.snrraptopack.jasx` plugin and the `io.github.snrraptopack:jasx-*` dependencies are built from ../.. when needed, so nothing has
// to be published first. A project outside this repository would instead get them from a Maven
// repository (see README.md).

pluginManagement {
    includeBuild("../..")
}

includeBuild("../..")

rootProject.name = "hello"
