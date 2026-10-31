package jasx.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jasx.compiler.CompilationResult;
import jasx.compiler.ComponentIndex;
import jasx.compiler.JasxCompiler;
import jasx.compiler.JavacRunner;
import jasx.compiler.SourceFile;
import jasx.compiler.Target;
import jasx.html.Html;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComponentProcessorTest {
    private static final String CLASSPATH = System.getProperty("java.class.path");

    /** The outputs of an in-memory javac run with the processor. */
    private record Output(boolean success, Map<String, byte[]> classes, Map<String, String> resources, List<String> errors) {}

    private static Output compile(Map<String, String> sources) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standard = javac.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        Map<String, byte[]> classes = new HashMap<>();
        Map<String, String> resources = new HashMap<>();
        JavaFileManager files = new ForwardingJavaFileManager<>(standard) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                    FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return capture(bytes -> classes.put(className, bytes));
                    }
                };
            }

            @Override
            public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + relativeName), JavaFileObject.Kind.OTHER) {
                    @Override
                    public OutputStream openOutputStream() {
                        return capture(bytes -> resources.put(relativeName, new String(bytes, StandardCharsets.UTF_8)));
                    }
                };
            }
        };
        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((path, content) -> units.add(new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        }));
        JavaCompiler.CompilationTask task = javac.getTask(null, files, diagnostics, List.of("-classpath", CLASSPATH), null, units);
        task.setProcessors(List.of(new ComponentProcessor()));
        boolean success = task.call();
        List<String> errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(Locale.ROOT)).toList();
        return new Output(success, classes, resources, errors);
    }

    private interface BytesSink {
        void accept(byte[] bytes);
    }

    private static OutputStream capture(BytesSink sink) {
        return new ByteArrayOutputStream() {
            @Override
            public void close() {
                sink.accept(toByteArray());
            }
        };
    }

    private static final String LIB = """
            package lib;
            import jasx.Component;
            import jasx.Default;
            import jasx.Optional;
            import jasx.runtime.Element;
            import java.util.List;
            public class Ui {
              @Component public static Element Chip(String label, @Optional String color, @Default("\\"md\\"") String size) {
                return jasx.html.Html.h("span", null, label, "/", color, "/", size);
              }
              @Component public static Element Panel(String title, List<Element> children) {
                return jasx.html.Html.h("section", null, title, children);
              }
              public static class Nested {
                @Component public static <T> Element Each(List<T> items, java.util.function.Function<T, Element> row) {
                  return jasx.html.Html.fragment(items.stream().map(row).toList());
                }
              }
              public static Element NotIndexed() { return null; }
            }
            """;

    @Test
    void writesTheComponentIndex() {
        Output out = compile(Map.of("lib/Ui.java", LIB));
        assertTrue(out.success(), () -> String.join("\n", out.errors()));
        ComponentIndex index = ComponentIndex.fromJson(out.resources().get(ComponentIndex.RESOURCE));
        assertEquals(3, index.all().size());

        ComponentIndex.Component chip = index.get("lib.Ui", "Chip");
        assertEquals(List.of("label", "color", "size"), chip.params().stream().map(ComponentIndex.Prop::name).toList());
        assertEquals(List.of("REQUIRED", "OPTIONAL", "DEFAULT"), chip.params().stream().map(p -> p.kind().name()).toList());
        assertEquals("java.lang.String", chip.params().get(0).type());
        assertEquals("\"md\"", chip.prop("size").defaultValue());

        assertEquals(ComponentIndex.Children.LIST, index.get("lib.Ui", "Panel").children());
        ComponentIndex.Component each = index.get("lib.Ui.Nested", "Each");
        assertEquals("java.util.List<T>", each.params().get(0).type());
        assertNull(index.get("lib.Ui", "NotIndexed"));
    }

    @Test
    void indexesTheComponentsOfCompiledJasxFiles() {
        Target html = Target.load("html", getClass().getClassLoader());
        CompilationResult jasx = new JasxCompiler(html).compile(List.of(new SourceFile("app/Cards.jasx", """
                package app;
                import jasx.runtime.Element;
                public class Cards {
                  public static Element Card(String title) { return <h2>{title}</h2>; }
                  static Element helper() { return <p/>; }
                }
                """)));
        Output out = compile(Map.of("app/Cards.java", jasx.files().get(0).content()));
        assertTrue(out.success(), () -> String.join("\n", out.errors()));
        ComponentIndex index = ComponentIndex.fromJson(out.resources().get(ComponentIndex.RESOURCE));
        assertEquals(List.of("app.Cards.Card"), index.all().stream().map(c -> c.className() + "." + c.method()).toList());
    }

    @Test
    void reportsInvalidComponents() {
        Output out = compile(Map.of("bad/Bad.java", """
                package bad;
                import jasx.Component;
                import jasx.Default;
                import jasx.runtime.Element;
                class Bad {
                  @Component Element NotStatic() { return null; }
                  @Component static Element A(@Default("make()") String x) { return null; }
                  @Component static Element B(String x) { return null; }
                  @Component static Element B(int x) { return null; }
                }
                """));
        assertFalse(out.success());
        assertEquals(3, out.errors().size(), () -> String.join("\n", out.errors()));
        assertTrue(out.errors().get(0).endsWith("[JASX2010]"), out.errors().get(0));
        assertTrue(out.errors().get(1).endsWith("[JASX2011]"), out.errors().get(1));
        assertTrue(out.errors().get(2).endsWith("[JASX2006]"), out.errors().get(2));
    }

    @Test
    void writesNothingWithoutComponents() {
        Output out = compile(Map.of("p/Plain.java", "package p; class Plain {}"));
        assertTrue(out.success());
        assertFalse(out.resources().containsKey(ComponentIndex.RESOURCE));
    }

    /** The jar workflow: a library compiled with the processor, used from .jasx through its index only. */
    @Test
    void componentsFromACompiledLibrary(@TempDir Path libDir) throws IOException, ReflectiveOperationException {
        Output lib = compile(Map.of("lib/Ui.java", LIB));
        for (Map.Entry<String, byte[]> c : lib.classes().entrySet()) {
            Path file = libDir.resolve(c.getKey().replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, c.getValue());
        }
        ComponentIndex dependencies = ComponentIndex.fromJson(lib.resources().get(ComponentIndex.RESOURCE));

        Target html = Target.load("html", getClass().getClassLoader());
        CompilationResult page = new JasxCompiler(html, dependencies).compile(List.of(new SourceFile("app/Page.jasx", """
                package app;
                import jasx.runtime.Element;
                import java.util.List;
                import lib.Ui;
                import static lib.Ui.*;
                public class Page {
                  public static Element Test() {
                    return (
                      <Panel title="T">
                        <Chip label="a" />
                        <Ui.Nested.Each items={List.of(1, 2)} row={n -> <i>{n}</i>} />
                      </Panel>
                    );
                  }
                }
                """)));
        assertFalse(page.hasErrors(), page.diagnostics()::toString);
        JavacRunner.Result compiled = JavacRunner.compile(page.files(), List.of(), libDir + File.pathSeparator + CLASSPATH);
        assertTrue(compiled.success(), compiled.diagnostics()::toString);

        ClassLoader loader = compiled.classLoader(new java.net.URLClassLoader(
                new java.net.URL[] {libDir.toUri().toURL()}, getClass().getClassLoader()));
        Method test = loader.loadClass("app.Page").getMethod("Test");
        assertEquals("<section>T<span>a//md</span><i>1</i><i>2</i></section>", Html.render(test.invoke(null)));
    }
}
