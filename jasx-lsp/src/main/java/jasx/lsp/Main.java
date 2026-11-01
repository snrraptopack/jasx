package jasx.lsp;

import jasx.compiler.ComponentIndex;
import jasx.compiler.Target;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts the JASX language server on stdin and stdout, as editors expect: {@code java -jar jasx-lsp.jar}.
 * Nothing else may be written to stdout, because it carries the protocol.
 */
public final class Main {
    private Main() {}

    /** How long typing must pause before javac checks the change (JasxLanguageServer). */
    private static final long TYPING_PAUSE_MILLIS = 500;

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
        }, background, TYPING_PAUSE_MILLIS);
        // The first javac run in a fresh JVM is several times slower than later ones; do it now, before the
        // editor asks for anything.
        background.execute(Main::warmUp);

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

    /**
     * Completes Java once in a small made-up file, with the bundled target: javac's classes get loaded and
     * compiled by the JIT, and the index of the platform's types is built ({@link TypeIndex}).
     */
    private static void warmUp() {
        try {
            ClassLoader loader = Main.class.getClassLoader();
            String text = "class Warm { Object page(String name) { return <p>{name.toUpperCase().length() + Str}</p>; } }";
            JavaCompletions.at(Map.of("Warm.jasx", text), "Warm.jasx", text.indexOf("Str") + 3,
                    Target.load("html", loader), new ComponentIndex(), System.getProperty("java.class.path"), null);
        } catch (RuntimeException e) {
            // only a warm-up: the first real request just takes longer
        }
    }
}
