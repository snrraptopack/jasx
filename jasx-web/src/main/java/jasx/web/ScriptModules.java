package jasx.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;

/**
 * The application's browser modules (targets/web.md §7.1): the modules the compiler made from {@code .jasx} files,
 * and the {@code .js} files next to them, which the build puts under {@code META-INF/jasx/js/} with a stamp,
 * {@code META-INF/jasx/js/.build}, holding a hash of them. Several jars may each bring their own.
 *
 * <p>The <em>build</em> is a short hash of every stamp, so the modules' URLs ({@code /_jasx/js/<build>/...}) change
 * whenever any of them changes, and can be cached for good.
 */
final class ScriptModules {
    static final String ROOT = "META-INF/jasx/js/";
    private static final String STAMP = ROOT + ".build";

    private final ClassLoader loader;
    /** The build, or null if the application has no modules. */
    private final String build;

    private ScriptModules(ClassLoader loader, String build) {
        this.loader = loader;
        this.build = build;
    }

    /** The modules {@code loader} sees. */
    static ScriptModules of(ClassLoader loader) {
        try {
            List<String> stamps = new ArrayList<>();
            Enumeration<URL> found = loader.getResources(STAMP);
            for (URL url : Collections.list(found)) {
                try (InputStream in = url.openStream()) {
                    stamps.add(new String(in.readAllBytes(), StandardCharsets.UTF_8).strip());
                }
            }
            if (stamps.isEmpty()) {
                return new ScriptModules(loader, null);
            }
            Collections.sort(stamps); // the order the class loader lists jars in doesn't change the build
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", stamps).getBytes(StandardCharsets.UTF_8));
            return new ScriptModules(loader, HexFormat.of().formatHex(digest, 0, 6));
        } catch (IOException | NoSuchAlgorithmException e) {
            return new ScriptModules(loader, null);
        }
    }

    /** The build, or null if there are no modules: then pages don't name a base URL for them. */
    String build() {
        return build;
    }

    /**
     * The answer to {@code GET <basePath>/js/<path>} (targets/web.md §8.1), where {@code path} is
     * {@code <build>/<file>}: the file, cached for good, or 404 for another build, a path that leaves the modules'
     * folder, a file that isn't {@code .js}, or one that doesn't exist.
     */
    WebApp.Response serve(String path) {
        String prefix = build + "/";
        boolean ours = build != null && path.startsWith(prefix);
        String file = ours ? path.substring(prefix.length()) : "";
        boolean safe = file.endsWith(".js") && !file.contains("..") && !file.startsWith("/") && !file.contains("\\");
        if (!safe) {
            return new WebApp.Response(404, "");
        }
        try (InputStream in = loader.getResourceAsStream(ROOT + file)) {
            if (in == null) {
                return new WebApp.Response(404, "");
            }
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new WebApp.Response(200, body, java.util.Map.of(
                    "Content-Type", "text/javascript; charset=utf-8",
                    "Cache-Control", "public, max-age=31536000, immutable"));
        } catch (IOException e) {
            return new WebApp.Response(404, "");
        }
    }
}
