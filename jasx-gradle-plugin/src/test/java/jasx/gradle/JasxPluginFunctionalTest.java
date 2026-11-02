package jasx.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                    id("io.github.snrraptopack.jasx")
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

    /** The jars of a classpath passed in by the build, as Kotlin string literals: "C:/.../a.jar", ... */
    private static String kotlinFiles(String property) {
        return Arrays.stream(System.getProperty(property).split(File.pathSeparator))
                .map(path -> "\"" + path.replace('\\', '/') + "\"")
                .collect(Collectors.joining(", "));
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
    void reportsJavacErrorsInGeneratedCodeAtTheirJasxLocation() throws IOException {
        write("src/main/java/app/Pages.jasx", """
                package app;

                import jasx.runtime.Element;

                public class Pages {
                    public static Element Home() {
                        return <p>{undefinedName}</p>;
                    }
                }
                """);

        BuildResult result = gradle("compileJava").buildAndFail();

        // "undefinedName" is on line 7, at column 20 (1-based).
        String expected = project.toRealPath().resolve("src/main/java/app/Pages.jasx") + ":7:20: error: javac: ";
        assertTrue(result.getOutput().contains(expected), result.getOutput());
        // Reported once: compileJasx failed, so compileJava didn't run and print it in the generated file.
        assertEquals(TaskOutcome.FAILED, result.task(":compileJasx").getOutcome());
        assertNull(result.task(":compileJava"), result.getOutput());
        assertFalse(result.getOutput().contains("Pages.java"), result.getOutput());
    }

    @Test
    void leavesErrorsInJavaFilesToCompileJava() throws IOException {
        write("src/main/java/app/Pages.jasx", """
                package app;
                public class Pages {
                    public static jasx.runtime.Element Home() { return <p>x</p>; }
                }
                """);
        write("src/main/java/app/Broken.java", """
                package app;
                class Broken { int x = undefinedInJava; }
                """);

        BuildResult result = gradle("compileJava").buildAndFail();

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJasx").getOutcome());
        assertEquals(TaskOutcome.FAILED, result.task(":compileJava").getOutcome());
        assertTrue(result.getOutput().contains("Broken.java:2: error"), result.getOutput());
    }

    @Test
    void typeChecksForTheReleaseCompileJavaUses() throws IOException {
        // List.getFirst() is new in Java 21, so it doesn't exist for release 17.
        write("build.gradle.kts", Files.readString(project.resolve("build.gradle.kts"))
                + "\ntasks.compileJava { options.release.set(17) }\n");
        write("src/main/java/app/Pages.jasx", """
                package app;
                public class Pages {
                    public static jasx.runtime.Element Home() {
                        return <p>{java.util.List.of("a").getFirst()}</p>;
                    }
                }
                """);

        BuildResult result = gradle("compileJava").buildAndFail();

        String expected = project.toRealPath().resolve("src/main/java/app/Pages.jasx") + ":4:";
        assertTrue(result.getOutput().contains(expected), result.getOutput());
        assertEquals(TaskOutcome.FAILED, result.task(":compileJasx").getOutcome());
    }

    @Test
    void typeChecksWithTheCompilerArgumentsCompileJavaUses() throws IOException {
        // The incubating vector API is only visible with --add-modules: without compileJava's argument, the
        // check would report "package jdk.incubator.vector is not visible" in Pages.jasx.
        write("build.gradle.kts", Files.readString(project.resolve("build.gradle.kts"))
                + "\ntasks.compileJava { options.compilerArgs.addAll(listOf(\"--add-modules\", \"jdk.incubator.vector\")) }\n");
        write("src/main/java/app/Pages.jasx", """
                package app;
                public class Pages {
                    public static jasx.runtime.Element Home() {
                        return <p>{jdk.incubator.vector.IntVector.SPECIES_128.length()}</p>;
                    }
                }
                """);

        BuildResult result = gradle("compileJava").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJasx").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
    }

    @Test
    void browserModulesGoIntoTheResources() throws IOException {
        write("build.gradle.kts", """
                plugins {
                    java
                    id("io.github.snrraptopack.jasx")
                }

                dependencies {
                    implementation(files(%s))
                }

                jasx {
                    target.set("web")
                }
                """.formatted(kotlinFiles("jasx.test.webClasspath")));
        Files.delete(project.resolve("src/main/java/app/Main.java"));
        write("src/main/jasx/app/menu.js", "export function toggleMenu(event, { menu }) { menu.hidden = !menu.hidden; }\n");
        write("src/main/jasx/app/Pages.jasx", """
                package app;
                <script>
                  import { toggleMenu } from "./menu.js";
                </script>
                public class Pages {
                    public static jasx.runtime.Element Home() {
                        return <div><button @click="toggleMenu({menu: $menu})">Menu</button><nav ref="menu" hidden></nav></div>;
                    }
                }
                """);

        BuildResult result = gradle("jar").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJasx").getOutcome());
        Path modules = project.resolve("build/resources/main/META-INF/jasx/js");
        assertTrue(Files.readString(modules.resolve("app/Pages.jasx.js")).contains("export { toggleMenu };"));
        assertTrue(Files.readString(modules.resolve("app/menu.js")).contains("export function toggleMenu"));
        assertTrue(Files.readString(modules.resolve(".build")).matches("[0-9a-f]{16}"));
    }

    @Test
    void typeCheckCanBeTurnedOff() throws IOException {
        write("build.gradle.kts", Files.readString(project.resolve("build.gradle.kts"))
                + "\njasx { typeCheck.set(false) }\n");
        write("src/main/java/app/Pages.jasx", """
                package app;
                public class Pages {
                    public static jasx.runtime.Element Home() { return <p>{undefinedName}</p>; }
                }
                """);

        BuildResult result = gradle("compileJava").buildAndFail();

        // compileJava reports the error, in the generated file.
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJasx").getOutcome());
        assertEquals(TaskOutcome.FAILED, result.task(":compileJava").getOutcome());
    }

    @Test
    void writesTheClasspathsForTheLanguageServer() throws IOException {
        write("src/main/java/app/Pages.jasx", """
                package app;
                public class Pages {
                    public static jasx.runtime.Element Home() { return <p>x</p>; }
                }
                """);

        gradle("compileJasx").build();

        String info = Files.readString(project.resolve("build/jasx/editor/main.json"));
        assertTrue(info.contains("\"target\": \"html\""), info);
        assertTrue(info.contains("jasx-target-html"), info);
        assertTrue(info.contains("\"compileClasspath\""), info);
        assertTrue(info.contains("\"processorPath\""), info);
    }

    @Test
    void stackTracesNameTheJasxFile() throws IOException {
        write("src/main/java/app/Pages.jasx", """
                package app;

                public class Pages {
                    public static jasx.runtime.Element Home() {
                        return <p>{Integer.parseInt("not a number")}</p>;
                    }
                }
                """);
        write("src/main/java/app/Main.java", """
                package app;

                public class Main {
                    public static void main(String[] args) {
                        try {
                            Pages.Home();
                        } catch (NumberFormatException e) {
                            for (StackTraceElement frame : e.getStackTrace()) {
                                if (frame.getClassName().equals("app.Pages")) {
                                    System.out.println("frame: " + frame);
                                }
                            }
                        }
                    }
                }
                """);

        BuildResult result = gradle("run", "-q").build();

        // Line 5 of Pages.jasx, where the markup is.
        assertTrue(result.getOutput().contains("frame: app.Pages.Home(Pages.jasx:5)"), result.getOutput());
    }

    @Test
    void worksWithTheConfigurationCache() throws IOException {
        write("src/main/java/app/Pages.jasx", """
                package app;
                public class Pages {
                    public static jasx.runtime.Element Home() { return <p>cached</p>; }
                }
                """);

        BuildResult first = gradle("run", "--configuration-cache").build();
        BuildResult second = gradle("run", "--configuration-cache").build();

        assertTrue(first.getOutput().contains("<p>cached</p>"), first.getOutput());
        assertTrue(second.getOutput().contains("Configuration cache entry reused"), second.getOutput());
        assertTrue(second.getOutput().contains("<p>cached</p>"), second.getOutput());
    }

    @Test
    void usesTheComponentsOfAnotherProject() throws IOException {
        // A library whose .jasx components the processor publishes, and an application that uses them.
        write("settings.gradle.kts", "rootProject.name = \"multi\"\ninclude(\"lib\", \"app\")\n");
        write("build.gradle.kts", "");
        write("lib/build.gradle.kts", """
                plugins {
                    `java-library`
                    id("io.github.snrraptopack.jasx")
                }

                dependencies {
                    api(files(%s))
                    annotationProcessor(files(%s))
                }
                """.formatted(kotlinFiles("jasx.test.targetClasspath"), kotlinFiles("jasx.test.processorClasspath")));
        write("lib/src/main/java/lib/Ui.jasx", """
                package lib;

                import jasx.runtime.Element;

                public Element Badge(String text) {
                  return <b>{text}</b>;
                }
                """);
        write("app/build.gradle.kts", """
                plugins {
                    application
                    id("io.github.snrraptopack.jasx")
                }

                dependencies {
                    implementation(project(":lib"))
                }

                application {
                    mainClass.set("app.Main")
                }
                """);
        write("app/src/main/java/app/Pages.jasx", """
                package app;

                import jasx.runtime.Element;
                import lib.Ui;

                public Element Home() {
                  return <p><Ui.Badge text="from lib" /></p>;
                }
                """);
        write("app/src/main/java/app/Main.java", """
                package app;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println(jasx.html.Html.render(Pages.Home()));
                    }
                }
                """);

        BuildResult result = gradle(":app:run", "-q").build();

        assertTrue(result.getOutput().contains("<p><b>from lib</b></p>"), result.getOutput());
    }

    @Test
    void usesATargetWithItsOwnLoweringFromAnotherProject() throws IOException {
        // A third-party target (semantics.md §7.4): the html factory and schema, with a lowering class that
        // passes every dynamic child through shout.Shout.loud. The application selects it by its id.
        write("settings.gradle.kts", "rootProject.name = \"custom\"\ninclude(\"shout\", \"app\")\n");
        write("build.gradle.kts", "");
        write("shout/build.gradle.kts", """
                plugins {
                    `java-library`
                }

                dependencies {
                    api(files(%s))
                    compileOnly(files(%s))
                }
                """.formatted(kotlinFiles("jasx.test.targetClasspath"), kotlinFiles("jasx.test.compilerClasspath")));
        write("shout/src/main/resources/META-INF/jasx/target.json", """
                {
                  "id": "shout",
                  "specVersion": "1.0",
                  "factory": "jasx.html.Html",
                  "attributes": "jasx.html.attrs",
                  "schema": "schemas/html.json",
                  "lowering": "shout.ShoutLowering"
                }
                """);
        write("shout/src/main/java/shout/ShoutLowering.java", """
                package shout;

                import jasx.compiler.FactoryLowering;
                import jasx.compiler.LoweringOutput;
                import jasx.compiler.MarkupTree;
                import jasx.compiler.Target;

                public class ShoutLowering extends FactoryLowering {
                    public ShoutLowering(Target target) {
                        super(target);
                    }

                    @Override
                    protected void dynamicChild(MarkupTree.Dynamic child, LoweringOutput out) {
                        out.write("shout.Shout.loud(", child.start());
                        out.code(child.expression());
                        out.write(")", child.end() - 1);
                    }
                }
                """);
        write("shout/src/main/java/shout/Shout.java", """
                package shout;

                public final class Shout {
                    public static Object loud(Object value) {
                        return String.valueOf(value).toUpperCase();
                    }
                }
                """);
        write("app/build.gradle.kts", """
                plugins {
                    application
                    id("io.github.snrraptopack.jasx")
                }

                jasx {
                    target.set("shout")
                }

                dependencies {
                    implementation(project(":shout"))
                }

                application {
                    mainClass.set("app.Main")
                }
                """);
        write("app/src/main/java/app/Pages.jasx", """
                package app;

                import jasx.runtime.Element;

                public Element Home(String name) {
                  return <p>hello {name}</p>;
                }
                """);
        write("app/src/main/java/app/Main.java", """
                package app;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println(jasx.html.Html.render(Pages.Home("world")));
                    }
                }
                """);

        BuildResult result = gradle(":app:run", "-q").build();

        assertTrue(result.getOutput().contains("<p>hello WORLD</p>"), result.getOutput());
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
