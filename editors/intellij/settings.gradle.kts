// The JASX plugin for IntelliJ IDEA. It is a build of its own (like editors/vscode), because the IntelliJ
// Platform Gradle plugin downloads an IDE to compile against, which the main build shouldn't need.
//
// The main build is included so that the plugin can package the language server jar it builds
// (:jasx-lsp:serverJar). Build with the repository's wrapper: ./gradlew -p editors/intellij buildPlugin

rootProject.name = "jasx-intellij"

includeBuild("../..")
