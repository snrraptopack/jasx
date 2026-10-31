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
 * Compiles generated Java in memory with javac and remaps javac's diagnostics to the {@code .jasx} sources
 * through their span maps (diagnostics.md §6). Used by the conformance suite and tests.
 */
public final class JavacRunner {
    /** The code of a remapped javac diagnostic; it is printed as the prefix {@code javac:}. */
    public static final String CODE = "javac";

    private JavacRunner() {}

    public record Result(boolean success, Map<String, byte[]> classes, List<CompilerDiagnostic> diagnostics) {
        /** A class loader for the compiled classes. */
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

    /** Compiles {@code generated} files and plain {@code java} sources against {@code classpath}. */
    public static Result compile(List<GeneratedFile> generated, List<SourceFile> java, String classpath) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalStateException("javac is not available: run on a JDK, not a JRE");
        }
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        StandardJavaFileManager standard = javac.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8);
        Map<String, byte[]> classes = new HashMap<>();
        Map<JavaFileObject, GeneratedFile> origins = new IdentityHashMap<>();
        Map<JavaFileObject, SourceFile> plain = new IdentityHashMap<>();
        List<JavaFileObject> units = new ArrayList<>();
        for (GeneratedFile g : generated) {
            JavaFileObject unit = source(g.javaPath(), g.content());
            origins.put(unit, g);
            units.add(unit);
        }
        for (SourceFile s : java) {
            JavaFileObject unit = source(s.path(), s.content());
            plain.put(unit, s);
            units.add(unit);
        }
        JavaFileManager files = new ForwardingJavaFileManager<>(standard) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                    FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
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
        List<String> options = List.of("-classpath", classpath, "-proc:none", "-encoding", "UTF-8");
        boolean success = javac.getTask(null, files, collector, options, null, units).call();

        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        for (javax.tools.Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
            if (d.getKind() == javax.tools.Diagnostic.Kind.NOTE) {
                continue;
            }
            Diagnostic.Severity severity = d.getKind() == javax.tools.Diagnostic.Kind.ERROR
                    ? Diagnostic.Severity.ERROR : Diagnostic.Severity.WARNING;
            long start = d.getStartPosition() >= 0 ? d.getStartPosition() : Math.max(d.getPosition(), 0);
            long end = d.getEndPosition() >= start ? d.getEndPosition() : start;
            GeneratedFile g = origins.get(d.getSource());
            String path;
            String content;
            int s;
            int e;
            if (g != null) {
                path = g.source().path();
                content = g.source().content();
                s = g.spanMap().toSource((int) start);
                e = Math.max(s, g.spanMap().toSource((int) end));
            } else {
                SourceFile source = plain.get(d.getSource());
                path = source != null ? source.path() : "<javac>";
                content = source != null ? source.content() : "";
                s = (int) Math.min(start, content.length());
                e = (int) Math.min(end, content.length());
            }
            Diagnostic remapped = new Diagnostic(CODE, severity, d.getMessage(Locale.ROOT), s, e, List.of());
            diagnostics.add(new CompilerDiagnostic(path, remapped, new LineMap(content)));
        }
        return new Result(success, classes, diagnostics);
    }

    private static JavaFileObject source(String path, String content) {
        URI uri = URI.create("string:///" + path.replace('\\', '/').replace(" ", "%20"));
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }
}
