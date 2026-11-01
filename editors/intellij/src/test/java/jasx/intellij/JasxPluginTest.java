package jasx.intellij;

import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.api.LspServerManager;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.Collection;
import org.eclipse.lsp4j.ServerCapabilities;
import org.jetbrains.plugins.textmate.TextMateService;

/**
 * The plugin inside a headless IntelliJ: its TextMate bundle is found, and IntelliJ starts the language
 * server from the plugin's directory and completes the LSP handshake with it. What the server answers is
 * tested in jasx-lsp itself.
 */
public final class JasxPluginTest extends BasePlatformTestCase {
    /** How long the server may take to start: a JVM, and loading the target. */
    private static final int SERVER_START_TIMEOUT_SECONDS = 60;

    public void testTheBundleGivesJasxFilesTheirGrammar() {
        TextMateService textMate = TextMateService.getInstance();
        textMate.reloadEnabledBundles(); // loads the bundles in the background
        PlatformTestUtil.waitWithEventsDispatching("no TextMate language for .jasx",
                () -> textMate.getLanguageDescriptorByFileName("Page.jasx") != null, 30);
    }

    public void testIntelliJStartsTheServerAndCompletesTheHandshake() {
        LspServerManager manager = LspServerManager.getInstance(getProject());
        manager.ensureServerStarted(JasxLspServerSupportProvider.class, new JasxLspServerDescriptor(getProject()));

        LspServer server = awaitRunningServer(manager);
        ServerCapabilities capabilities = server.getInitializeResult().getCapabilities();
        assertTrue(capabilities.getHoverProvider().getLeft());
        assertNotNull(capabilities.getCompletionProvider());

        manager.stopServers(JasxLspServerSupportProvider.class);
    }

    /** Waits, pumping the event queue, until the project's JASX server runs. */
    private LspServer awaitRunningServer(LspServerManager manager) {
        PlatformTestUtil.waitWithEventsDispatching(
                "the JASX language server didn't start within " + SERVER_START_TIMEOUT_SECONDS + " s",
                () -> serverIn(manager, LspServerState.Running) != null
                        || serverIn(manager, LspServerState.ShutdownUnexpectedly) != null,
                SERVER_START_TIMEOUT_SECONDS);
        assertNull("the JASX language server stopped unexpectedly",
                serverIn(manager, LspServerState.ShutdownUnexpectedly));
        return serverIn(manager, LspServerState.Running);
    }

    /** The project's JASX server, if it is in the given state. */
    private static LspServer serverIn(LspServerManager manager, LspServerState state) {
        Collection<LspServer> servers = manager.getServersForProvider(JasxLspServerSupportProvider.class);
        return servers.stream().filter(server -> server.getState() == state).findFirst().orElse(null);
    }
}
