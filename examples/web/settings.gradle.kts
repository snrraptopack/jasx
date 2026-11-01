// An example of the web target (jasx-spec/targets/web.md): interactive pages whose code runs on the server.
//
// Like examples/hello, it takes JASX straight from this repository's source through a composite build, so
// nothing has to be published first.

pluginManagement {
    includeBuild("../..")
}

includeBuild("../..")

rootProject.name = "web"
