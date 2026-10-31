package jasx.parser;

import java.util.List;

/** A JASX diagnostic (jasx-spec/diagnostics.md). Positions are UTF-16 offsets, {@code [start, end)}. */
public record Diagnostic(
        String code, Severity severity, String message, int start, int end, List<Related> related) {

    public enum Severity {
        ERROR,
        WARNING;

        public String jsonName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** A secondary location. */
    public record Related(String message, int start, int end) {}

    public static Diagnostic error(String code, String message, int start, int end) {
        return new Diagnostic(code, Severity.ERROR, message, start, end, List.of());
    }

    /** The console form {@code <file>:<line>:<column+1>: <severity>: <message> [<code>]}. */
    public String format(String file, LineMap lines) {
        LineMap.Position p = lines.position(start);
        return file + ":" + p.line() + ":" + (p.column() + 1) + ": " + severity.jsonName() + ": " + message
                + " [" + code + "]";
    }
}
