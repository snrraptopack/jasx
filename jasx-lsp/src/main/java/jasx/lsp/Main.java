package jasx.lsp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts the JASX language server on stdin and stdout, as editors expect: {@code java -jar jasx-lsp.jar}.
 * Nothing else may be written to stdout, because it carries the protocol.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        Transport transport = new Transport(System.in, System.out);
        // javac checks run on one background thread, one at a time; a daemon, so it never keeps the process up.
        ExecutorService background = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "jasx-javac");
            thread.setDaemon(true);
            return thread;
        });
        JasxLanguageServer server = new JasxLanguageServer(message -> {
            try {
                transport.write(message);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, background);

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
