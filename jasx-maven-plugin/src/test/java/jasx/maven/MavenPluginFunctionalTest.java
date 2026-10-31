package jasx.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jasx.compiler.JasxSourceNames;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs real Maven builds of a copy of {@code examples/hello-maven}, with the Maven distribution the build
 * unpacked ({@code jasx.test.mavenHome}) and the JASX artifacts it installed in {@code ~/.m2}.
 */
class MavenPluginFunctionalTest {
    @TempDir
    Path project;

    /** The output of one Maven run. */
    private record Run(int exitCode, String output) {}

    @BeforeEach
    void copyExample() throws IOException {
        Path example = Path.of(System.getProperty("jasx.test.example"));
        try (Stream<Path> walk = Files.walk(example)) {
            for (Path source : walk.toList()) {
                Path relative = example.relativize(source);
                if (relative.startsWith("target")) {
                    continue; // left over from a build by hand
                }
                Path copy = project.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(source, copy);
                }
            }
        }
    }

    /**
     * Runs Maven in the project, as the {@code mvn} script would: the classworlds launcher with Maven's
     * {@code m2.conf}, on the JDK running the tests.
     */
    private Run maven(String... goals) throws IOException, InterruptedException {
        Path home = Path.of(System.getProperty("jasx.test.mavenHome"));
        Path launcher;
        try (Stream<Path> boot = Files.list(home.resolve("boot"))) {
            launcher = boot.filter(p -> p.getFileName().toString().startsWith("plexus-classworlds")
                    && p.toString().endsWith(".jar")).findFirst().orElseThrow();
        }
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath", launcher.toString(),
                "-Dclassworlds.conf=" + home.resolve("bin/m2.conf"),
                "-Dmaven.home=" + home,
                "-Dlibrary.jansi.path=" + home.resolve("lib/jansi-native"),
                "-Dmaven.multiModuleProjectDirectory=" + project,
                "org.codehaus.plexus.classworlds.launcher.Launcher",
                "-B", "-q"));
        command.addAll(List.of(goals));

        Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(5, TimeUnit.MINUTES), "Maven didn't finish");
        return new Run(process.exitValue(), output);
    }

    private Path pages() {
        return project.resolve("src/main/java/example/Pages.jasx");
    }

    private void editPages(String from, String to) {
        try {
            String text = Files.readString(pages());
            assertTrue(text.contains(from), "the example changed: " + from);
            Files.writeString(pages(), text.replace(from, to));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void compilesAndRunsTheExample() throws Exception {
        Run run = maven("compile", "exec:java");

        assertEquals(0, run.exitCode(), run.output());
        assertTrue(run.output().contains("<p class=\"greeting jx-"), run.output());
        assertTrue(run.output().contains(">Hello, Ada!</p>"), run.output());
        // source-names ran after the compiler: the stack trace names the .jasx file and its line.
        assertTrue(run.output().contains("thrown at example.Pages.Broken(Pages.jasx:25)"), run.output());
    }

    @Test
    void writesTheGeneratedJavaAndTheEditorInformation() throws Exception {
        Run run = maven("compile");

        assertEquals(0, run.exitCode(), run.output());
        assertTrue(Files.exists(project.resolve("target/generated-sources/jasx/example/Pages.java")));
        String info = Files.readString(project.resolve("target/jasx/editor/main.json"));
        assertTrue(info.contains("\"target\": \"html\""), info);
        assertTrue(info.contains("jasx-target-html"), info);
        byte[] pagesClass = Files.readAllBytes(project.resolve("target/classes/example/Pages.class"));
        assertEquals("Pages.jasx", JasxSourceNames.sourceFile(pagesClass));
    }

    @Test
    void reportsJasxErrorsAtTheirJasxLocation() throws Exception {
        editPages("<p class=\"greeting\">", "<p class=\"greeting\" colour=\"red\">");

        Run run = maven("compile");

        assertEquals(1, run.exitCode(), run.output());
        assertTrue(run.output().contains(pages() + ":11:30: error:"), run.output());
        assertTrue(run.output().contains("[JASX2101]"), run.output());
    }

    @Test
    void reportsJavacErrorsInMarkupOnceAtTheirJasxLocation() throws Exception {
        editPages("Hello, {name}!", "Hello, {nme}!");

        Run run = maven("compile");

        assertEquals(1, run.exitCode(), run.output());
        assertTrue(run.output().contains(pages() + ":11:38: error: javac: cannot find symbol"), run.output());
        // Reported by the plugin, before Maven's compiler, which would name the generated file.
        assertFalse(run.output().contains("Pages.java"), run.output());
    }

    @Test
    void theTypeCheckCanBeTurnedOff() throws Exception {
        editPages("Hello, {name}!", "Hello, {nme}!");

        Run run = maven("compile", "-Djasx.typeCheck=false");

        // Maven's compiler reports the error now, in the generated file.
        assertEquals(1, run.exitCode(), run.output());
        assertTrue(run.output().contains("Pages.java"), run.output());
    }
}
