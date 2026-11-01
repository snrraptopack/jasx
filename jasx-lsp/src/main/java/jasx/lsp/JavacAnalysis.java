package jasx.lsp;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import jasx.compiler.GeneratedFile;
import jasx.compiler.SourceFile;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * javac's view of a group, centred on one {@code .jasx} file, for the questions the editor asks about Java
 * code: completion ({@link JavaCompletions}), hover ({@link JavaHovers}), and references and rename
 * ({@link JavaReferences}).
 *
 * <p>The group's generated Java and its {@code .java} files are analysed together, so that every name
 * resolves as in the build. Each tree knows where it came from ({@link #originOf}): a {@code .jasx} file
 * through its generated Java and span map, or a {@code .java} file as it is.
 *
 * <p>Errors don't stop the analysis ({@code should-stop.ifError=FLOW}): code being typed is rarely valid, and
 * a completion marker is an error by design.
 */
final class JavacAnalysis {
    /**
     * The source file a tree was parsed from.
     *
     * @param path      the file's path in the group, as the compiler names it ({@code app/Page.jasx})
     * @param generated for a {@code .jasx} file, its generated Java (the text javac parsed); null for a
     *                  {@code .java} file, whose text javac parsed as it is
     */
    record Origin(String path, GeneratedFile generated) {}

    final JavacTask task;
    final Trees trees;
    /** The tree of the file asked about. */
    final CompilationUnitTree unit;
    /** The generated file it was parsed from. */
    final GeneratedFile generated;
    /** Every file of the group, the one asked about included. */
    final List<CompilationUnitTree> group;
    /** Where each file of the group came from, by the URI javac knows it under. */
    private final Map<URI, Origin> origins;

    private JavacAnalysis(JavacTask task, CompilationUnitTree unit, GeneratedFile generated,
            List<CompilationUnitTree> group, Map<URI, Origin> origins) {
        this.task = task;
        this.trees = Trees.instance(task);
        this.unit = unit;
        this.generated = generated;
        this.group = group;
        this.origins = origins;
    }

    /** Where a file of the group came from. */
    Origin originOf(CompilationUnitTree tree) {
        return origins.get(tree.getSourceFile().toUri());
    }

    /**
     * Analyses the group, centred on the generated file of {@code path}, or returns null if javac couldn't
     * (the file has no generated Java, or javac failed).
     *
     * @param generated the group's generated files, one of which comes from {@code path}
     * @param java      the group's {@code .java} files
     * @param classpath the classpath for javac
     * @param release   the Java release the build compiles for, or null
     */
    static JavacAnalysis of(List<GeneratedFile> generated, List<SourceFile> java, String path, String classpath,
            Integer release) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<JavaFileObject> units = new ArrayList<>();
        Map<URI, Origin> origins = new HashMap<>();
        URI focus = null;
        GeneratedFile focusFile = null;
        for (GeneratedFile file : generated) {
            JavaFileObject unit = inMemory(file.javaPath(), file.content());
            units.add(unit);
            origins.put(unit.toUri(), new Origin(file.source().path(), file));
            if (file.source().path().equals(path)) {
                focus = unit.toUri();
                focusFile = file;
            }
        }
        if (focus == null) {
            return null;
        }
        for (SourceFile file : java) {
            JavaFileObject unit = inMemory(file.path(), file.content());
            units.add(unit);
            origins.put(unit.toUri(), new Origin(file.path(), null));
        }

        List<String> options = new ArrayList<>(List.of("-classpath", classpath, "-proc:none", "-encoding", "UTF-8",
                "-nowarn", "-implicit:none", "-XDshould-stop.ifError=FLOW"));
        if (release != null) {
            options.addAll(List.of("--release", release.toString()));
        }
        StandardJavaFileManager files = javac.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
        JavacTask task = (JavacTask) javac.getTask(null, files, diagnostic -> {}, options, null, units);
        try {
            List<CompilationUnitTree> trees = new ArrayList<>();
            task.parse().forEach(trees::add);
            task.analyze();
            CompilationUnitTree focusTree = null;
            for (CompilationUnitTree tree : trees) {
                if (tree.getSourceFile().toUri().equals(focus)) { // javac wraps file objects, but keeps their URIs
                    focusTree = tree;
                }
            }
            if (focusTree == null) {
                return null;
            }
            return new JavacAnalysis(task, focusTree, focusFile, trees, origins);
        } catch (IOException | RuntimeException e) {
            return null; // javac couldn't analyse it: no answer rather than an error
        }
    }

    private static JavaFileObject inMemory(String path, String content) {
        URI uri = URI.create("string:///" + path.replace('\\', '/').replace(" ", "%20"));
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }
}
