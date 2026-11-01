package jasx.intellij;

import java.util.List;
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider;

/**
 * Gives IntelliJ's TextMate support the JASX grammar, so that {@code .jasx} files are highlighted as in VS
 * Code. The bundle is in VS Code's extension layout (a package.json naming the language and the grammar);
 * the grammar builds on the Java and CSS grammars that IntelliJ's TextMate support ships with.
 */
public final class JasxBundleProvider implements TextMateBundleProvider {
    @Override
    public List<PluginBundle> getBundles() {
        return List.of(new PluginBundle("JASX", JasxPlugin.bundle()));
    }
}
