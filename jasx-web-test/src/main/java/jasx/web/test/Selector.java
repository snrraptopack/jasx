package jasx.web.test;

import jasx.web.test.Dom.Element;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The CSS selectors {@link TestPage} understands: compound selectors made of a tag, an {@code #id},
 * {@code .classes} and attribute tests ({@code [name]}, {@code [name=value]}, {@code [name="value"]}), joined by
 * descendant (space) and child ({@code >}) combinators, and lists of them separated by commas.
 * For example {@code form input[name=title]}, {@code ul > li.done}, {@code #save}.
 */
final class Selector {
    /** One compound selector: everything here must match the same element. */
    private record Compound(String tag, String id, List<String> classes, List<String[]> attributes) {
        boolean matches(Element element) {
            if (tag != null && !tag.equals(element.tag)) return false;
            if (id != null && !id.equals(element.attribute("id"))) return false;
            List<String> elementClasses = classesOf(element);
            if (!elementClasses.containsAll(classes)) return false;
            for (String[] test : attributes) {
                String value = element.attribute(test[0]);
                if (value == null) return false;
                if (test[1] != null && !test[1].equals(value)) return false;
            }
            return true;
        }
    }

    /** Compounds from left to right, and the combinator before each one after the first: ' ' or '>'. */
    private record Chain(List<Compound> compounds, List<Character> combinators) {}

    private final List<Chain> alternatives;

    private Selector(List<Chain> alternatives) {
        this.alternatives = alternatives;
    }

    static Selector parse(String selector) {
        List<Chain> alternatives = new ArrayList<>();
        for (String part : selector.split(",")) {
            alternatives.add(parseChain(part.strip(), selector));
        }
        return new Selector(alternatives);
    }

    /** The elements under {@code root} (itself excluded) that match, in document order. */
    List<Element> select(Element root) {
        List<Element> found = new ArrayList<>();
        Dom.eachElement(root, element -> {
            if (element != root && matches(element)) {
                found.add(element);
            }
        });
        return found;
    }

    boolean matches(Element element) {
        return alternatives.stream().anyMatch(chain -> matches(chain, chain.compounds().size() - 1, element));
    }

    /** Whether compound {@code index} matches {@code element}, and the compounds before it match its ancestors. */
    private static boolean matches(Chain chain, int index, Element element) {
        if (!chain.compounds().get(index).matches(element)) {
            return false;
        }
        if (index == 0) {
            return true;
        }
        char combinator = chain.combinators().get(index - 1);
        if (combinator == '>') {
            return isElement(element.parent) && matches(chain, index - 1, element.parent);
        }
        for (Element ancestor = element.parent; isElement(ancestor); ancestor = ancestor.parent) {
            if (matches(chain, index - 1, ancestor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isElement(Element node) {
        return node != null && !node.tag.equals("#root");
    }

    private static List<String> classesOf(Element element) {
        String value = element.attribute("class");
        return value == null || value.isBlank() ? List.of() : List.of(value.strip().split("\\s+"));
    }

    // ---------------------------------------------------------------------------------------------

    private static Chain parseChain(String text, String whole) {
        List<Compound> compounds = new ArrayList<>();
        List<Character> combinators = new ArrayList<>();
        int pos = 0;
        char pending = 0;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (Character.isWhitespace(c) || c == '>') {
                if (c == '>' || pending == 0) {
                    pending = c == '>' ? '>' : ' ';
                }
                pos++;
                continue;
            }
            if (!compounds.isEmpty()) {
                combinators.add(pending == 0 ? ' ' : pending);
            }
            pending = 0;
            int end = compoundEnd(text, pos);
            compounds.add(parseCompound(text.substring(pos, end), whole));
            pos = end;
        }
        if (compounds.isEmpty()) {
            throw new IllegalArgumentException("empty selector: \"" + whole + "\"");
        }
        return new Chain(compounds, combinators);
    }

    /** Where a compound ends: at whitespace or '>' outside brackets. */
    private static int compoundEnd(String text, int pos) {
        boolean inBrackets = false;
        for (int i = pos; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') inBrackets = true;
            else if (c == ']') inBrackets = false;
            else if (!inBrackets && (Character.isWhitespace(c) || c == '>')) return i;
        }
        return text.length();
    }

    private static Compound parseCompound(String text, String whole) {
        String tag = null;
        String id = null;
        List<String> classes = new ArrayList<>();
        List<String[]> attributes = new ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '#' || c == '.') {
                int end = nameEnd(text, pos + 1);
                String name = text.substring(pos + 1, end);
                if (c == '#') id = name;
                else classes.add(name);
                pos = end;
            } else if (c == '[') {
                int end = text.indexOf(']', pos);
                if (end < 0) throw new IllegalArgumentException("unclosed [ in selector \"" + whole + "\"");
                attributes.add(attributeTest(text.substring(pos + 1, end)));
                pos = end + 1;
            } else if (c == '*') {
                pos++;
            } else {
                int end = nameEnd(text, pos);
                if (end == pos) throw new IllegalArgumentException("unsupported selector \"" + whole + "\"");
                tag = text.substring(pos, end).toLowerCase(Locale.ROOT);
                pos = end;
            }
        }
        return new Compound(tag, id, classes, attributes);
    }

    private static String[] attributeTest(String inside) {
        int equals = inside.indexOf('=');
        if (equals < 0) {
            return new String[] {inside.strip().toLowerCase(Locale.ROOT), null};
        }
        String value = inside.substring(equals + 1).strip();
        boolean quoted = value.length() >= 2 && (value.startsWith("\"") || value.startsWith("'"));
        return new String[] {inside.substring(0, equals).strip().toLowerCase(Locale.ROOT),
                quoted ? value.substring(1, value.length() - 1) : value};
    }

    private static int nameEnd(String text, int pos) {
        int end = pos;
        while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '-'
                || text.charAt(end) == '_')) {
            end++;
        }
        return end;
    }
}
