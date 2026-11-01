package jasx.compiler;

import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The class loader a build uses to read a project's classpath for the compiler: the target's descriptor and
 * schema, the component indexes of dependencies ({@code META-INF/jasx/components.json}), and a target's own
 * lowering class (semantics.md §7.4).
 *
 * <p>It sees only the classpath and the JDK, not the build tool's classes, so that the project's target is
 * found and not one that happens to be on the build tool's classpath. The one exception is the compiler
 * itself: classes of {@code jasx.compiler} and {@code jasx.parser} always come from the compiler that runs, so
 * that a lowering class implements this compiler's {@link MarkupLowering} even if the classpath has another
 * copy of the compiler.
 *
 * <p>Close it when the compilation is done: on Windows an open loader keeps its jars locked.
 */
public final class TargetClassLoader extends URLClassLoader {
    private static final ClassLoader COMPILER = TargetClassLoader.class.getClassLoader();

    public TargetClassLoader(Iterable<Path> classpath) {
        super(urls(classpath), ClassLoader.getPlatformClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        boolean isCompilerClass = name.startsWith("jasx.compiler.") || name.startsWith("jasx.parser.");
        if (isCompilerClass) {
            return COMPILER.loadClass(name);
        }
        return super.loadClass(name, resolve);
    }

    private static URL[] urls(Iterable<Path> classpath) {
        List<URL> urls = new ArrayList<>();
        for (Path entry : classpath) {
            try {
                urls.add(entry.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new UncheckedIOException(e);
            }
        }
        return urls.toArray(URL[]::new);
    }
}
