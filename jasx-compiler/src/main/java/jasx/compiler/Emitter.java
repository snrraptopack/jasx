package jasx.compiler;

import jasx.parser.LineMap;

/**
 * Builds a generated Java file while preserving lines (semantics.md §6.5). Before writing code that
 * belongs to a source position, it adds line breaks until the output is on that position's line, and it
 * never adds others. Copied source keeps its own line breaks, so lines stay aligned unless code is emitted
 * out of source order (component props are passed in parameter order).
 */
final class Emitter {
    private final String src;
    private final LineMap lines;
    private final StringBuilder out = new StringBuilder();
    private final SpanMap spans = new SpanMap();
    private int outLine = 1;

    Emitter(String src, LineMap lines) {
        this.src = src;
        this.lines = lines;
    }

    /** Copies source text verbatim. */
    void copy(int start, int end) {
        if (end <= start) {
            return;
        }
        catchUp(start);
        int generated = out.length();
        out.append(src, start, end);
        for (int i = start; i < end; i++) {
            char c = src.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= src.length() || src.charAt(i + 1) != '\n'))) {
                outLine++;
            }
        }
        spans.add(generated, out.length(), start, end, true);
    }

    /** Writes synthesized code for the source position {@code anchor}. */
    void emit(String code, int anchor) {
        catchUp(anchor);
        int generated = out.length();
        out.append(code);
        spans.add(generated, out.length(), anchor, anchor, false);
    }

    private void catchUp(int offset) {
        int target = lines.position(Math.min(offset, src.length())).line();
        while (outLine < target) {
            out.append('\n');
            outLine++;
        }
    }

    String text() {
        return out.toString();
    }

    SpanMap spans() {
        return spans;
    }
}
