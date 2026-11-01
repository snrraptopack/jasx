package jasx.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServerSupportProvider;

/**
 * Starts the JASX language server when a project opens its first {@code .jasx} file. IntelliJ keeps one
 * server per project ({@link JasxLspServerDescriptor} is project-wide) and stops it with the project.
 */
public final class JasxLspServerSupportProvider implements LspServerSupportProvider {
    @Override
    public void fileOpened(Project project, VirtualFile file, LspServerStarter starter) {
        if (JasxPlugin.isJasxFile(file)) {
            starter.ensureServerStarted(new JasxLspServerDescriptor(project));
        }
    }
}
