package jasx.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the CSS of a style block into its <em>scoped CSS</em> (semantics.md §10.4): every selector gets
 * {@code .scope} on its last compound selector, so that it only matches elements written in the file.
 *
 * <pre>
 *   .card { padding: 1rem }          →   .card.jx-1a2b3c4d { padding: 1rem }
 *   ul > li::before { content: "" }  →   ul > li.jx-1a2b3c4d::before { content: "" }
 *   :global(body) { margin: 0 }      →   body { margin: 0 }
 * </pre>
 *
 * <p>This is not a CSS parser. It reads CSS only as far as needed to find selectors: it knows comments,
 * strings, escapes, brackets, blocks, at-rules and nested rules, and never looks at properties or values.
 * It works in three steps:
 *
 * <ol>
 *   <li>{@link #removeComments}: comments are removed, and strings checked. A map from the cleaned text
 *       back to the original text is kept, so that errors point at the right place in the .jasx file.
 *   <li>The rule structure is walked ({@link #ruleList}, {@link #styleRule}, {@link #styleBlock},
 *       {@link #atRule}), copying the text to the output and scoping each selector list on the way.
 *   <li>{@link #tidy}: each line is trimmed and empty lines are dropped.
 * </ol>
 *
 * <p>Problems (JASX2014) are collected, not thrown, and the walk recovers, so one run reports them all.
 */
final class ScopedCss {

    /** A problem in the CSS, with its range as offsets in the original style block content. */
    record Problem(String message, int start, int end) {}

    /**
     * The scoped CSS, the problems found, and the scoped compounds: the compound selectors that got the
     * scope name, without what follows it (semantics.md §10.4). The CSS is meaningless if there are problems.
     */
    record Result(String css, List<Problem> problems, List<String> scopedCompounds) {}

    /** At-rules whose block holds rules, which are scoped too. Others' blocks are copied unchanged. */
    private static final Set<String> GROUPING_AT_RULES = Set.of(
            "@media", "@supports", "@container", "@layer", "@scope", "@starting-style", "@document");

    /** Pseudo-elements that may be written with a single colon (CSS 2). */
    private static final Set<String> LEGACY_PSEUDO_ELEMENTS = Set.of("before", "after", "first-line", "first-letter");

    private final String original;
    private final String scope;

    /** The text without comments, and for each of its characters, its offset in {@link #original}. */
    private String text;
    private int[] originalOffset;

    private final StringBuilder out = new StringBuilder();
    /** Keyed by offset and message, so that text scanned twice doesn't report a problem twice. */
    private final Map<String, Problem> problems = new LinkedHashMap<>();
    /** The scoped compounds, each once, in the order they were found. */
    private final Set<String> scopedCompounds = new LinkedHashSet<>();

    private ScopedCss(String original, String scope) {
        this.original = original;
        this.scope = scope;
    }

    /** Scopes {@code css}, the content of a style block, with the scope name {@code scope} (semantics.md §10.3). */
    static Result scope(String css, String scope) {
        ScopedCss scoper = new ScopedCss(css, scope);
        scoper.checkNoClosingStyleTag();
        scoper.removeComments();
        scoper.ruleList(0, -1);
        return new Result(tidy(scoper.out.toString()), List.copyOf(scoper.problems.values()),
                List.copyOf(scoper.scopedCompounds));
    }

    // ---------------------------------------------------------------------------------------------
    // Step 1: comments and strings
    // ---------------------------------------------------------------------------------------------

    /** {@code </style} would end an HTML style element early, wherever it is (semantics.md §10.4). */
    private void checkNoClosingStyleTag() {
        int at = original.toLowerCase(Locale.ROOT).indexOf("</style");
        if (at >= 0) {
            addProblem("a style block must not contain \"</style\"", at, at + 7);
        }
    }

    /**
     * Copies {@link #original} to {@link #text} without its comments. Strings are copied whole, so that
     * {@code /*} inside a string is not a comment; a string must end on its line.
     */
    private void removeComments() {
        StringBuilder clean = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        int n = original.length();
        int i = 0;
        while (i < n) {
            char c = original.charAt(i);
            boolean commentStart = c == '/' && i + 1 < n && original.charAt(i + 1) == '*';

            if (commentStart) {
                int close = original.indexOf("*/", i + 2);
                if (close < 0) {
                    problem("this comment is not closed", i, i + 2);
                    break;
                }
                i = close + 2;
            } else if (c == '"' || c == '\'') {
                int end = stringEnd(original, i);
                boolean closed = end < n && original.charAt(end) == c;
                if (!closed) {
                    problem("this string is not closed", i, i + 1);
                }
                int copyEnd = closed ? end + 1 : end;
                for (int j = i; j < copyEnd; j++) {
                    clean.append(original.charAt(j));
                    offsets.add(j);
                }
                i = copyEnd;
            } else {
                // An escape copies the escaped character too, so that \" or \/ is never special.
                int copyEnd = c == '\\' ? Math.min(i + 2, n) : i + 1;
                for (int j = i; j < copyEnd; j++) {
                    clean.append(original.charAt(j));
                    offsets.add(j);
                }
                i = copyEnd;
            }
        }
        text = clean.toString();
        originalOffset = offsets.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * The offset of the quote that closes the string starting at {@code quote}, or of the line terminator
     * or end of text where it stops unclosed.
     */
    static int stringEnd(String s, int quote) {
        char q = s.charAt(quote);
        int j = quote + 1;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '\\') {
                j += 2;
            } else if (c == q || c == '\n' || c == '\r') {
                return j;
            } else {
                j++;
            }
        }
        return s.length();
    }

    // ---------------------------------------------------------------------------------------------
    // Step 2: the rule structure
    // ---------------------------------------------------------------------------------------------

    /**
     * A rule list, as at the top level or in the block of {@code @media}: at-rules and style rules.
     *
     * @param open the offset of the {@code {} that opened this list, or -1 at the top level
     * @return the offset after the list's closing {@code }} (or the end of the text)
     */
    private int ruleList(int i, int open) {
        while (true) {
            i = copyWhitespace(i);
            if (i >= text.length()) {
                if (open >= 0) {
                    problemAt("this block is not closed", open);
                }
                return text.length();
            }
            char c = text.charAt(i);
            if (c == '}') {
                if (open >= 0) {
                    out.append('}');
                    return i + 1;
                }
                problemAt("this '}' closes nothing", i);
                i++;
            } else if (c == '@') {
                i = atRule(i, false);
            } else {
                i = styleRule(i);
            }
        }
    }

    /**
     * A style rule: a selector list, then a block. Inside another rule's block, this is a nested rule in
     * CSS nesting syntax, which is scoped the same way.
     */
    private int styleRule(int i) {
        int end = scanTo(i, "{;}");
        boolean hasBlock = end < text.length() && text.charAt(end) == '{';
        if (!hasBlock) {
            // At the rule-list level, something like "color: red;" or a selector without a block.
            problem("expected a '{' after this selector", i, Math.max(i + 1, end));
            boolean atSemicolon = end < text.length() && text.charAt(end) == ';';
            return atSemicolon ? end + 1 : end;
        }

        String prelude = text.substring(i, end);
        String trimmed = prelude.stripTrailing();
        out.append(scopeSelectorList(trimmed, i));
        out.append(prelude, trimmed.length(), prelude.length()); // the whitespace before '{'
        out.append('{');
        return styleBlock(end + 1, end);
    }

    /**
     * The block of a style rule: declarations, and nested rules.
     *
     * @param open the offset of the block's {@code {}
     * @return the offset after the block's {@code }}
     */
    private int styleBlock(int i, int open) {
        while (true) {
            i = copyWhitespace(i);
            if (i >= text.length()) {
                problemAt("this block is not closed", open);
                return text.length();
            }
            char c = text.charAt(i);
            if (c == '}') {
                out.append('}');
                return i + 1;
            } else if (c == ';') {
                out.append(';');
                i++;
            } else if (c == '@') {
                i = atRule(i, true);
            } else {
                int end = scanTo(i, "{;}");
                boolean isNestedRule = end < text.length() && text.charAt(end) == '{';
                if (isNestedRule) {
                    i = styleRule(i);
                } else {
                    out.append(text, i, end); // a declaration, copied as it is
                    i = end;
                }
            }
        }
    }

    /**
     * An at-rule: {@code @import ...;}, or {@code @media ... { rules }}, or another at-rule with a block,
     * which is copied unchanged ({@code @keyframes}, {@code @font-face}, ...).
     *
     * @param inStyleBlock whether the at-rule is nested in a style rule, so that a grouping at-rule's
     *     block holds declarations and nested rules rather than a rule list
     */
    private int atRule(int i, boolean inStyleBlock) {
        int nameEnd = i + 1;
        while (nameEnd < text.length() && isNameChar(text.charAt(nameEnd))) {
            nameEnd++;
        }
        String name = text.substring(i, nameEnd).toLowerCase(Locale.ROOT);

        int end = scanTo(nameEnd, "{;}");
        if (end >= text.length()) {
            out.append(text, i, end); // a statement at the very end, without ';'
            return end;
        }
        char stop = text.charAt(end);
        if (stop == ';') {
            out.append(text, i, end + 1);
            return end + 1;
        }
        if (stop == '}') {
            out.append(text, i, end); // a statement ended by the enclosing block
            return end;
        }

        // An at-rule with a block.
        out.append(text, i, end + 1);
        if (GROUPING_AT_RULES.contains(name)) {
            return inStyleBlock ? styleBlock(end + 1, end) : ruleList(end + 1, end);
        }
        int close = matchingBrace(end);
        if (close < 0) {
            problemAt("this block is not closed", end);
            out.append(text, end + 1, text.length());
            return text.length();
        }
        out.append(text, end + 1, close + 1);
        return close + 1;
    }

    // ---------------------------------------------------------------------------------------------
    // Selectors
    // ---------------------------------------------------------------------------------------------

    /** Scopes each complex selector of a selector list; {@code at} is the list's offset in {@link #text}. */
    private String scopeSelectorList(String list, int at) {
        List<String> scoped = new ArrayList<>();
        int partStart = 0;
        for (int comma : topLevelCommas(list)) {
            scoped.add(scopeComplexSelector(list.substring(partStart, comma), at + partStart));
            partStart = comma + 1;
        }
        scoped.add(scopeComplexSelector(list.substring(partStart), at + partStart));
        return String.join(", ", scoped);
    }

    /**
     * Scopes one complex selector, such as {@code ul > li:hover}: {@code .scope} goes into its last compound
     * selector, before a pseudo-element if it has one. {@code :global(S)} is replaced by {@code S}.
     */
    private String scopeComplexSelector(String part, int at) {
        String selector = part.strip();
        if (selector.isEmpty()) {
            problem("this selector is empty", at, at + Math.max(1, part.length()));
            return selector;
        }

        String global = globalArgument(selector);
        if (global != null) {
            if (global.isBlank()) {
                problem("this :global() is empty", at, at + part.length());
            }
            return global.strip();
        }

        int compoundStart = lastCompoundStart(selector);
        int insertAt = pseudoElementStart(selector, compoundStart);
        scopedCompounds.add(selector.substring(compoundStart, insertAt));
        return selector.substring(0, insertAt) + "." + scope + selector.substring(insertAt);
    }

    /** The argument of {@code :global(...)} if the whole selector is one, otherwise null. */
    private static String globalArgument(String selector) {
        String prefix = ":global(";
        boolean looksGlobal = selector.regionMatches(true, 0, prefix, 0, prefix.length()) && selector.endsWith(")");
        if (!looksGlobal) {
            return null;
        }
        // The '(' after :global must be closed by the last ')', not earlier: ":global(a) b:not(c)" isn't one.
        int open = prefix.length() - 1;
        int depth = 0;
        for (int j = open; j < selector.length(); j++) {
            char c = selector.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == '"' || c == '\'') {
                j = stringEnd(selector, j);
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return j == selector.length() - 1 ? selector.substring(open + 1, j) : null;
                }
            }
        }
        return null;
    }

    /** Where the last compound selector starts: after the last combinator outside brackets and strings. */
    private static int lastCompoundStart(String selector) {
        int start = 0;
        int depth = 0;
        for (int j = 0; j < selector.length(); j++) {
            char c = selector.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == '"' || c == '\'') {
                j = stringEnd(selector, j);
            } else if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && isCombinator(c)) {
                start = j + 1;
            }
        }
        return start;
    }

    /** The offset of the first pseudo-element in the compound starting at {@code from}, or the end. */
    private static int pseudoElementStart(String selector, int from) {
        int depth = 0;
        for (int j = from; j < selector.length(); j++) {
            char c = selector.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == '"' || c == '\'') {
                j = stringEnd(selector, j);
            } else if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && c == ':') {
                boolean doubleColon = j + 1 < selector.length() && selector.charAt(j + 1) == ':';
                if (doubleColon || isLegacyPseudoElement(selector, j + 1)) {
                    return j;
                }
            }
        }
        return selector.length();
    }

    /** Whether one of {@code before}, {@code after}, {@code first-line}, {@code first-letter} starts at {@code j}. */
    private static boolean isLegacyPseudoElement(String selector, int j) {
        int end = j;
        while (end < selector.length() && isNameChar(selector.charAt(end))) {
            end++;
        }
        return LEGACY_PSEUDO_ELEMENTS.contains(selector.substring(j, end).toLowerCase(Locale.ROOT));
    }

    private static boolean isCombinator(char c) {
        return Character.isWhitespace(c) || c == '>' || c == '+' || c == '~';
    }

    // ---------------------------------------------------------------------------------------------
    // Scanning helpers
    // ---------------------------------------------------------------------------------------------

    /** The offsets of the commas of {@code list} that are outside parentheses, brackets and strings. */
    private static List<Integer> topLevelCommas(String list) {
        List<Integer> commas = new ArrayList<>();
        int depth = 0;
        for (int j = 0; j < list.length(); j++) {
            char c = list.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == '"' || c == '\'') {
                j = stringEnd(list, j);
            } else if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && c == ',') {
                commas.add(j);
            }
        }
        return commas;
    }

    /**
     * The offset of the first of {@code stops} at or after {@code i} that is outside parentheses, brackets
     * and strings, or the end of the text. A brace inside parentheses or brackets is a problem.
     */
    private int scanTo(int i, String stops) {
        Deque<Integer> openBrackets = new ArrayDeque<>();
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"' || c == '\'') {
                i = stringEnd(text, i) + 1;
                continue;
            }
            if (c == '(' || c == '[') {
                openBrackets.push(i);
            } else if (c == ')' || c == ']') {
                openBrackets.poll();
            } else if (c == '{' || c == '}') {
                if (!openBrackets.isEmpty()) {
                    problemAt("a brace can't be inside parentheses or brackets", i);
                    openBrackets.clear(); // recover: treat the brace as a real one
                }
                if (stops.indexOf(c) >= 0) {
                    return i;
                }
            } else if (openBrackets.isEmpty() && stops.indexOf(c) >= 0) {
                return i;
            }
            i++;
        }
        for (int open : openBrackets) {
            problemAt("this " + (text.charAt(open) == '(' ? "parenthesis" : "bracket") + " is not closed", open);
        }
        return text.length();
    }

    /** The offset of the {@code }} that closes the {@code {} at {@code open}, or -1. */
    private int matchingBrace(int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"' || c == '\'') {
                i = stringEnd(text, i);
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Copies whitespace from {@code i} to the output; returns the offset after it. */
    private int copyWhitespace(int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            out.append(text.charAt(i));
            i++;
        }
        return i;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    // ---------------------------------------------------------------------------------------------
    // Step 3 and problems
    // ---------------------------------------------------------------------------------------------

    /** semantics.md §10.4, output: every line trimmed, empty lines dropped, lines joined with LF. */
    static String tidy(String css) {
        List<String> lines = new ArrayList<>();
        for (String line : css.split("\r\n|\r|\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return String.join("\n", lines);
    }

    /** A problem at one character of {@link #text}. */
    private void problemAt(String message, int textOffset) {
        addProblem(message, toOriginal(textOffset), toOriginal(textOffset) + 1);
    }

    /** A problem at {@code [start, end)} of {@link #text} (of the original text, during step 1). */
    private void problem(String message, int textStart, int textEnd) {
        addProblem(message, toOriginal(textStart), toOriginal(Math.max(textStart, textEnd - 1)) + 1);
    }

    /** Records a problem at {@code [start, end)} of the original text, unless it is already recorded. */
    private void addProblem(String message, int start, int end) {
        problems.putIfAbsent(start + ":" + message, new Problem(message, start, end));
    }

    /** Maps an offset in {@link #text} to the original content. */
    private int toOriginal(int textOffset) {
        if (originalOffset == null) {
            return textOffset; // still in step 1, where offsets are original
        }
        if (textOffset < originalOffset.length) {
            return originalOffset[textOffset];
        }
        return original.length();
    }
}
