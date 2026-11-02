package jasx.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads what the compiler needs from JavaScript (semantics.md §11.1, §11.4): the top-level names a file's script
 * declares, and the refs ({@code $name}) inline code uses.
 *
 * <p>It is not a JavaScript parser, and must stay one that the spec can describe exactly, as {@link ScopedCss} is
 * for CSS. It splits the text into identifiers and punctuation, skipping comments, string literals, template
 * literals (with their {@code ${...}} substitutions) and regular expression literals, and tracks the depth of
 * {@code ( [ {}. Names are read only at depth 0.
 */
final class ScriptReader {
    private ScriptReader() {}

    /** A significant token: an identifier or keyword, or one punctuation character. */
    record Token(String text, int start, int depth, boolean newlineBefore) {
        boolean isIdentifier() {
            return Character.isJavaIdentifierStart(text.charAt(0));
        }

        boolean is(String s) {
            return text.equals(s);
        }
    }

    /**
     * The top-level names of a script, each with whether the script exports it already (§11.1): imports,
     * function and class declarations, and {@code const}/{@code let}/{@code var} names, at the start of a
     * statement, outside every bracket.
     */
    static Map<String, Boolean> topLevelNames(String script) {
        List<Token> tokens = tokens(script);
        Map<String, Boolean> names = new LinkedHashMap<>();
        Set<String> exportedByList = new LinkedHashSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.depth() != 0 || !atStatementStart(tokens, i)) {
                continue;
            }
            boolean exported = false;
            int j = i;
            if (tokens.get(j).is("export")) {
                exported = true;
                j++;
                if (j < tokens.size() && tokens.get(j).is("{")) {
                    exportedByList.addAll(exportList(tokens, j));
                    continue;
                }
                if (j < tokens.size() && tokens.get(j).is("default")) {
                    j++;
                }
            }
            if (j >= tokens.size()) {
                break;
            }
            Token keyword = tokens.get(j);
            if (keyword.is("import") && !exported) {
                for (String name : importNames(tokens, j)) {
                    names.put(name, false);
                }
            } else if (keyword.is("async") || keyword.is("function")) {
                String name = functionName(tokens, j);
                if (name != null) {
                    names.put(name, exported);
                }
            } else if (keyword.is("class")) {
                String name = nameAt(tokens, j + 1);
                if (name != null) {
                    names.put(name, exported);
                }
            } else if (keyword.is("const") || keyword.is("let") || keyword.is("var")) {
                for (String name : variableNames(tokens, j)) {
                    names.put(name, exported);
                }
            }
        }
        for (String name : exportedByList) {
            names.computeIfPresent(name, (n, wasExported) -> true);
        }
        return names;
    }

    /** A ref used by inline code: its name, and where its {@code $} is in the code. */
    record RefUse(String name, int offset) {}

    /** The refs inline code uses (§11.4): identifiers that start with {@code $}, outside strings and comments. */
    static List<RefUse> refs(String code) {
        List<RefUse> refs = new ArrayList<>();
        for (Token t : tokens(code)) {
            boolean isRef = t.text().length() > 1 && t.text().charAt(0) == '$'
                    && Character.isJavaIdentifierStart(t.text().charAt(1));
            if (isRef) {
                refs.add(new RefUse(t.text().substring(1), t.start()));
            }
        }
        return refs;
    }

    // ---------------------------------------------------------------------------------------------
    // Top-level declarations
    // ---------------------------------------------------------------------------------------------

    /** Punctuation after which a line break doesn't end a statement: the expression goes on. */
    private static final Set<String> CONTINUES_EXPRESSION = Set.of(
            "=", "(", ",", ":", "?", "[", "+", "-", "*", "/", "%", "&", "|", "^", "!", "~", "<", ">", ".");

    /** Whether the token at {@code i} starts a statement: first, after {@code ;} or {@code }}, or on a new line. */
    private static boolean atStatementStart(List<Token> tokens, int i) {
        if (i == 0) {
            return true;
        }
        Token previous = tokens.get(i - 1);
        if (previous.is(";") || previous.is("}")) {
            return true;
        }
        return tokens.get(i).newlineBefore() && !CONTINUES_EXPRESSION.contains(previous.text());
    }

    /** {@code import d, { a, b as c }, * as ns from "m"}: the names it binds. */
    private static List<String> importNames(List<Token> tokens, int importAt) {
        List<String> names = new ArrayList<>();
        for (int i = importAt + 1; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.is("from") || t.is(";") || (t.depth() == 0 && t.newlineBefore() && i > importAt + 1
                    && !tokens.get(i - 1).is(",") && !tokens.get(i - 1).is("{"))) {
                break;
            }
            if (t.is("*")) {
                String name = tokens.size() > i + 2 && tokens.get(i + 1).is("as") ? nameAt(tokens, i + 2) : null;
                if (name != null) {
                    names.add(name);
                }
                i += 2;
            } else if (t.is("{")) {
                i = namedImports(tokens, i, names);
            } else if (t.isIdentifier() && t.depth() == 0) {
                names.add(t.text()); // the default import
            }
        }
        return names;
    }

    /** {@code { a, b as c }}: adds {@code a} and {@code c}; returns the index of the closing brace. */
    private static int namedImports(List<Token> tokens, int open, List<String> names) {
        int i = open + 1;
        while (i < tokens.size() && !tokens.get(i).is("}")) {
            Token t = tokens.get(i);
            if (t.isIdentifier()) {
                boolean renamed = i + 2 < tokens.size() && tokens.get(i + 1).is("as");
                String name = renamed ? nameAt(tokens, i + 2) : t.text();
                if (name != null) {
                    names.add(name);
                }
                i += renamed ? 3 : 1;
            } else {
                i++;
            }
        }
        return i;
    }

    /** {@code export { a, b as c }}: the local names it exports ({@code a}, {@code b}). */
    private static List<String> exportList(List<Token> tokens, int open) {
        List<String> names = new ArrayList<>();
        int i = open + 1;
        while (i < tokens.size() && !tokens.get(i).is("}")) {
            Token t = tokens.get(i);
            if (t.isIdentifier()) {
                names.add(t.text());
                i += i + 1 < tokens.size() && tokens.get(i + 1).is("as") ? 3 : 1;
            } else {
                i++;
            }
        }
        return names;
    }

    /** {@code async function* f}, {@code function f}: {@code f}, or null. */
    private static String functionName(List<Token> tokens, int at) {
        int i = at;
        if (tokens.get(i).is("async")) {
            i++;
        }
        if (i >= tokens.size() || !tokens.get(i).is("function")) {
            return null;
        }
        i++;
        if (i < tokens.size() && tokens.get(i).is("*")) {
            i++;
        }
        return nameAt(tokens, i);
    }

    /**
     * {@code const x = 1, y = 2}: {@code x} and {@code y}. A destructuring pattern ({@code const { a } = o}) binds
     * nothing that is read. The list ends at a {@code ;} or at a new statement.
     */
    private static List<String> variableNames(List<Token> tokens, int at) {
        List<String> names = new ArrayList<>();
        String first = nameAt(tokens, at + 1);
        if (first == null) {
            return names;
        }
        names.add(first);
        for (int i = at + 2; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.depth() == 0 && (t.is(";") || atStatementStart(tokens, i))) {
                break;
            }
            boolean nextDeclarator = t.depth() == 0 && t.is(",") && i + 1 < tokens.size() && tokens.get(i + 1).isIdentifier();
            if (nextDeclarator) {
                names.add(tokens.get(i + 1).text());
            }
        }
        return names;
    }

    /** The identifier at {@code i}, or null. */
    private static String nameAt(List<Token> tokens, int i) {
        return i < tokens.size() && tokens.get(i).isIdentifier() ? tokens.get(i).text() : null;
    }

    // ---------------------------------------------------------------------------------------------
    // Tokens
    // ---------------------------------------------------------------------------------------------

    /** Words after which a {@code /} starts a regular expression, not a division. */
    private static final Set<String> REGEX_AFTER_WORD = Set.of("return", "typeof", "case", "do", "else", "in");

    /** Punctuation after which a {@code /} starts a regular expression (§11.1). */
    private static final String REGEX_AFTER_PUNCTUATION = "(,=:[!&|?{};+-*%<>~^";

    /** The significant tokens of {@code text}: identifiers and punctuation, with their bracket depth. */
    static List<Token> tokens(String text) {
        List<Token> tokens = new ArrayList<>();
        int depth = 0;
        boolean newline = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                newline = true;
                i++;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                i = end < 0 ? text.length() : end;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                newline |= end > 0 && text.substring(i, end).contains("\n");
                i = end < 0 ? text.length() : end + 2;
            } else if (c == '\'' || c == '"') {
                tokens.add(new Token("\"", i, depth, newline)); // a string stands as one token
                i = skipString(text, i, c);
                newline = false;
            } else if (c == '`') {
                tokens.add(new Token("`", i, depth, newline));
                i = skipTemplate(text, i);
                newline = false;
            } else if (c == '/' && startsRegex(tokens)) {
                tokens.add(new Token("/regex/", i, depth, newline));
                i = skipRegex(text, i);
                newline = false;
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(text.substring(start, i), start, depth, newline));
                newline = false;
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token("0", start, depth, newline));
                newline = false;
            } else {
                if (c == ')' || c == ']' || c == '}') {
                    depth = Math.max(0, depth - 1);
                }
                tokens.add(new Token(String.valueOf(c), i, depth, newline));
                newline = false;
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                }
                i++;
            }
        }
        return tokens;
    }

    private static boolean startsRegex(List<Token> tokens) {
        if (tokens.isEmpty()) {
            return true;
        }
        String previous = tokens.get(tokens.size() - 1).text();
        boolean punctuation = previous.length() == 1 && REGEX_AFTER_PUNCTUATION.indexOf(previous.charAt(0)) >= 0;
        return punctuation || REGEX_AFTER_WORD.contains(previous);
    }

    /** The index after the string literal that starts at {@code start} with {@code quote}. */
    private static int skipString(String text, int start, char quote) {
        int i = start + 1;
        while (i < text.length() && text.charAt(i) != quote && text.charAt(i) != '\n') {
            i += text.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(text.length(), i + 1);
    }

    /** The index after the template literal that starts at {@code start}, its substitutions included. */
    private static int skipTemplate(String text, int start) {
        int i = start + 1;
        while (i < text.length() && text.charAt(i) != '`') {
            if (text.charAt(i) == '\\') {
                i += 2;
            } else if (text.startsWith("${", i)) {
                i = skipSubstitution(text, i + 2);
            } else {
                i++;
            }
        }
        return Math.min(text.length(), i + 1);
    }

    /** The index after the {@code }} that closes a substitution whose content starts at {@code start}. */
    private static int skipSubstitution(String text, int start) {
        int depth = 0;
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipString(text, i, c);
            } else if (c == '`') {
                i = skipTemplate(text, i);
            } else if (c == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                if (depth == 0) {
                    return i + 1;
                }
                depth--;
                i++;
            } else {
                i++;
            }
        }
        return text.length();
    }

    /** The index after the regular expression literal (and its flags) that starts at {@code start}. */
    private static int skipRegex(String text, int start) {
        int i = start + 1;
        boolean inClass = false;
        while (i < text.length() && text.charAt(i) != '\n') {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '/' && !inClass) {
                i++;
                break;
            }
            i++;
        }
        while (i < text.length() && Character.isLetter(text.charAt(i))) {
            i++;
        }
        return Math.min(text.length(), i);
    }
}
