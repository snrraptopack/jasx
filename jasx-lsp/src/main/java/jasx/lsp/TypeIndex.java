package jasx.lsp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.lang.model.element.Element;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/**
 * The names of the top-level types a file could import: those of the Java platform's {@code java.*} modules
 * and those of the classpath. Java completion offers them when a type name is typed that isn't in scope yet,
 * with the import it needs ({@link JavaCompletions}).
 *
 * <p>Only names are kept, never javac objects, so the index outlives the javac task that built it. Building it
 * reads every class file name of the classpath, and the platform's exported packages, so it is cached: the
 * platform by Java release, each classpath entry by its path, size and modification time (a rebuilt jar is
 * read again).
 *
 * <p>Classpath names are taken from file names alone, so a package-private class of a library is in the index
 * too; {@link JavaCompletions} checks that a suggested type is accessible before offering it.
 */
final class TypeIndex {
    private TypeIndex() {}

    /** The platform's public top-level types, by release ({@code 0}: the running JDK's). */
    private static final Map<Integer, List<String>> PLATFORM = new ConcurrentHashMap<>();

    /** A classpath entry as it was when read: a change to any of these reads it again. */
    private record EntryKey(String path, long size, long modified) {}

    /** The top-level class names of each classpath entry. */
    private static final Map<EntryKey, List<String>> CLASSPATH = new ConcurrentHashMap<>();

    /**
     * The qualified names of the public top-level types in the exported packages of the platform's
     * {@code java.*} modules, as {@code elements} (a javac task for {@code release}) sees them.
     */
    static List<String> platform(Elements elements, Integer release) {
        return PLATFORM.computeIfAbsent(release != null ? release : 0, unused -> readPlatform(elements));
    }

    private static List<String> readPlatform(Elements elements) {
        List<String> names = new ArrayList<>();
        for (ModuleElement module : elements.getAllModuleElements()) {
            if (!module.getQualifiedName().toString().startsWith("java.")) {
                continue; // jdk.* modules are rarely what a page imports
            }
            for (ModuleElement.Directive directive : module.getDirectives()) {
                boolean exportedToAll = directive instanceof ModuleElement.ExportsDirective exports
                        && exports.getTargetModules() == null;
                if (exportedToAll) {
                    addPublicTypes(((ModuleElement.ExportsDirective) directive).getPackage(), names);
                }
            }
        }
        return List.copyOf(names);
    }

    private static void addPublicTypes(PackageElement pkg, List<String> names) {
        for (Element element : pkg.getEnclosedElements()) {
            if (element instanceof TypeElement type && type.getModifiers().contains(Modifier.PUBLIC)) {
                names.add(type.getQualifiedName().toString());
            }
        }
    }

    /** The qualified names of the top-level classes of a classpath ({@code File.pathSeparator}-separated). */
    static List<String> classpath(String classpath) {
        List<String> names = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                names.addAll(entry(Path.of(entry)));
            }
        }
        return names;
    }

    private static List<String> entry(Path path) {
        try {
            if (!Files.exists(path)) {
                return List.of();
            }
            EntryKey key = new EntryKey(path.toString(), Files.size(path), Files.getLastModifiedTime(path).toMillis());
            return CLASSPATH.computeIfAbsent(key, unused -> Files.isDirectory(path) ? readDirectory(path) : readJar(path));
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<String> readDirectory(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .map(file -> className(directory.relativize(file).toString().replace('\\', '/')))
                    .filter(name -> name != null)
                    .toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static List<String> readJar(Path jar) {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = className(entries.nextElement().getName());
                if (name != null) {
                    names.add(name);
                }
            }
        } catch (IOException e) {
            return List.of(); // not a jar after all
        }
        return List.copyOf(names);
    }

    /**
     * The qualified name of a top-level class from its path in a jar or directory ({@code a/b/C.class} is
     * {@code a.b.C}), or null for anything else: other files, nested classes ({@code C$D}), {@code
     * module-info} and {@code package-info}, classes in the default package, and versioned or other entries
     * under {@code META-INF}.
     */
    static String className(String path) {
        if (!path.endsWith(".class") || path.startsWith("META-INF/")) {
            return null;
        }
        String name = path.substring(0, path.length() - ".class".length());
        boolean special = name.contains("$") || name.endsWith("module-info") || name.endsWith("package-info");
        if (special || !name.contains("/")) {
            return null;
        }
        return name.replace('/', '.');
    }
}
