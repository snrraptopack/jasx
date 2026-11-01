package jasx.compiler;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps offsets in a generated Java file back to its {@code .jasx} source (semantics.md §6.6). Copied Java
 * code maps character for character; code synthesized for markup maps to the markup that produced it.
 */
public final class SpanMap {
    private record Span(int generatedStart, int generatedEnd, int sourceStart, int sourceEnd, boolean verbatim) {}

    private final List<Span> spans = new ArrayList<>();

    void add(int generatedStart, int generatedEnd, int sourceStart, int sourceEnd, boolean verbatim) {
        if (generatedEnd > generatedStart) {
            spans.add(new Span(generatedStart, generatedEnd, sourceStart, sourceEnd, verbatim));
        }
    }

    /**
     * Whether the generated offset is in Java code copied from the source, where offsets map character for
     * character. Editor tooling uses it to tell the user's own Java (a name to hover or rename) from code
     * synthesized for markup, which maps only to the markup as a whole.
     */
    public boolean isVerbatim(int generatedOffset) {
        Span span = spanAt(generatedOffset);
        return span != null && span.verbatim() && generatedOffset < span.generatedEnd();
    }

    /** The span that starts last at or before the generated offset, or null. */
    private Span spanAt(int generatedOffset) {
        int lo = 0;
        int hi = spans.size() - 1;
        Span found = null;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Span s = spans.get(mid);
            if (s.generatedStart() <= generatedOffset) {
                found = s;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** The source offset for a generated offset. */
    public int toSource(int generatedOffset) {
        Span found = spanAt(generatedOffset);
        if (found == null) {
            return 0;
        }
        if (generatedOffset >= found.generatedEnd()) {
            return found.verbatim() ? found.sourceEnd() : found.sourceStart();
        }
        return found.verbatim() ? found.sourceStart() + (generatedOffset - found.generatedStart()) : found.sourceStart();
    }
}
