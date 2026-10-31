package jasx.parser;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line entry point.
 *
 * <pre>
 *   jasx-parser --dump-ast &lt;file.jasx&gt;      print the AST as JSON (ast.md §8)
 *   jasx-parser --diagnostics &lt;file.jasx&gt;   print the diagnostics as JSON (diagnostics.md §1)
 * </pre>
 *
 * Diagnostics are always also printed to stderr in console form. The exit code is 1 if there are errors.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("--dump-ast") || args[0].equals("--diagnostics"))) {
            System.err.println("usage: jasx-parser (--dump-ast | --diagnostics) <file.jasx>");
            System.exit(2);
        }
        Path file = Path.of(args[1]);
        String source = Files.readString(file, StandardCharsets.UTF_8);
        ParseResult result = JasxParser.parse(source, file.getFileName().toString());

        LineMap lines = new LineMap(source);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        result.diagnostics().forEach(d -> err.println(d.format(file.toString(), lines)));

        String json = args[0].equals("--dump-ast")
                ? AstJson.toJson(result.unit(), source)
                : AstJson.diagnosticsToJson(result.diagnostics(), source);
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        out.print(json + "\n");
        out.flush();
        System.exit(result.hasErrors() ? 1 : 0);
    }
}
