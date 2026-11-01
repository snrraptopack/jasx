package jasx.intellij;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * How IntelliJ runs the JASX language server for a project: {@code java -jar server/jasx-lsp.jar}, talking
 * over stdio, for the project's {@code .jasx} files.
 *
 * <p>The server is the same as in VS Code; everything it knows (errors, completion, definition, hover)
 * comes from it. It needs Java 21 or later with the compiler module, because it runs javac
 * ({@code jdk.compiler}). The IDE's own runtime qualifies (IntelliJ runs on Java 21 or later), so it is
 * used unless it lacks the compiler, in which case {@code JAVA_HOME} is tried.
 */
final class JasxLspServerDescriptor extends ProjectWideLspServerDescriptor {
    JasxLspServerDescriptor(Project project) {
        super(project, "JASX");
    }

    @Override
    public boolean isSupportedFile(VirtualFile file) {
        return JasxPlugin.isJasxFile(file);
    }

    @Override
    public GeneralCommandLine createCommandLine() throws ExecutionException {
        Path jar = JasxPlugin.serverJar();
        if (!Files.isRegularFile(jar)) {
            throw new ExecutionException("The JASX language server is missing from the plugin: " + jar);
        }
        return new GeneralCommandLine(java().toString(), "-jar", runnableCopyOf(jar).toString());
    }

    /**
     * A copy of the server jar in the IDE's system directory, named after the jar's size and time, which the
     * server runs from. A running JVM locks its jar on Windows, so running the plugin's own jar would keep the
     * plugin from being updated while the IDE is open. Older copies are deleted when they are no longer in use.
     */
    private static Path runnableCopyOf(Path jar) throws ExecutionException {
        try {
            Path directory = PathManager.getSystemDir().resolve("jasx");
            String name = "jasx-lsp-" + Files.size(jar) + "-" + Files.getLastModifiedTime(jar).toMillis() + ".jar";
            Path copy = directory.resolve(name);
            if (!Files.isRegularFile(copy)) {
                Files.createDirectories(directory);
                Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING);
            }
            deleteOtherCopies(directory, copy);
            return copy;
        } catch (IOException e) {
            return jar; // run the plugin's own jar after all
        }
    }

    private static void deleteOtherCopies(Path directory, Path keep) {
        try (DirectoryStream<Path> copies = Files.newDirectoryStream(directory, "jasx-lsp-*.jar")) {
            for (Path copy : copies) {
                if (!copy.equals(keep)) {
                    try {
                        Files.deleteIfExists(copy);
                    } catch (IOException e) {
                        // still in use by a server of another project: deleted another time
                    }
                }
            }
        } catch (IOException e) {
            // nothing to clean up then
        }
    }

    /** The language id sent with each document; IntelliJ doesn't know {@code .jasx} by itself. */
    @Override
    public String getLanguageId(VirtualFile file) {
        return "jasx";
    }

    /** The java executable that runs the server. */
    private static Path java() throws ExecutionException {
        boolean ideRuntimeHasCompiler = ModuleLayer.boot().findModule("jdk.compiler").isPresent();
        if (ideRuntimeHasCompiler) {
            return executableIn(Path.of(System.getProperty("java.home")));
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && Files.isRegularFile(executableIn(Path.of(javaHome)))) {
            return executableIn(Path.of(javaHome));
        }
        throw new ExecutionException("The JASX language server needs a JDK 21 or later (it runs javac): "
                + "the IDE's runtime has no compiler module, and JAVA_HOME isn't set to a JDK.");
    }

    private static Path executableIn(Path javaHome) {
        return javaHome.resolve("bin").resolve(SystemInfo.isWindows ? "java.exe" : "java");
    }
}
