package jasx.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs real Gradle builds of a small project that applies the plugin. The project depends on the html
 * target through local jar files (passed in by the build as {@code jasx.test.targetClasspath}).
 */
class JasxPluginFunctionalTest {
    @TempDir
    Path project;

    @BeforeEach
    void writeBuild() throws IOException {
        // The target's jars as Kotlin string literals: files("C:/.../jasx-target-html.jar", ...)
        String jars = Arrays.stream(System.getProperty("jasx.test.targetClasspath").split(File.pathSeparator))
                .map(path -> "\"" + path.replace('\\', '/') + "\"")
                .collect(Collectors.joining(", "));

        write("settings.gradle.kts", "rootProject.name = \"sample\"\n");
        write("build.gradle.kts", """
                plugins {
                    application
                    id("dev.jasx")
                }

                dependencies {
                    implementation(files(%s))
                }

                application {
                    mainClass.set("app.Main")
                }
                """.formatted(jars));
        write("src/main/java/app/Main.java", """
                package app;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println(jasx.html.Html.render(Pages.Home()));
                    }
                }
                """);
    }

    private void write(String path, String content) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private GradleRunner gradle(String... arguments) {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withArguments(arguments);
    }

    @Test
    void compilesJasxNextToJavaAndRuns() throws IOException {
        write("src/main/java/app/Pages.jasx", """
                package app;

                import jasx.runtime.Element;
                import java.util.List;

                public class Pages {
                    static Element Item(String title) {
                        return <li>{title}</li>;
                    }

                    public static Element Home() {
                        return <ul>{List.of("a", "b").stream().map(t -> <Item title={t} />).toList()}</ul>;
                    }
                }
                """);

        BuildResult result = gradle("run", "-q").build();

        assertTrue(result.getOutput().contains("<ul><li>a</li><li>b</li></ul>"), result.getOutput());
        assertTrue(Files.exists(project.resolve("build/generated/sources/jasx/main/app/Pages.java")));
    }

    @Test
    void compilesJasxFromTheJasxDirectoryAndUsesJavaComponents() throws IOException {
        write("src/main/java/app/Tags.java", """
                package app;

                import jasx.Component;
                import jasx.runtime.Element;

                public class Tags {
                    @Component
                    public static Element Bold(String text) {
                        return jasx.html.Html.h("b", null, text);
                    }
                }
                """);
        write("src/main/jasx/app/Pages.jasx", """
                package app;

                import jasx.runtime.Element;
                import static app.Tags.*;

                public class Pages {
                    public static Element Home() {
                        return <p><Bold text="hi" /></p>;
                    }
                }
                """);

        BuildResult result = gradle("run", "-q").build();

        assertTrue(result.getOutput().contains("<p><b>hi</b></p>"), result.getOutput());
    }

    @Test
    void reportsJasxErrorsWithTheirSourceLocation() throws IOException {
        write("src/main/java/app/Pages.jasx", """
                package app;

                import jasx.runtime.Element;

                public class Pages {
                    public static Element Home() {
                        return <div colour="red" />;
                    }
                }
                """);

        BuildResult result = gradle("compileJava").buildAndFail();

        // toRealPath: on Windows the temp directory may be given as a short (8.3) name, while Gradle
        // prints the long one.
        String expectedLocation = project.toRealPath().resolve("src/main/java/app/Pages.jasx") + ":7:21: error:";
        assertTrue(result.getOutput().contains(expectedLocation), result.getOutput());
        assertTrue(result.getOutput().contains("[JASX2101]"), result.getOutput());
    }

    @Test
    void isUpToDateWhenNothingChanged() throws IOException {
        write("src/main/java/app/Pages.jasx", """
                package app;
                public class Pages {
                    public static jasx.runtime.Element Home() { return <p>x</p>; }
                }
                """);

        assertEquals(TaskOutcome.SUCCESS, gradle("compileJasx").build().task(":compileJasx").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, gradle("compileJasx").build().task(":compileJasx").getOutcome());
    }

    @Test
    void removesGeneratedFilesOfDeletedSources() throws IOException {
        write("src/main/java/app/Pages.jasx", """
                package app;
                public class Pages {
                    public static jasx.runtime.Element Home() { return <p>x</p>; }
                }
                """);
        write("src/main/java/app/Old.jasx", """
                package app;
                class Old {
                    static jasx.runtime.Element View() { return <p>old</p>; }
                }
                """);
        gradle("compileJasx").build();
        Path oldOutput = project.resolve("build/generated/sources/jasx/main/app/Old.java");
        assertTrue(Files.exists(oldOutput));

        Files.delete(project.resolve("src/main/java/app/Old.jasx"));
        gradle("compileJasx").build();

        assertTrue(Files.notExists(oldOutput));
    }
}
