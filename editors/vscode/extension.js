// The VS Code side of the JASX language server.
//
// Syntax highlighting comes from the TextMate grammar alone. This file starts the language server
// (server/jasx-lsp.jar, built by `./gradlew :jasx-lsp:installServer`) with Java, and connects it to the
// .jasx files of the window. The server then reports JASX errors while you type, and answers
// go-to-definition and hover on component tags.

const fs = require("node:fs");
const path = require("node:path");
const vscode = require("vscode");
const { LanguageClient } = require("vscode-languageclient/node");

/** The running client, so that it can be stopped when the extension is deactivated. */
let client;

/** Starts the language server, unless it is turned off or can't be found. */
async function activate(context) {
  const settings = vscode.workspace.getConfiguration("jasx");
  if (!settings.get("languageServer.enabled", true)) {
    return;
  }

  const jar = context.asAbsolutePath(path.join("server", "jasx-lsp.jar"));
  if (!fs.existsSync(jar)) {
    vscode.window.showWarningMessage(
      "JASX: the language server isn't packaged with this extension (server/jasx-lsp.jar is missing). " +
        "Highlighting still works. See editors/vscode/README.md to build it.");
    return;
  }

  const java = findJava(settings);
  const server = { command: java, args: ["-jar", jar] };
  client = new LanguageClient(
    "jasx",
    "JASX Language Server",
    { run: server, debug: server },
    {
      documentSelector: [{ scheme: "file", language: "jasx" }],
      // Other files change what a .jasx file means (a component declared elsewhere), so the server hears
      // about every .jasx and .java file that changes on disk.
      synchronize: { fileEvents: vscode.workspace.createFileSystemWatcher("**/*.{jasx,java}") },
      initializationOptions: { target: settings.get("target", "html") },
    });

  try {
    await client.start();
  } catch (error) {
    vscode.window.showErrorMessage(
      `JASX: the language server didn't start with '${java}' (${error.message}). ` +
        "It needs Java 21 or later; set 'jasx.languageServer.java' to the java executable to use.");
  }
}

/**
 * The java executable: the jasx.languageServer.java setting, then JAVA_HOME, then the home that the
 * Java extension uses (java.jdt.ls.java.home), and finally plain "java" from the PATH.
 */
function findJava(settings) {
  const configured = settings.get("languageServer.java", "");
  if (configured) {
    return configured;
  }
  const executable = process.platform === "win32" ? "java.exe" : "java";
  const homes = [
    process.env.JAVA_HOME,
    vscode.workspace.getConfiguration("java").get("jdt.ls.java.home"),
  ];
  for (const home of homes) {
    if (home) {
      const candidate = path.join(home, "bin", executable);
      if (fs.existsSync(candidate)) {
        return candidate;
      }
    }
  }
  return "java";
}

/** Stops the language server with the extension. */
function deactivate() {
  return client ? client.stop() : undefined;
}

module.exports = { activate, deactivate };
