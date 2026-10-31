package jasx.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import jasx.parser.ast.Ast.Child;
import jasx.parser.ast.Ast.JasxAttribute;
import jasx.parser.ast.Ast.JasxElement;
import jasx.parser.ast.Ast.JasxEmptyExpression;
import jasx.parser.ast.Ast.JasxExpressionContainer;
import jasx.parser.ast.Ast.JasxFragment;
import jasx.parser.ast.Ast.JasxStringLiteral;
import jasx.parser.ast.Ast.JasxText;
import jasx.parser.ast.Ast.JavaCode;
import jasx.parser.ast.Ast.JavaExpression;
import jasx.parser.ast.Ast.Markup;
import jasx.parser.ast.Ast.Node;
import jasx.parser.ast.Ast.Segment;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JasxParserTest {

    /** A Unicode escape for {@code hex}, built at run time so no escape appears in this source file. */
    private static String esc(String hex) {
        return "\\" + "u" + hex;
    }

    private static ParseResult parse(String source) {
        ParseResult result = JasxParser.parse(source, "Test.jasx");
        assertCovers(source, result.unit().start(), result.unit().end(), result.unit().segments());
        return result;
    }

    private static ParseResult parseClean(String source) {
        ParseResult result = parse(source);
        assertEquals(List.of(), result.diagnostics(), "diagnostics for: " + source);
        return result;
    }

    /** The source text of every top-level markup segment. */
    private static List<String> markup(String source) {
        List<String> found = new ArrayList<>();
        for (Segment s : parseClean(source).unit().segments()) {
            if (s instanceof Markup) {
                found.add(source.substring(s.start(), s.end()));
            }
        }
        return found;
    }

    private static List<String> codes(String source) {
        return parse(source).diagnostics().stream().map(Diagnostic::code).toList();
    }

    private static Diagnostic onlyDiagnostic(String source) {
        List<Diagnostic> diagnostics = parse(source).diagnostics();
        assertEquals(1, diagnostics.size(), () -> "diagnostics: " + diagnostics);
        return diagnostics.get(0);
    }

    private static JasxElement firstElement(String source) {
        return (JasxElement) parseClean(source).unit().segments().stream()
                .filter(s -> s instanceof JasxElement).findFirst().orElseThrow();
    }

    /** Segments cover [start, end) in order with no gaps (ast.md §4, §5), recursively. */
    private static void assertCovers(String source, int start, int end, List<Segment> segments) {
        int at = start;
        for (Segment s : segments) {
            assertEquals(at, s.start(), "gap or overlap before segment at " + s.start());
            if (s instanceof JavaCode code) {
                assertEquals(source.substring(s.start(), s.end()), code.source());
            }
            visitExpressions(source, s);
            at = s.end();
        }
        if (!segments.isEmpty() || start != end) {
            assertEquals(end, at, "segments do not reach the end");
        }
    }

    private static void visitExpressions(String source, Node node) {
        switch (node) {
            case JasxElement e -> {
                e.openingElement().attributes().forEach(a -> visitExpressions(source, a));
                e.children().forEach(c -> visitExpressions(source, c));
            }
            case JasxFragment f -> f.children().forEach(c -> visitExpressions(source, c));
            case JasxAttribute a when a.value() != null -> visitExpressions(source, a.value());
            case JasxExpressionContainer c when c.expression() instanceof JavaExpression e ->
                    assertCovers(source, e.start(), e.end(), e.segments());
            default -> {}
        }
    }

    @Nested
    class MarkupDetection {
        @Test
        void relationalOperatorIsJava() {
            assertEquals(List.of("<a/>"), markup("boolean b = x < y; Element e = <a/>;"));
        }

        @Test
        void genericMethodAtStartOfTypeBodyIsJava() {
            assertEquals(List.of(), markup("class A { <T> void m(T t) {} }"));
        }

        @Test
        void explicitGenericConstructorInvocationIsJava() {
            assertEquals(List.of(), markup("class A { <T> A(T t) {} A() { <String>this(\"x\"); } }"));
        }

        @Test
        void annotationArrayValueIsNotAnArrayInitializerBrace() {
            assertEquals(List.of(), markup("@A({<T>x}) class B {}"));
        }

        @Test
        void arrayInitializerAfterBracket() {
            assertEquals(List.of("<a/>", "<b/>"), markup("var xs = new Element[] { <a/>, <b/> };"));
        }

        @Test
        void nestedArrayInitializers() {
            assertEquals(List.of("<a/>", "<b/>"), markup("Element[][] g = {{<a/>}, {<b/>}};"));
        }

        @Test
        void methodReferenceTypeArgumentsAreJava() {
            assertEquals(List.of(), markup("var of = List::<String>of;"));
        }

        @Test
        void lambdaExpressionBody() {
            assertEquals(List.of("<li/>"), markup("Function<T, Element> f = t -> <li/>;"));
        }

        @Test
        void returnInLambdaBlockBody() {
            assertEquals(List.of("<li/>"), markup("Function<T, Element> f = t -> { return <li/>; };"));
        }

        @Test
        void switchRuleArm() {
            assertEquals(List.of("<p/>", "<br/>"), markup("Element e = switch (k) { case 0 -> <p/>; default -> <br/>; };"));
        }

        @Test
        void allIntroducingOperators() {
            String source = "x = <a/>; x += <a/>; f(<a/>, <a/>); a[<a/>]; c ? <a/> : <a/>; p && <a/>; p || <a/>; x >>>= <a/>;";
            assertEquals(10, markup(source).size());
        }

        @Test
        void markupNotIntroducedAtStartOfFile() {
            assertEquals(List.of(), markup("<T> void m() {}"));
        }

        @Test
        void commentsAreNotSignificantTokens() {
            assertEquals(List.of("<a/>"), markup("Element e = /* c */ // d\n <a/>;"));
        }

        @Test
        void supplementaryIdentifierBeforeLessThanIsJava() {
            assertEquals(List.of(), markup("boolean b = \uD835\uDC65 < 3;"));
        }
    }

    @Nested
    class Yield {
        @Test
        void yieldAfterReturnIsAnIdentifier() {
            assertEquals(List.of(), markup("boolean f(int yield) { return yield < 2; }"));
        }

        @Test
        void yieldInForHeaderIsAnIdentifier() {
            assertEquals(List.of(), markup("void f(int yield) { for (; yield < 3; ) {} }"));
        }

        @Test
        void yieldAfterLambdaArrowIsAnIdentifier() {
            assertEquals(List.of(), markup("IntPredicate p = yield -> yield < 2;"));
        }

        @Test
        void yieldAsStatementAfterBlockBrace() {
            assertEquals(List.of("<br/>"), markup("Element e = switch (k) { default -> { yield <br/>; } };"));
        }

        @Test
        void yieldAsStatementAfterCaseLabel() {
            assertEquals(List.of("<p/>"), markup("Element e = switch (k) { case 0: yield <p/>; default: yield null; };"));
        }

        @Test
        void yieldAsStatementAfterIfCondition() {
            assertEquals(List.of("<p/>"), markup("Element e = switch (k) { default -> { if (c) yield <p/>; yield null; } };"));
        }
    }

    @Nested
    class JavaModeScanning {
        @Test
        void unicodeEscapeClosesExpressionContainer() {
            String source = "Element e = <p>{x" + esc("007D") + "</p>;";
            JasxElement p = firstElement(source);
            JasxExpressionContainer container = (JasxExpressionContainer) p.children().get(0);
            assertEquals("{x" + esc("007D"), source.substring(container.start(), container.end()));
        }

        @Test
        void unicodeEscapeOpensStringLiteral() {
            // The escaped quote starts a string, so the brace inside it does not close the container.
            String q = esc("0022");
            String source = "Element e = <p>{" + q + "}" + q + "}</p>;";
            JasxElement p = firstElement(source);
            assertEquals(1, p.children().size());
        }

        @Test
        void escapedBackslashDoesNotStartUnicodeEscape() {
            // A backslash preceded by an odd number of backslashes does not start an escape, so the
            // container is closed by the real brace, not by the would-be escape.
            String source = "Element e = <p>{x\\" + esc("007D") + "}</p>;";
            JasxElement p = firstElement(source);
            JasxExpressionContainer container = (JasxExpressionContainer) p.children().get(0);
            assertEquals("{x\\" + esc("007D") + "}", source.substring(container.start(), container.end()));
        }

        @Test
        void bracesInCommentsStringsCharsAndTextBlocks() {
            String source = """
                    Element e = <p>{f("}", '}', '"', /* } */ \"""
                        } { "
                        \""", // }
                      x)}</p>;
                    """;
            JasxElement p = firstElement(source);
            assertEquals(1, p.children().size());
            assertInstanceOf(JavaExpression.class, ((JasxExpressionContainer) p.children().get(0)).expression());
        }

        @Test
        void commentOnlyContainerIsEmptyExpression() {
            JasxElement p = firstElement("Element e = <p>{ /* note */ }</p>;");
            JasxExpressionContainer c = (JasxExpressionContainer) p.children().get(0);
            JasxEmptyExpression empty = assertInstanceOf(JasxEmptyExpression.class, c.expression());
            assertEquals(" /* note */ ", empty.source());
        }

        @Test
        void markupNestedInExpressionInsideMarkup() {
            JasxElement ul = firstElement("Element e = <ul>{xs.map(x -> <li>{x.map(y -> <b>{y}</b>)}</li>)}</ul>;");
            JavaExpression outer = (JavaExpression) ((JasxExpressionContainer) ul.children().get(0)).expression();
            assertEquals(3, outer.segments().size());
            assertInstanceOf(JasxElement.class, outer.segments().get(1));
        }
    }

    @Nested
    class Text {
        private String text(String source) {
            return ((JasxText) firstElement(source).children().get(0)).value();
        }

        @Test
        void characterReferencesAreDecoded() {
            assertEquals("& < A B \u00A0 \u00A9", text("Element e = <p>&amp; &lt; &#65; &#x42; &nbsp; &copy;</p>;"));
        }

        @Test
        void supplementaryNumericReference() {
            assertEquals("\uD83D\uDE00", text("Element e = <p>&#x1F600;</p>;"));
        }

        @Test
        void ampersandWithoutReferenceIsLiteral() {
            assertEquals("a & b &copy &#; &#x;", text("Element e = <p>a & b &copy &#; &#x;</p>;"));
        }

        @Test
        void invalidCodePointBecomesReplacementCharacter() {
            assertEquals("\uFFFD\uFFFD", text("Element e = <p>&#0;&#xD800;</p>;"));
        }

        @Test
        void rawPreservesSourceAndWhitespaceIsNotNormalized() {
            JasxText t = (JasxText) firstElement("Element e = <p>\n  a &amp; b\n</p>;").children().get(0);
            assertEquals("\n  a &amp; b\n", t.raw());
            assertEquals("\n  a & b\n", t.value());
        }

        @Test
        void unknownNamedReferenceIsAnError() {
            Diagnostic d = onlyDiagnostic("Element e = <p>&bogus;</p>;");
            assertEquals("JASX1010", d.code());
            assertEquals(15, d.start());
            assertEquals(22, d.end());
        }

        @ParameterizedTest
        @ValueSource(strings = {"}", ">"})
        void braceOrGreaterThanInTextIsAnError(String c) {
            Diagnostic d = onlyDiagnostic("Element e = <p>a " + c + " b</p>;");
            assertEquals("JASX1009", d.code());
            assertEquals(17, d.start());
        }
    }

    @Nested
    class Attributes {
        private JasxAttribute attribute(String source) {
            return firstElement(source).openingElement().attributes().get(0);
        }

        @Test
        void shorthandAttributeHasNullValue() {
            assertNull(attribute("Element e = <input disabled />;").value());
        }

        @Test
        void whitespaceAroundEquals() {
            JasxAttribute a = attribute("Element e = <div   class = \"x\"  >y</div>;");
            assertEquals("x", ((JasxStringLiteral) a.value()).value());
        }

        @Test
        void javaEscapesInStringLiterals() {
            JasxStringLiteral s = (JasxStringLiteral) attribute("Element e = <a t=\"x\\n\\t\\\"\\\\\\101\\s\" />;").value();
            assertEquals("x\n\t\"\\A ", s.value());
        }

        @Test
        void unicodeEscapesAreNotProcessedInMarkup() {
            String source = "Element e = <a t=\"" + esc("0041") + "\" />;";
            JasxStringLiteral s = (JasxStringLiteral) attribute(source).value();
            assertEquals(esc("0041"), s.value());
        }

        @Test
        void characterReferencesAreNotDecodedInAttributeStrings() {
            JasxStringLiteral s = (JasxStringLiteral) attribute("Element e = <a t=\"&amp;\" />;").value();
            assertEquals("&amp;", s.value());
        }

        @Test
        void markupAsAttributeValue() {
            JasxAttribute a = attribute("Element e = <Card icon=<Icon /> footer=<></> />;");
            assertInstanceOf(JasxElement.class, a.value());
        }

        @Test
        void duplicateNamespacedAttribute() {
            Diagnostic d = onlyDiagnostic("Element e = <a x:y=\"1\" x:y=\"2\" />;");
            assertEquals("JASX1004", d.code());
        }

        @Test
        void sameLocalNameInDifferentNamespacesIsNotDuplicate() {
            parseClean("Element e = <a x:y=\"1\" z:y=\"2\" y=\"3\" />;");
        }

        @Test
        void invalidEscapeIsAnError() {
            assertEquals(List.of("JASX1005"), codes("Element e = <a t=\"\\q\" />;"));
        }

        @Test
        void unterminatedStringIsAnError() {
            assertTrue(codes("Element e = <a t=\"abc\n />;").contains("JASX1005"));
        }

        @Test
        void singleQuotedValueIsAnError() {
            assertEquals(List.of("JASX1005"), codes("Element e = <a t='x' />;"));
        }

        @Test
        void missingValueIsAnError() {
            Diagnostic d = onlyDiagnostic("Element e = <a t=>x</a>;");
            assertEquals("JASX1005", d.code());
            assertEquals(17, d.start());
            assertEquals(18, d.end());
        }

        @Test
        void expressionInAttributePositionIsAnError() {
            assertEquals(List.of("JASX1006"), codes("Element e = <a {x} />;"));
        }
    }

    @Nested
    class Errors {
        @Test
        void unclosedElementReportsOpeningElement() {
            Diagnostic d = onlyDiagnostic("Element e = <div><p>Hi</p>;");
            assertEquals("JASX1002", d.code());
            assertEquals(12, d.start());
            assertEquals(17, d.end());
        }

        @Test
        void unclosedFragment() {
            Diagnostic d = onlyDiagnostic("Element e = <>x;");
            assertEquals("JASX1002", d.code());
            assertEquals(12, d.start());
            assertEquals(14, d.end());
        }

        @Test
        void endOfInputInsideTag() {
            assertEquals(List.of("JASX1002"), codes("Element e = <div class=\"a\""));
        }

        @Test
        void onlyInnermostEndOfInputIsReported() {
            assertEquals(List.of("JASX1003"), codes("Element e = <a><b>{x"));
        }

        @Test
        void unexpectedTokenInTag() {
            Diagnostic d = onlyDiagnostic("Element e = <div # />;");
            assertEquals("JASX1006", d.code());
            assertEquals(17, d.start());
            assertEquals(18, d.end());
        }

        @Test
        void identifierEndingWithDash() {
            Diagnostic d = onlyDiagnostic("Element e = <my-widget- />;");
            assertEquals("JASX1011", d.code());
            assertEquals(13, d.start());
            assertEquals(23, d.end());
        }

        @Test
        void qualifiedNameWithDash() {
            assertEquals(List.of("JASX1011"), codes("Element e = <ui.my-card />;"));
        }

        @Test
        void mismatchedQualifiedNames() {
            Diagnostic d = onlyDiagnostic("Element e = <a.b></a.c>;");
            assertEquals("JASX1001", d.code());
            assertEquals(1, d.related().size());
        }

        @Test
        void fragmentClosedByNamedTag() {
            assertEquals(List.of("JASX1001"), codes("Element e = <>x</div>;"));
        }

        @Test
        void elementClosedByFragmentTag() {
            assertEquals(List.of("JASX1001"), codes("Element e = <div>x</>;"));
        }

        @Test
        void recoveryReportsIndependentErrors() {
            assertEquals(List.of("JASX1004", "JASX1009", "JASX1001"),
                    codes("Element e = <p a=\"1\" a=\"2\">x > y</q>;"));
        }

        @Test
        void missingElementNameIsAnError() {
            assertEquals(List.of("JASX1006"), codes("int x = <3;"));
        }
    }

    @Nested
    class SourceShapes {
        @Test
        void emptyFileHasNoSegments() {
            assertEquals(List.of(), parseClean("").unit().segments());
        }

        @Test
        void fileWithoutMarkupIsOneJavaCodeSegment() {
            List<Segment> segments = parseClean("class A { int x = a < b ? 1 : 2; }\n").unit().segments();
            assertEquals(1, segments.size());
            assertInstanceOf(JavaCode.class, segments.get(0));
        }

        @Test
        void selfClosingAndEmptyElementsDiffer() {
            JasxElement self = firstElement("Element e = <div/>;");
            JasxElement empty = firstElement("Element e = <div></div>;");
            assertTrue(self.openingElement().selfClosing());
            assertNull(self.closingElement());
            assertTrue(!empty.openingElement().selfClosing());
            assertEquals(List.<Child>of(), empty.children());
        }

        @Test
        void crlfCountsAsOneLineTerminator() {
            String source = "Element e =\r\n  <p>\r\n</p>;\rx";
            JasxElement p = firstElement(source);
            LineMap lines = new LineMap(source);
            assertEquals(new LineMap.Position(2, 2), lines.position(p.start()));
            assertEquals(new LineMap.Position(3, 4), lines.position(p.end()));
            assertEquals(new LineMap.Position(4, 0), lines.position(source.length() - 1));
        }

        @Test
        void jsonEscapesControlCharacters() {
            String source = "Element e = <p>a\tb" + (char) 1 + "</p>;";
            String json = AstJson.toJson(parseClean(source).unit(), source);
            assertTrue(json.contains("\"a\\tb\\" + "u0001\""), json);
        }
    }
}
