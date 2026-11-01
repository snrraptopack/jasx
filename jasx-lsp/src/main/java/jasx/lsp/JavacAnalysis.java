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
import java.util.List;
import java.util.Locale;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * javac's view of one file of a group, for the questions the editor asks about Java code: completion
 * ({@link JavaCompletions}) and hover ({@link JavaHovers}).
 *
 * <p>The group's generated Java and its {@code .java} files are analysed together, so that every name
 * resolves as in the build.
 *
 * <p>Errors don't stop the analysis ({@code should-stop.ifError=FLOW}): code being typed is rarely valid, and
 * a completion marker is an error by design.
 */
final class JavacAnalysis {
    final JavacTask task;
    final Trees trees;
    /** The tree of the file asked about. */
    final CompilationUnitTree unit;
    /** The generated file it was parsed from. */
    final GeneratedFile generated;
    /** Every file of the group, the one asked about included. */
    final List<CompilationUnitTree> group;

    private JavacAnalysis(JavacTask task, CompilationUnitTree unit, GeneratedFile generated,
            List<CompilationUnitTree> group) {
        this.task = task;
        this.trees = Trees.instance(task);
        this.unit = unit;
        this.generated = generated;
        this.group = group;
    }

    /**
     * Analyses the generated file of {@code path} in the context of its group, or returns null if javac
     * couldn't (the file has no generated Java, or javac failed).
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
        JavaFileObject focus = null;
        GeneratedFile focusFile = null;
        for (GeneratedFile file : generated) {
            JavaFileObject unit = inMemory(file.javaPath(), file.content());
            units.add(unit);
            if (file.source().path().equals(path)) {
                focus = unit;
                focusFile = file;
            }
        }
        if (focus == null) {
            return null;
        }
        for (SourceFile file : java) {
            units.add(inMemory(file.path(), file.content()));
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
                if (tree.getSourceFile().toUri().equals(focus.toUri())) { // javac wraps file objects
                    focusTree = tree;
                }
            }
            if (focusTree == null) {
                return null;
            }
            return new JavacAnalysis(task, focusTree, focusFile, trees);
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
