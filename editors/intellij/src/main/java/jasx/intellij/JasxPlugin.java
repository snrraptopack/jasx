package jasx.intellij;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.file.Path;

/**
 * Where the plugin's files are. Besides its jar, the plugin's directory holds the language server
 * ({@code server/jasx-lsp.jar}) and the TextMate bundle ({@code bundles/jasx}); the build puts them there
 * (build.gradle.kts, {@code prepareSandbox}), outside the jar, because IntelliJ needs real paths for both.
 */
final class JasxPlugin {
    /** The plugin's id, as in plugin.xml. */
    private static final PluginId ID = PluginId.getId("io.github.snrraptopack.jasx");

    private JasxPlugin() {}

    /** The directory the plugin is installed in. */
    static Path directory() {
        IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(ID);
        if (descriptor == null) {
            throw new IllegalStateException("the JASX plugin isn't loaded");
        }
        return descriptor.getPluginPath();
    }

    /** The language server's jar. */
    static Path serverJar() {
        return directory().resolve("server").resolve("jasx-lsp.jar");
    }

    /** The TextMate bundle with the JASX grammar. */
    static Path bundle() {
        return directory().resolve("bundles").resolve("jasx");
    }

    /** True for the files the plugin handles: {@code .jasx} files. */
    static boolean isJasxFile(VirtualFile file) {
        return "jasx".equals(file.getExtension());
    }
}
