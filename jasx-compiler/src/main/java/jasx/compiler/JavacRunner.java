package jasx.compiler;

import com.sun.source.util.JavacTask;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
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
 * Compiles generated Java with javac, and reports javac's errors at their {@code .jasx} positions
 * (diagnostics.md §6).
 *
 * <p>An error javac reports in generated code is translated back through the file's {@link SpanMap}. For
 * example, an unknown variable inside {@code <p>{undefinedName}</p>} is reported at
 * {@code undefinedName} in the {@code .jasx} file, not in the generated Java.
 *
 * <p>Two entry points:
 *
 * <ul>
 *   <li>{@link #compile} compiles in memory, with no annotation processors, and keeps the class files.
 *       The conformance suite and the tests use it to run the generated code.
 *   <li>{@link #typeCheck} only checks, the way a build would compile: with the build's classpath, its
 *       annotation processors and its Java release. The Gradle plugin uses it so that javac's errors in
 *       markup are reported once, at their {@code .jasx} locations, before the build's own Java
 *       compilation would report them in the generated files.
 * </ul>
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
     * How a build compiles its Java, for {@link #typeCheck}.
     *
     * @param classpath     the compile classpath, entries separated by {@link java.io.File#pathSeparator}
     * @param processorPath the annotation processor path in the same form; empty for no processors
     * @param release       the {@code --release} to compile for, or {@code null} for the running JDK's
     * @param workDirectory a directory for the files annotation processors write (generated sources and
     *                      resources); its contents are replaced
     */
    public record BuildOptions(String classpath, String processorPath, Integer release, Path workDirectory) {}

    /**
     * Compiles generated files together with plain Java sources, in memory. Annotation processors don't run.
     *
     * @param classpath the classpath for javac, which must contain the target and {@code jasx-runtime}
     */
    public static Result compile(List<GeneratedFile> generated, List<SourceFile> java, String classpath) {
        JavaCompiler javac = systemJavac();
        Sources sources = new Sources(generated, java);

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        StandardJavaFileManager standard = javac.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8);
        Map<String, byte[]> classes = new HashMap<>();
        JavaFileManager files = classesInMemory(standard, classes);

        // -proc:none: annotation processors on the classpath must not run here.
        List<String> options = List.of("-classpath", classpath, "-proc:none", "-encoding", "UTF-8");
        boolean success = javac.getTask(null, files, collector, options, null, sources.units).call();

        return new Result(success, classes, sources.translateAll(collector));
    }

    /**
     * Type-checks generated files together with the plain Java sources they are compiled with, as the build
     * would compile them, and returns javac's errors and warnings. No class file is written: javac stops
     * after analysis.
     *
     * <p>Annotation processors on {@code options.processorPath()} run, as they would in the build, so code in
     * {@code .jasx} files can use the classes and members they generate. Errors in files a processor
     * generated keep that file's path.
     *
     * @throws IllegalArgumentException if javac rejects the options (a {@code --release} this JDK doesn't
     *                                  support, say)
     */
    public static List<CompilerDiagnostic> typeCheck(List<GeneratedFile> generated, List<SourceFile> java,
            BuildOptions options) throws IOException {
        JavaCompiler javac = systemJavac();
        Sources sources = new Sources(generated, java);

        // Processors write generated sources and resources: give them real directories, so that javac can
        // read the generated sources back, as it does in a build.
        Path classOutput = cleanDirectory(options.workDirectory().resolve("classes"));
        Path sourceOutput = cleanDirectory(options.workDirectory().resolve("sources"));

        List<String> arguments = new ArrayList<>(List.of(
                "-classpath", options.classpath(),
                "-d", classOutput.toString(),
                "-s", sourceOutput.toString(),
                "-encoding", "UTF-8",
                "-nowarn"));
        if (options.processorPath().isEmpty()) {
            arguments.add("-proc:none");
        } else {
            arguments.addAll(List.of("-processorpath", options.processorPath()));
        }
        if (options.release() != null) {
            arguments.addAll(List.of("--release", options.release().toString()));
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) javac.getTask(null, files, collector, arguments, null, sources.units);
            // analyze() parses, runs the annotation processors and attributes; it doesn't generate code.
            task.analyze();
        }
        return sources.translateAll(collector);
    }

    private static JavaCompiler systemJavac() {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalStateException("javac is not available: run on a JDK, not a JRE");
        }
        return javac;
    }

    /** Creates {@code directory}, or empties it if it exists. */
    private static Path cleanDirectory(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            try (Stream<Path> walk = Files.walk(directory)) {
                // Deepest first, so that directories are empty when they are deleted.
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    if (!p.equals(directory)) {
                        Files.delete(p);
                    }
                }
            }
        }
        return Files.createDirectories(directory);
    }

    /**
     * The compilation units given to javac, with a note of where each came from, so that diagnostics can be
     * translated back to the files the user wrote.
     */
    private static final class Sources {
        final List<JavaFileObject> units = new ArrayList<>();
        final Map<JavaFileObject, GeneratedFile> generated = new IdentityHashMap<>();
        final Map<JavaFileObject, SourceFile> plain = new IdentityHashMap<>();

        Sources(List<GeneratedFile> generatedFiles, List<SourceFile> plainFiles) {
            for (GeneratedFile file : generatedFiles) {
                JavaFileObject unit = sourceInMemory(file.javaPath(), file.content());
                generated.put(unit, file);
                units.add(unit);
            }
            for (SourceFile file : plainFiles) {
                JavaFileObject unit = sourceInMemory(file.path(), file.content());
                plain.put(unit, file);
                units.add(unit);
            }
        }

        /** Every error and warning javac reported, translated; notes are dropped. */
        List<CompilerDiagnostic> translateAll(DiagnosticCollector<JavaFileObject> collector) {
            List<CompilerDiagnostic> diagnostics = new ArrayList<>();
            for (javax.tools.Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
                if (d.getKind() != javax.tools.Diagnostic.Kind.NOTE) {
                    diagnostics.add(translate(d));
                }
            }
            return diagnostics;
        }

        /** Converts a javac diagnostic, mapping positions in generated code back to the {@code .jasx} file. */
        private CompilerDiagnostic translate(javax.tools.Diagnostic<? extends JavaFileObject> d) {
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
            GeneratedFile generatedFile = generated.get(d.getSource());
            SourceFile plainFile = plain.get(d.getSource());
            if (generatedFile != null) {
                path = generatedFile.source().path();
                content = generatedFile.source().content();
                sourceStart = generatedFile.spanMap().toSource((int) start);
                sourceEnd = Math.max(sourceStart, generatedFile.spanMap().toSource((int) end));
            } else if (plainFile != null) {
                path = plainFile.path();
                content = plainFile.content();
                sourceStart = (int) Math.min(start, content.length());
                sourceEnd = (int) Math.min(end, content.length());
            } else if (d.getSource() != null) {
                // A file an annotation processor generated: keep its own path and position.
                URI uri = d.getSource().toUri();
                path = "file".equals(uri.getScheme()) ? Path.of(uri).toString() : d.getSource().getName();
                content = readOrEmpty(d.getSource());
                sourceStart = (int) Math.min(start, content.length());
                sourceEnd = (int) Math.min(end, content.length());
            } else {
                // A diagnostic not about any file (a bad classpath entry, say).
                path = "<javac>";
                content = "";
                sourceStart = 0;
                sourceEnd = 0;
            }

            Diagnostic translated = new Diagnostic(CODE, severity, d.getMessage(Locale.ROOT), sourceStart, sourceEnd, List.of());
            return new CompilerDiagnostic(path, translated, new LineMap(content));
        }

        private static String readOrEmpty(JavaFileObject file) {
            try {
                return file.getCharContent(true).toString();
            } catch (IOException | RuntimeException e) {
                return ""; // the position is then shown as line 1; the message is still right
            }
        }
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
