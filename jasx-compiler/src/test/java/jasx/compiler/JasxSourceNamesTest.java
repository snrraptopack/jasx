package jasx.compiler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Class files that name their .jasx source, for stack traces and debuggers. */
class JasxSourceNamesTest {
    private static final Target HTML = Target.load("html", JasxSourceNamesTest.class.getClassLoader());
    private static final String CLASSPATH = System.getProperty("java.class.path");

    /** Throws from line 5, inside markup, and from a lambda (a synthetic method) on line 6. */
    private static final String SOURCE = """
            package app;
            import jasx.runtime.Element;
            class Pages {
              static Element Boom() {
                return <p>{Integer.parseInt("not a number")}</p>;
              }
              static Runnable Later() { return () -> { throw new IllegalStateException("later"); }; }
            }
            """;

    private static Map<String, byte[]> compile() {
        CompilationResult result = new JasxCompiler(HTML).compile(List.of(new SourceFile("app/Pages.jasx", SOURCE)));
        JavacRunner.Result compiled = JavacRunner.compile(result.files(), List.of(), CLASSPATH);
        assertTrue(compiled.success(), compiled.diagnostics().toString());
        return compiled.classes();
    }

    @Test
    void javacNamesTheGeneratedFile() {
        assertEquals("Pages.java", JasxSourceNames.sourceFile(compile().get("app.Pages")));
    }

    @Test
    void stackTracesNameTheJasxFileAndLine() throws ReflectiveOperationException {
        Map<String, byte[]> classes = new HashMap<>(compile());
        classes.replaceAll((name, bytes) -> JasxSourceNames.withSourceFile(bytes, "Pages.jasx"));
        ClassLoader loader = new JavacRunner.Result(true, classes, List.of()).classLoader(getClass().getClassLoader());
        Method boom = loader.loadClass("app.Pages").getDeclaredMethod("Boom");
        boom.setAccessible(true);

        try {
            boom.invoke(null);
            throw new AssertionError("expected an exception");
        } catch (InvocationTargetException e) {
            StackTraceElement frame = frameIn("app.Pages", e.getCause());
            assertEquals("Pages.jasx", frame.getFileName());
            assertEquals(5, frame.getLineNumber()); // the line of the markup in Pages.jasx
        }
    }

    private static StackTraceElement frameIn(String className, Throwable thrown) {
        for (StackTraceElement element : thrown.getStackTrace()) {
            if (element.getClassName().equals(className)) {
                return element;
            }
        }
        throw new AssertionError("no frame in " + className, thrown);
    }

    @Test
    void rewritesTheClassesOfGeneratedFilesOnly(@TempDir Path classes) throws IOException {
        Path app = Files.createDirectories(classes.resolve("app"));
        Files.write(app.resolve("Pages.class"), compile().get("app.Pages"));
        byte[] other = JasxSourceNames.withSourceFile(compile().get("app.Pages"), "Other.java");
        Files.write(app.resolve("Other.class"), other);

        int changed = JasxSourceNames.rewrite(classes, Set.of("app/Pages.java"));

        assertEquals(1, changed);
        assertEquals("Pages.jasx", JasxSourceNames.sourceFile(Files.readAllBytes(app.resolve("Pages.class"))));
        assertArrayEquals(other, Files.readAllBytes(app.resolve("Other.class"))); // not from a .jasx file
        assertEquals(0, JasxSourceNames.rewrite(classes, Set.of("app/Pages.java"))); // nothing left to do
    }
}
