package jasx.parser;

import java.util.Arrays;

/**
 * Maps UTF-16 offsets to 1-based lines and 0-based columns (jasx-spec/ast.md §3). CR, LF and CR LF
 * each end a line; CR LF counts as one terminator.
 */
public final class LineMap {
    private final int[] lineStarts;

    public LineMap(String source) {
        int[] starts = new int[16];
        int count = 1;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                    i++;
                }
                if (count == starts.length) {
                    starts = Arrays.copyOf(starts, count * 2);
                }
                starts[count++] = i + 1;
            }
        }
        this.lineStarts = Arrays.copyOf(starts, count);
    }

    public record Position(int line, int column) {}

    public Position position(int offset) {
        int i = Arrays.binarySearch(lineStarts, offset);
        int line = i >= 0 ? i : -i - 2;
        return new Position(line + 1, offset - lineStarts[line]);
    }
}
