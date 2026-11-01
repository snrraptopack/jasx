# JASX for IntelliJ IDEA

Support for `.jasx` files (Java with JSX-style markup) in IntelliJ IDEA 2025.3 or later: highlighting, and errors, completion, go-to-definition and hover from the JASX language server.

The plugin is a thin client, like the VS Code extension. Everything it knows comes from two pieces both editors share:

- **Highlighting** comes from the TextMate grammar in [`editors/vscode/syntaxes`](../vscode/syntaxes), loaded by IntelliJ's TextMate support. The grammar builds on the Java and CSS grammars IntelliJ ships with. See the [VS Code README](../vscode/README.md#how-markup-is-recognized) for how it recognizes markup and where it falls short.
- **Errors, completion, go-to-definition, hover, find usages, rename and the Structure view** come from the JASX language server ([`jasx-lsp`](../../jasx-lsp)), through IntelliJ's own LSP support. The server behaves as in VS Code: JASX errors while you type, and after a build with the JASX Gradle or Maven plugin, the components of the project's jars, the project's own target, and javac's errors in `.jasx` files when you open or save one. See the [VS Code README](../vscode/README.md) for details.

The server runs on the IDE's own Java runtime, which has the compiler module the server needs to run javac. If a runtime lacks it, `JAVA_HOME` is used. Its messages are in the **Language Services** widget in the status bar.

## `.java` files that use components

A `.java` file calls components as the plain Java methods they compile to (`Pages.Home()`), so IntelliJ's own Java support must see the Java generated from `.jasx` files. It does when the project is a linked Gradle (or Maven) project built at least once: the JASX plugin adds `build/generated/sources/jasx/<set>` to the source set, and IntelliJ imports it as a generated source root. From then on, the language server keeps those files current as you edit `.jasx` files, so a new component or a changed prop is known in `.java` files without another build.

If a `.java` file reports `No candidates found` for a component, the Gradle build it belongs to isn't linked: open its `build.gradle.kts` and choose **Link Gradle Project** in the banner (or use **+** in the Gradle tool window), then build it once.

## Which IDEs

IntelliJ IDEA 2025.3 or later, the unified distribution, with or without a subscription. The plugin depends on IntelliJ's LSP API (`com.intellij.modules.lsp`), which JetBrains doesn't ship in open-source builds of IntelliJ (or in Android Studio), so it can't be installed there.

## Install

From the root of the repository:

```
./gradlew -p editors/intellij buildPlugin
```

This builds the language server and the plugin into `editors/intellij/build/distributions/jasx-intellij-<version>.zip`. In IntelliJ, open **Settings | Plugins**, click the gear icon, choose **Install Plugin from Disk...**, pick the zip, and restart the IDE when asked. Open a `.jasx` file: it should be highlighted, and a JASX error (an unclosed tag) should be underlined as you type.

The build downloads IntelliJ IDEA 2025.3 to compile against the first time. To use an IDE you already have instead, pass its directory:

```
./gradlew -p editors/intellij buildPlugin -PintellijPlatformLocalPath="C:/Program Files/JetBrains/IntelliJ IDEA 2026.1.1"
```

`./gradlew -p editors/intellij runIde` starts an IDE with the plugin installed, in a sandbox of its own (`.intellijPlatform/`).

## Tests

```
./gradlew -p editors/intellij test
```

The tests run a headless IntelliJ with the plugin. They check that the TextMate bundle gives `.jasx` files their grammar, and that IntelliJ starts the language server from the plugin's directory and completes the LSP handshake with it. What the server answers is tested in `jasx-lsp`.

## Later

A deeper plugin could make IntelliJ itself understand `.jasx` files: navigation from Java code to a component's `.jasx` source instead of the generated Java, and IntelliJ's own Java support for the Java inside `.jasx` files.
