package jasx.lsp;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jasx.compiler.ComponentIndex;
import jasx.compiler.EditorInfo;
import jasx.compiler.JavacRunner;
import jasx.compiler.Target;
import jasx.compiler.TargetClassLoader;

/**
 * What the build says about a source set, read from the file the Gradle or Maven plugin writes at
 * {@code build/jasx/editor/<sourceSet>.json} or {@code target/jasx/editor/<sourceSet>.json} ({@link EditorInfo}).
 *
 * <p>With it, the language server analyses a source directory as the build compiles it: with the target and
 * the components found on the project's classpath (so components of the project's jars are known), and it
 * can type-check {@code .jasx} files with javac, with the build's classpath, annotation processors and
 * release ({@link #javacOptions}).
 *
 * <p>The file exists once the project has been built. Until then, the server falls back to the target
 * bundled with it, and to no components from jars.
 *
 * @param file          the file this was read from
 * @param modified      its modification time when read, to notice when a build rewrites it
 * @param target        the project's target, loaded from its classpath
 * @param dependencies  the components of the project's dependencies
 * @param classpath     the compile classpath, as one string for javac
 * @param processorPath the annotation processor path, as one string for javac
 * @param release       the Java release the build compiles for, or null
 * @param loader        the open class loader of the target's own lowering, or null (see {@link #close})
 */
record BuildInfo(Path file, long modified, Target target, ComponentIndex dependencies, String classpath,
        String processorPath, Integer release, TargetClassLoader loader) {

    /** Releases the target's class loader, if it was kept open. Call it when this is no longer used. */
    void close() {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException e) {
            // Nothing to do: the loader is no longer used either way.
        }
    }


    /**
     * The file for a Gradle- or Maven-style source directory: for {@code <project>/src/main/java}, Gradle
     * writes {@code <project>/build/jasx/editor/main.json} and Maven {@code <project>/target/jasx/editor/main.json}.
     * If both exist, the newer one; if neither does, the Gradle one (so that its creation is noticed).
     */
    static Path fileFor(Path sourceDirectory) {
        Path sourceSet = sourceDirectory.getParent();     // <project>/src/main
        Path project = sourceSet.getParent().getParent(); // <project>
        String relative = EditorInfo.relativePath(sourceSet.getFileName().toString());
        Path gradle = project.resolve("build").resolve(relative);
        Path maven = project.resolve("target").resolve(relative);
        if (!Files.isRegularFile(maven)) {
            return gradle;
        }
        if (!Files.isRegularFile(gradle)) {
            return maven;
        }
        return modified(maven) > modified(gradle) ? maven : gradle;
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Reads {@code file}. */
    static BuildInfo read(Path file) {
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            EditorInfo info = EditorInfo.fromJson(Files.readString(file, StandardCharsets.UTF_8));

            // Like the compiler in the build, read only resources from the classpath, and the target's own
            // lowering class if it has one (semantics.md §7.4).
            List<Path> classpath = info.jasxClasspath().stream().map(Path::of).toList();
            TargetClassLoader loader = new TargetClassLoader(classpath);
            Target target;
            ComponentIndex dependencies;
            try {
                target = Target.load(info.target(), loader);
                dependencies = ComponentIndex.fromClasspath(loader);
            } catch (RuntimeException e) {
                loader.close();
                throw e;
            }

            // A custom lowering loads its classes while it runs, so its loader stays open until this is
            // replaced (close). Otherwise close it now: on Windows an open loader locks the project's jars.
            boolean keepOpen = target.lowering() != null;
            if (!keepOpen) {
                loader.close();
            }
            return new BuildInfo(file, modified, target, dependencies, String.join(File.pathSeparator, info.compileClasspath()),
                    String.join(File.pathSeparator, info.processorPath()), info.release(), keepOpen ? loader : null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether the file was rewritten (by a build) or deleted since this was read. */
    boolean isStale() {
        try {
            return Files.getLastModifiedTime(file).toMillis() != modified;
        } catch (IOException e) {
            return true;
        }
    }

    /** The options to type-check like the build; processors write into {@code workDirectory}. */
    JavacRunner.BuildOptions javacOptions(Path workDirectory) {
        return new JavacRunner.BuildOptions(classpath, processorPath, release, workDirectory);
    }
}
