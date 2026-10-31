package jasx.lsp;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Starts the JASX language server on stdin and stdout, as editors expect: {@code java -jar jasx-lsp.jar}.
 * Nothing else may be written to stdout, because it carries the protocol.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        Transport transport = new Transport(System.in, System.out);
        JasxLanguageServer server = new JasxLanguageServer(message -> {
            try {
                transport.write(message);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        String message;
        while ((message = transport.read()) != null) {
            server.handle(message);
            if (server.exitRequested()) {
                System.exit(server.exitCode());
            }
        }
        // The client closed the connection without 'exit'.
        System.exit(1);
    }
}
