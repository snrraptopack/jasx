package jasx.compiler;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
import jasx.parser.Diagnostic;
import jasx.parser.LineMap;

/**
 * Compiles generated Java in memory with javac, and reports javac's errors at their {@code .jasx}
 * positions (diagnostics.md §6). Nothing is written to disk: sources are strings, and class files are
 * kept in a map.
 *
 * <p>An error javac reports in generated code is translated back through the file's {@link SpanMap}. For
 * example, an unknown variable inside {@code <p>{undefinedName}</p>} is reported at
 * {@code undefinedName} in the {@code .jasx} file, not in the generated Java.
 *
 * <p>Used by the conformance suite and the tests. A build tool would normally let its own Java
 * compilation compile the generated files.
 */
public final class JavacRunner {
    /** The code of a javac diagnostic. In console output it is shown as the prefix {@code javac:}. */
    public static final String CODE = "javac";

    private JavacRunner() {}

    /** The outcome of a compilation: whether it succeeded, the class files by class name, and diagnostics. */
    public record Result(boolean success, Map<String, byte[]> classes, List<CompilerDiagnostic> diagnostics) {
        /** A class loader that loads the compiled classes, delegating everything else to {@code parent}. */
        public ClassLoader classLoader(ClassLoader parent) {
            return new ClassLoader(parent) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = classes.get(name);
                    if (bytes == null) {
                        throw new ClassNotFoundException(name);
                    }
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
        }
    }

    /**
     * Compiles generated files together with plain Java sources.
     *
     * @param classpath the classpath for javac, which must contain the target and {@code jasx-runtime}
     */
    public static Result compile(List<GeneratedFile> generated, List<SourceFile> java, String classpath) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalStateException("javac is not available: run on a JDK, not a JRE");
        }

        // Remember where each in-memory source came from, to translate diagnostics back.
        Map<JavaFileObject, GeneratedFile> generatedSources = new IdentityHashMap<>();
        Map<JavaFileObject, SourceFile> plainSources = new IdentityHashMap<>();
        List<JavaFileObject> units = new ArrayList<>();
        for (GeneratedFile file : generated) {
            JavaFileObject unit = sourceInMemory(file.javaPath(), file.content());
            generatedSources.put(unit, file);
            units.add(unit);
        }
        for (SourceFile file : java) {
            JavaFileObject unit = sourceInMemory(file.path(), file.content());
            plainSources.put(unit, file);
            units.add(unit);
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        StandardJavaFileManager standard = javac.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8);
        Map<String, byte[]> classes = new HashMap<>();
        JavaFileManager files = classesInMemory(standard, classes);

        // -proc:none: annotation processors on the classpath must not run here.
        List<String> options = List.of("-classpath", classpath, "-proc:none", "-encoding", "UTF-8");
        boolean success = javac.getTask(null, files, collector, options, null, units).call();

        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        for (javax.tools.Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
            if (d.getKind() != javax.tools.Diagnostic.Kind.NOTE) {
                diagnostics.add(translate(d, generatedSources, plainSources));
            }
        }
        return new Result(success, classes, diagnostics);
    }

    /** Converts a javac diagnostic, mapping positions in generated code back to the {@code .jasx} file. */
    private static CompilerDiagnostic translate(javax.tools.Diagnostic<? extends JavaFileObject> d,
            Map<JavaFileObject, GeneratedFile> generatedSources, Map<JavaFileObject, SourceFile> plainSources) {
        Diagnostic.Severity severity = d.getKind() == javax.tools.Diagnostic.Kind.ERROR
                ? Diagnostic.Severity.ERROR
                : Diagnostic.Severity.WARNING;

        // javac reports NOPOS (-1) when it has no exact range.
        long start = d.getStartPosition() >= 0 ? d.getStartPosition() : Math.max(d.getPosition(), 0);
        long end = d.getEndPosition() >= start ? d.getEndPosition() : start;

        String path;
        String content;
        int sourceStart;
        int sourceEnd;
        GeneratedFile generated = generatedSources.get(d.getSource());
        SourceFile plain = plainSources.get(d.getSource());
        if (generated != null) {
            path = generated.source().path();
            content = generated.source().content();
            sourceStart = generated.spanMap().toSource((int) start);
            sourceEnd = Math.max(sourceStart, generated.spanMap().toSource((int) end));
        } else if (plain != null) {
            path = plain.path();
            content = plain.content();
            sourceStart = (int) Math.min(start, content.length());
            sourceEnd = (int) Math.min(end, content.length());
        } else {
            // A diagnostic not about one of our sources (a bad classpath entry, say).
            path = "<javac>";
            content = "";
            sourceStart = 0;
            sourceEnd = 0;
        }

        Diagnostic translated = new Diagnostic(CODE, severity, d.getMessage(Locale.ROOT), sourceStart, sourceEnd, List.of());
        return new CompilerDiagnostic(path, translated, new LineMap(content));
    }

    /** A Java source held in a string. */
    private static JavaFileObject sourceInMemory(String path, String content) {
        URI uri = URI.create("string:///" + path.replace('\\', '/').replace(" ", "%20"));
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }

    /** A file manager that stores every class file javac writes in {@code classes}, keyed by class name. */
    private static JavaFileManager classesInMemory(StandardJavaFileManager standard, Map<String, byte[]> classes) {
        return new ForwardingJavaFileManager<>(standard) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                    FileObject sibling) {
                URI uri = URI.create("mem:///" + className.replace('.', '/') + kind.extension);
                return new SimpleJavaFileObject(uri, kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return new ByteArrayOutputStream() {
                            @Override
                            public void close() {
                                classes.put(className, toByteArray());
                            }
                        };
                    }
                };
            }
        };
    }
}
