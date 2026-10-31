package jasx.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

/** Reads classpath resources (target descriptors, schemas, component indexes). */
final class Resources {
    private Resources() {}

    /**
     * Reads a resource as UTF-8 text, bypassing the JDK's cache of open jar files.
     *
     * <p>By default, reading from a {@code jar:} URL keeps the jar open and cached for the life of the JVM.
     * In a long-running process, such as a Gradle daemon, the jar may later be rebuilt, and the cached
     * handle then reads garbage ("ZipException: invalid LOC header"). Turning caching off opens the jar
     * fresh each time and closes it after reading.
     */
    static String read(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        connection.setUseCaches(false);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
