package jasx.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides which intrinsic elements of a file get the scope class: those that one of the file's scoped
 * compounds <em>may match</em> (semantics.md §10.5).
 *
 * <p>Like {@link ScopedCss}, this is not a CSS selector engine. It reads only three things from each scoped
 * compound, outside parentheses, brackets and strings: a type selector at its start ({@code li}), its class
 * selectors ({@code .card}) and its ID selectors ({@code #main}). It compares them with what the source says
 * about the element: its tag name, and its {@code class} and {@code id} attributes when they are string
 * literals. Anything it can't judge counts as a possible match, so an element that the CSS could style is
 * never left without the class.
 *
 * <pre>
 *   scoped compounds: li, .card
 *   &lt;li&gt;                 may match (li)
 *   &lt;div class="card"&gt;   may match (.card)
 *   &lt;div class={c}&gt;      may match (c could be "card")
 *   &lt;ul&gt;, &lt;div&gt;          cannot match
 * </pre>
 */
final class ScopeMatch {

    /** What the source says about an element's {@code class} or {@code id} attribute. */
    record AttributeValue(boolean present, String literal) {
        static final AttributeValue ABSENT = new AttributeValue(false, null);
        /** An expression value, or no value: it could be anything. */
        static final AttributeValue ANY = new AttributeValue(true, null);

        static AttributeValue literal(String value) {
            return new AttributeValue(true, value);
        }
    }

    /**
     * What one scoped compound requires. {@code type} is null if it has no type selector;
     * {@code matchesAnything} is set for a compound this class can't read ({@code \}, {@code &}, {@code |}).
     */
    private record Compound(String type, List<String> classes, List<String> ids, boolean matchesAnything) {}

    private final List<Compound> compounds;

    private ScopeMatch(List<Compound> compounds) {
        this.compounds = compounds;
    }

    /** The matcher for a file's scoped compounds (semantics.md §10.4). */
    static ScopeMatch of(List<String> scopedCompounds) {
        List<Compound> compounds = new ArrayList<>();
        for (String text : scopedCompounds) {
            compounds.add(read(text));
        }
        return new ScopeMatch(compounds);
    }

    /** Whether any scoped compound may match an element with this tag name and these attributes. */
    boolean mayMatch(String tag, AttributeValue classAttribute, AttributeValue idAttribute) {
        for (Compound compound : compounds) {
            if (mayMatch(compound, tag, classAttribute, idAttribute)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mayMatch(Compound compound, String tag, AttributeValue classAttribute,
            AttributeValue idAttribute) {
        if (compound.matchesAnything()) {
            return true;
        }
        // 1. A type selector must be the element's tag name.
        if (compound.type() != null && !asciiEqualsIgnoreCase(compound.type(), tag)) {
            return false;
        }
        // 2. Each class selector needs the class, unless the class attribute could be anything.
        for (String className : compound.classes()) {
            if (!classAttribute.present()) {
                return false;
            }
            boolean literalWithoutIt = classAttribute.literal() != null
                    && !Set.of(classAttribute.literal().strip().split("[ \t\n\f\r]+")).contains(className);
            if (literalWithoutIt) {
                return false;
            }
        }
        // 3. Each ID selector needs that id, unless the id attribute could be anything.
        for (String id : compound.ids()) {
            if (!idAttribute.present()) {
                return false;
            }
            if (idAttribute.literal() != null && !idAttribute.literal().equals(id)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Reading a compound
    // ---------------------------------------------------------------------------------------------

    /** Reads the type, class and ID selectors of one scoped compound, such as {@code li.item:hover}. */
    private static Compound read(String text) {
        boolean unreadable = text.indexOf('\\') >= 0 || text.indexOf('&') >= 0 || text.indexOf('|') >= 0;
        if (unreadable) {
            return new Compound(null, List.of(), List.of(), true);
        }

        int typeEnd = nameEnd(text, 0);
        String type = typeEnd > 0 ? text.substring(0, typeEnd) : null;
        List<String> classes = new ArrayList<>();
        List<String> ids = new ArrayList<>();

        // Only the top level counts: :not(.x) and [data-x] don't require anything.
        int depth = 0;
        for (int j = typeEnd; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '"' || c == '\'') {
                j = ScopedCss.stringEnd(text, j);
            } else if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && (c == '.' || c == '#')) {
                int end = nameEnd(text, j + 1);
                if (end > j + 1) {
                    (c == '.' ? classes : ids).add(text.substring(j + 1, end));
                    j = end - 1;
                }
            }
        }
        return new Compound(type, classes, ids, false);
    }

    /** The end of the name starting at {@code from} ({@code from} itself if there is none). */
    private static int nameEnd(String text, int from) {
        int end = from;
        while (end < text.length() && isNameChar(text.charAt(end))) {
            end++;
        }
        return end;
    }

    /** ASCII letters and digits, {@code -}, {@code _}, and non-ASCII characters (semantics.md §10.5). */
    private static boolean isNameChar(char c) {
        boolean asciiLetterOrDigit = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
        return asciiLetterOrDigit || c == '-' || c == '_' || c >= 0x80;
    }

    private static boolean asciiEqualsIgnoreCase(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        for (int i = 0; i < a.length(); i++) {
            if (asciiLower(a.charAt(i)) != asciiLower(b.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static char asciiLower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }
}
