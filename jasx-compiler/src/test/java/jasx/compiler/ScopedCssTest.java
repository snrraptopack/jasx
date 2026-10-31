package jasx.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** semantics.md §10.4: how the CSS of a style block is scoped. The scope name is {@code jx-1} throughout. */
class ScopedCssTest {

    /** The scoped CSS of {@code css}, which must have no problems. */
    private static String scoped(String css) {
        ScopedCss.Result result = ScopedCss.scope(css, "jx-1");
        assertEquals(List.of(), result.problems(), () -> "problems in: " + css);
        return result.css();
    }

    /** The problems of {@code css}, as "message@start-end". */
    private static List<String> problems(String css) {
        return ScopedCss.scope(css, "jx-1").problems().stream()
                .map(p -> p.message() + "@" + p.start() + "-" + p.end())
                .toList();
    }

    @Nested
    class Selectors {
        /** The examples of the table in semantics.md §10.4, and more. */
        @ParameterizedTest(name = "{0}  →  {1}")
        @CsvSource(delimiter = '|', value = {
                ".card                  | .card.jx-1",
                "ul > li:hover          | ul > li:hover.jx-1",
                ".title::before         | .title.jx-1::before",
                "a, b:not(.x, .y)       | a.jx-1, b:not(.x, .y).jx-1",
                ":global(body)          | body",
                ":global(.a .b)         | .a .b",
                ".dark .card            | .dark .card.jx-1",
                "p:first-line           | p.jx-1:first-line",
                "p:FIRST-LETTER         | p.jx-1:FIRST-LETTER",
                "a:hover::after         | a:hover.jx-1::after",
                "h1+p                   | h1+p.jx-1",
                "h1~p                   | h1~p.jx-1",
                "*                      | *.jx-1",
                "input[type=\"a b\"]    | input[type=\"a b\"].jx-1",
                "[title=\"x, y\"]       | [title=\"x, y\"].jx-1",
                ".a\\:b                 | .a\\:b.jx-1",
                "li:nth-child(2n + 1)   | li:nth-child(2n + 1).jx-1",
                ":global(a) b           | :global(a) b.jx-1",
        })
        void areScopedOnTheirLastCompound(String selector, String expected) {
            assertEquals(expected + " { }", scoped(selector + " { }"));
        }

        @Test
        void selectorListsAreJoinedWithCommaAndSpace() {
            assertEquals("a.jx-1, b.jx-1 { }", scoped("a,b { }"));
            assertEquals("a.jx-1, b.jx-1 { }", scoped("a ,\n  b { }"));
        }
    }

    @Nested
    class Rules {
        @Test
        void declarationsAreCopiedUnchanged() {
            assertEquals(".a.jx-1 { color: red; background: url(\"x;y\") }",
                    scoped(".a { color: red; background: url(\"x;y\") }"));
        }

        @Test
        void groupingAtRulesAreScopedInside() {
            assertEquals("@media (min-width: 1px) {\n.a.jx-1 { }\n}",
                    scoped("@media (min-width: 1px) {\n  .a { }\n}"));
            assertEquals("@supports (display: grid) { @layer base { b.jx-1 { } } }",
                    scoped("@supports (display: grid) { @layer base { b { } } }"));
        }

        @Test
        void otherAtRulesAreCopiedUnchanged() {
            String keyframes = "@keyframes spin { from { rotate: 0 } to { rotate: 1turn } }";
            assertEquals(keyframes, scoped(keyframes));
            String fontFace = "@font-face { font-family: X; src: url(x.woff) }";
            assertEquals(fontFace, scoped(fontFace));
            assertEquals("@import url(\"a.css\");\na.jx-1 { }", scoped("@import url(\"a.css\");\na { }"));
            assertEquals("@layer a, b;", scoped("@layer a, b;"));
        }

        @Test
        void nestedRulesAreScopedToo() {
            assertEquals(".card.jx-1 { color: red; &:hover.jx-1 { color: blue } .title.jx-1 { } }",
                    scoped(".card { color: red; &:hover { color: blue } .title { } }"));
        }

        @Test
        void groupingAtRulesInsideARuleHoldDeclarations() {
            assertEquals(".a.jx-1 { @media print { color: black; .b.jx-1 { } } }",
                    scoped(".a { @media print { color: black; .b { } } }"));
        }
    }

    @Nested
    class Output {
        @Test
        void commentsAreRemoved() {
            // A removed comment leaves the whitespace around it.
            assertEquals(".a.jx-1  { color: red }", scoped("/* lead */ .a /* in */ { color: /* x */red }"));
        }

        @Test
        void commentMarkersInStringsAreKept() {
            assertEquals("a.jx-1 { content: \"/* not a comment */\" }", scoped("a { content: \"/* not a comment */\" }"));
        }

        @Test
        void linesAreTrimmedAndEmptyLinesDropped() {
            assertEquals(".a.jx-1 {\ncolor: red;\n}\n.b.jx-1 { }",
                    scoped("\n    .a {\n      color: red;\n    }\n\n\n    .b { }\n  "));
        }

        @Test
        void bracesInStringsAreNotBlocks() {
            assertEquals("a.jx-1 { content: \"}\" }\nb.jx-1 { content: '{' }",
                    scoped("a { content: \"}\" }\nb { content: '{' }"));
        }

        @Test
        void anEmptyStyleBlockIsEmptyCss() {
            assertEquals("", scoped("\n   \n"));
        }
    }

    @Nested
    class Problems {
        @Test
        void unclosedComment() {
            assertEquals(List.of("this comment is not closed@4-6"), problems("a{} /* x"));
        }

        @Test
        void unclosedString() {
            assertEquals(List.of("this string is not closed@13-14"), problems("a { content: \"x\n}"));
        }

        @Test
        void unclosedBlock() {
            assertEquals(List.of("this block is not closed@2-3"), problems("a { color: red"));
            assertEquals(List.of("this block is not closed@7-8"), problems("@media { a { } "));
        }

        @Test
        void braceThatClosesNothing() {
            assertEquals(List.of("this '}' closes nothing@4-5"), problems("a{} }"));
        }

        @Test
        void braceInsideParentheses() {
            assertEquals(List.of("a brace can't be inside parentheses or brackets@9-10"), problems("a:not(.x { }"));
        }

        @Test
        void unclosedParenthesis() {
            assertEquals(List.of("this parenthesis is not closed@5-6", "expected a '{' after this selector@0-8"),
                    problems("a:not(.x"));
        }

        @Test
        void emptySelectors() {
            assertEquals(List.of("this selector is empty@0-1"), problems("{ color: red }"));
            assertEquals(List.of("this selector is empty@2-3"), problems("a,,b { }"));
        }

        @Test
        void aDeclarationOutsideARule() {
            assertEquals(List.of("expected a '{' after this selector@0-10"), problems("color: red; a { }"));
        }

        @Test
        void closingStyleTag() {
            assertEquals(List.of("a style block must not contain \"</style\"@14-21"),
                    problems("a { content: \"</STYLE>\" }"));
        }

        @Test
        void problemsAfterACommentPointAtTheOriginalText() {
            // The comment is removed before the structure is read; the offset must still be in the original.
            assertEquals(List.of("this '}' closes nothing@14-15"), problems("a{} /* xxxx */}"));
        }
    }
}
