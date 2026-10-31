package jasx.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** The named character references of the HTML Living Standard (grammar.md §5). */
final class Entities {
    private static final Map<String, String> TABLE = load();

    private Entities() {}

    /** Returns the decoded text of {@code &name;}, or {@code null} if {@code name} is not a named reference. */
    static String decode(String name) {
        return TABLE.get(name);
    }

    private static Map<String, String> load() {
        Map<String, String> table = new HashMap<>(4096);
        try (InputStream in = Entities.class.getResourceAsStream("entities.txt")) {
            if (in == null) {
                throw new IllegalStateException("missing resource jasx/parser/entities.txt");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int space = line.indexOf(' ');
                StringBuilder text = new StringBuilder();
                for (String cp : line.substring(space + 1).split(",")) {
                    text.appendCodePoint(Integer.parseInt(cp, 16));
                }
                table.put(line.substring(0, space), text.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.copyOf(table);
    }
}
