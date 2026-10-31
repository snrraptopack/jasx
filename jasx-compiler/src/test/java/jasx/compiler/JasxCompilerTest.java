package jasx.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jasx.html.Html;
import jasx.parser.LineMap;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JasxCompilerTest {
    private static final Target HTML = Target.load("html", JasxCompilerTest.class.getClassLoader());
    private static final String CLASSPATH = System.getProperty("java.class.path");

    /** A .jasx file declaring class T with the given members. */
    private static SourceFile file(String members) {
        return new SourceFile("T.jasx", "import jasx.runtime.Element;\nimport java.util.*;\n\nclass T {\n" + members + "\n}\n");
    }

    private static CompilationResult compile(SourceFile... files) {
        return new JasxCompiler(HTML).compile(List.of(files));
    }

    private static List<String> codes(SourceFile... files) {
        return compile(files).diagnostics().stream().map(CompilerDiagnostic::code).toList();
    }

    private static String format(List<CompilerDiagnostic> diagnostics) {
        return diagnostics.stream().map(CompilerDiagnostic::format).collect(Collectors.joining("\n"));
    }

    /** Compiles everything, runs {@code className.Test()}, and renders the result with the html target. */
    private static String render(String className, List<SourceFile> jasx, List<SourceFile> java) {
        List<SourceFile> all = new ArrayList<>(jasx);
        all.addAll(java);
        CompilationResult result = new JasxCompiler(HTML).compile(all);
        assertFalse(result.hasErrors(), () -> format(result.diagnostics()));
        JavacRunner.Result compiled = JavacRunner.compile(result.files(), java, CLASSPATH);
        assertTrue(compiled.success(), () -> format(compiled.diagnostics()) + "\n"
                + result.files().stream().map(GeneratedFile::content).collect(Collectors.joining("\n")));
        try {
            Method test = compiled.classLoader(JasxCompilerTest.class.getClassLoader()).loadClass(className)
                    .getDeclaredMethod("Test");
            test.setAccessible(true);
            return Html.render(test.invoke(null));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static String render(String members) {
        return render("T", List.of(file(members)), List.of());
    }

    @Nested
    class Rendering {
        @Test
        void listsFromStreamsWithNestedMarkup() {
            assertEquals("<ul><li>a</li><li>b</li></ul>", render("""
                    static Element Test() {
                      var xs = List.of("a", "b");
                      return <ul>{xs.stream().map(x -> <li>{x}</li>).toList()}</ul>;
                    }
                    """));
        }

        @Test
        void attributesOfEveryKind() {
            assertEquals("<div class=\"c\" data-id=\"7\" aria-label=\"L\" hx-post=\"/x\" xlink:href=\"#a\" hidden title=\"&lt;b&gt;t&lt;/b&gt;\"></div>",
                    render("""
                    static Element Test() {
                      return <div class="c" data-id={7} aria-label="L" hx-post="/x" xlink:href="#a" inert={false} hidden title=<b>t</b>></div>;
                    }
                    """));
        }

        @Test
        void inlineEventHandlerStringsAreAllowed() {
            assertEquals("<button onclick=\"go()\">Go</button>", render("""
                    static Element Test() { return <button onclick="go()">Go</button>; }
                    """));
        }

        @Test
        void customElementsUseAttributesByName() {
            assertEquals("<my-card size=\"2\">x</my-card>", render("""
                    static Element Test() { return <my-card size={2}>x</my-card>; }
                    """));
        }

        @Test
        void textReferencesAndAttributeEscapes() {
            assertEquals("<p title=\"a&quot;b\\n\">x &amp; &lt;y&gt; ©</p>", render("""
                    static Element Test() { return <p title="a\\"b\\\\n">x &amp; &lt;y&gt; &copy;</p>; }
                    """));
        }

        @Test
        void keysAreAcceptedAndIgnoredByHtml() {
            assertEquals("<ul><li>a</li><li>b</li></ul>", render("""
                    static Element Item(String s) { return <li>{s}</li>; }
                    static Element Test() {
                      return <ul>{List.of("a", "b").stream().map(s -> <Item key={s} s={s} />).toList()}</ul>;
                    }
                    """));
        }

        @Test
        void optionalPropsGetNullOrPrimitiveDefaults() {
            // A null child renders as nothing (semantics.md §3.4).
            assertEquals("<b>x||0|false</b>", render("""
                    static Element Badge(String text, @jasx.Optional String color, @jasx.Optional int size,
                                         @jasx.Optional boolean big) {
                      return <b>{text}|{color}|{size}|{big}</b>;
                    }
                    static Element Test() { return <Badge text="x" />; }
                    """));
        }

        @Test
        void defaultPropsUseTheirLiterals() {
            assertEquals("<b>blue|3|-1|c|true|2.5</b>", render("""
                    static Element Badge(@jasx.Default("\\"blue\\"") String color, @jasx.Default("3") int n,
                                         @jasx.Default("-1") long neg, @jasx.Default("'c'") char ch,
                                         @jasx.Default("true") boolean flag, @jasx.Default(value = "2.5") double d) {
                      return <b>{color}|{n}|{neg}|{ch}|{flag}|{d}</b>;
                    }
                    static Element Test() { return <Badge />; }
                    """));
        }

        @Test
        void importedAnnotationsBySimpleName() {
            SourceFile f = new SourceFile("T.jasx", """
                    import jasx.runtime.Element;
                    import jasx.*;
                    class T {
                      static Element Badge(@Default("\\"d\\"") String a, @Optional String b) { return <b>{a}{b}</b>; }
                      static Element Test() { return <Badge />; }
                    }
                    """);
            assertEquals("<b>d</b>", render("T", List.of(f), List.of()));
        }

        @Test
        void argumentsAreEvaluatedInParameterOrder() {
            // The log list is a child, so it renders as its items: "a" was evaluated before "b".
            assertEquals("<i>a,b|ab</i>", render("""
                    static List<String> log = new ArrayList<>();
                    static String note(String s) { log.add(s); return s; }
                    static Element Pair(String a, String b) { return <i>{a},{b}|{log}</i>; }
                    static Element Test() { return <Pair b={note("b")} a={note("a")} />; }
                    """));
        }

        @Test
        void lambdaPropsAreTypedByTheParameter() {
            assertEquals("<p>HI</p>", render("""
                    static Element Shout(java.util.function.Function<String, String> f) { return <p>{f.apply("hi")}</p>; }
                    static Element Test() { return <Shout f={s -> s.toUpperCase()} />; }
                    """));
        }

        @Test
        void genericComponentsInferTypeArguments() {
            assertEquals("<ul><li>1</li><li>2</li></ul>", render("""
                    static <X> Element ListView(List<X> items, java.util.function.Function<X, Element> row) {
                      return <ul>{items.stream().map(row).toList()}</ul>;
                    }
                    static Element Test() { return <ListView items={List.of(1, 2)} row={n -> <li>{n}</li>} />; }
                    """));
        }

        @Test
        void singleChildParameter() {
            assertEquals("<div><b>m</b>t<i>e</i></div>", render("""
                    static Element Box(Element children) { return <>{children}</>; }
                    static Element Test() {
                      Element e = <i>e</i>;
                      return <div><Box><b>m</b></Box><Box>t</Box><Box>{e}</Box></div>;
                    }
                    """));
        }

        @Test
        void childListParameterWithMixedChildren() {
            assertEquals("<section>a<b>b</b>c3</section>", render("""
                    static Element Panel(java.util.List<jasx.runtime.Element> children) { return <section>{children}</section>; }
                    static Element Test() { return <Panel>a<b>b</b>{"c"}{3}{null}</Panel>; }
                    """));
        }

        @Test
        void emptyChildListParameter() {
            assertEquals("<section></section>", render("""
                    static Element Panel(List<Element> children) { return <section>{children}</section>; }
                    static Element Test() { return <Panel />; }
                    """));
        }

        @Test
        void qualifiedNameOfNestedClass() {
            assertEquals("<b>n</b>", render("""
                    static class Ui { static Element Tag(String s) { return <b>{s}</b>; } }
                    static Element Test() { return <Ui.Tag s="n" />; }
                    """));
        }

        @Test
        void componentsOfEnclosingClassFromNestedClass() {
            assertEquals("<b>x</b>", render("""
                    static Element Tag() { return <b>x</b>; }
                    static class Inner { static Element Use() { return <Tag />; } }
                    static Element Test() { return Inner.Use(); }
                    """));
        }

        @Test
        void componentsFromOtherFilesByStaticImportQualifiedAndFullyQualifiedNames() {
            SourceFile ui = new SourceFile("app/ui/Ui.jasx", """
                    package app.ui;
                    import jasx.runtime.Element;
                    public class Ui {
                      public static Element Card(String title) { return <h2>{title}</h2>; }
                    }
                    """);
            SourceFile page = new SourceFile("app/Page.jasx", """
                    package app;
                    import jasx.runtime.Element;
                    import app.ui.Ui;
                    import static app.ui.Ui.Card;
                    public class Page {
                      public static Element Test() { return <><Card title="a" /><Ui.Card title="b" /><app.ui.Ui.Card title="c" /></>; }
                    }
                    """);
            assertEquals("<h2>a</h2><h2>b</h2><h2>c</h2>", render("app.Page", List.of(ui, page), List.of()));
        }

        @Test
        void annotatedComponentsInJavaFiles() {
            SourceFile lib = new SourceFile("lib/Lib.java", """
                    package lib;
                    import jasx.Component;
                    import jasx.runtime.Element;
                    public class Lib {
                      @Component public static Element Tag(String text) { return jasx.html.Html.h("em", null, text); }
                      public static Element NotAComponent() { return null; }
                    }
                    """);
            SourceFile page = new SourceFile("app/Page.jasx", """
                    package app;
                    import jasx.runtime.Element;
                    import static lib.Lib.*;
                    public class Page { public static Element Test() { return <Tag text="j" />; } }
                    """);
            assertEquals("<em>j</em>", render("app.Page", List.of(page), List.of(lib)));
            assertEquals(List.of("JASX2001"), codes(lib, new SourceFile("app/Q.jasx", """
                    import static lib.Lib.*;
                    class Q { static jasx.runtime.Element Test() { return <NotAComponent />; } }
                    """)));
        }
    }

    @Nested
    class Generation {
        private String generated(String members) {
            CompilationResult r = compile(file(members));
            assertFalse(r.hasErrors(), () -> format(r.diagnostics()));
            return r.files().get(0).content();
        }

        @Test
        void linesArePreserved() {
            String source = file("""
                    static Element Card(String title, List<Element> children) {
                      return <section>
                        <h2>{title}</h2>
                        {children}
                      </section>;
                    }

                    static Element Test() {
                      int n = 3; // a comment
                      return (
                        <Card title="t">
                          <p>one {n}</p>
                          <p>two</p>
                        </Card>
                      );
                    }
                    """).content();
            String generated = generated(source.substring(source.indexOf("class T {\n") + 10, source.lastIndexOf("\n}\n")));
            String[] in = source.split("\n", -1);
            String[] out = generated.split("\n", -1);
            assertEquals(in.length, out.length, generated);
            for (int i = 0; i < in.length; i++) {
                if (!in[i].contains("<") && !in[i].contains(">") && !in[i].contains("{") && !in[i].isBlank()) {
                    assertEquals(in[i], out[i], "line " + (i + 1));
                }
            }
        }

        @Test
        void componentAnnotationIsInsertedOnTheSameLineOnce() {
            String g = generated("""
                    static Element A() { return <p/>; }
                    @jasx.Component static Element B() { return <p/>; }
                    static Element helper() { return <p/>; }
                    """);
            assertTrue(g.contains("@jasx.Component static Element A()"), g);
            assertFalse(g.contains("@jasx.Component @jasx.Component"), g);
            assertFalse(g.contains("@jasx.Component static Element helper"), g);
        }

        @Test
        void childrenAreSingleVariadicArgumentsAndNamesAreQualified() {
            String g = generated("static Element Test() { return <p>{null}</p>; }");
            assertTrue(g.contains("jasx.html.Html.h(\"p\", null, (java.lang.Object) (null))"), g);
        }

        @Test
        void javaCodeIsCopiedByteForByte() {
            String members = "static int x = 1 \\" + "u002B 2; /* <p/> */ static Element Test() { return <br/>; }";
            String g = generated(members);
            assertTrue(g.contains("static int x = 1 \\" + "u002B 2; /* <p/> */ "), g);
        }

        @Test
        void lowercaseHelpersAreNotComponentsAndMayBeOverloaded() {
            CompilationResult r = compile(file("""
                    static Element row(String s) { return <li>{s}</li>; }
                    static Element row(int n) { return <li>{n}</li>; }
                    static Element Test() { return <ul>{row("a")}{row(1)}</ul>; }
                    """));
            assertEquals(List.of(), r.diagnostics());
            assertEquals(1, r.index().all().size());
        }
    }

    @Nested
    class JavacDiagnostics {
        @Test
        void errorsInsideMarkupExpressionsMapToTheJasxSource() {
            SourceFile f = file("""
                    static Element Test() {
                      return <div>
                        <p>{undefinedName}</p>
                      </div>;
                    }
                    """);
            CompilationResult r = compile(f);
            JavacRunner.Result compiled = JavacRunner.compile(r.files(), List.of(), CLASSPATH);
            assertFalse(compiled.success());
            CompilerDiagnostic d = compiled.diagnostics().get(0);
            assertEquals("javac", d.code());
            assertEquals("T.jasx", d.path());
            int at = f.content().indexOf("undefinedName");
            assertEquals(at, d.diagnostic().start());
            assertEquals(new LineMap.Position(7, 8), new LineMap(f.content()).position(d.diagnostic().start()));
            assertTrue(d.format().startsWith("T.jasx:7:9: error: javac: "), d.format());
        }

        @Test
        void errorsInJavaCodeAfterMarkupKeepTheirPosition() {
            SourceFile f = file("""
                    static Element Test() {
                      Element e = <p>x</p>;
                      int bad = "string";
                      return e;
                    }
                    """);
            JavacRunner.Result compiled = JavacRunner.compile(compile(f).files(), List.of(), CLASSPATH);
            CompilerDiagnostic d = compiled.diagnostics().get(0);
            assertEquals(f.content().indexOf("\"string\""), d.diagnostic().start());
        }

        @Test
        void typedAttributesAreCheckedByJavac() {
            JavacRunner.Result compiled = JavacRunner.compile(compile(file("""
                    static Element Test() { return <input disabled="yes" />; }
                    """)).files(), List.of(), CLASSPATH);
            assertFalse(compiled.success());
        }
    }

    @Nested
    class Errors {
        @Test
        void unresolvedComponent() {
            assertEquals(List.of("JASX2001"), codes(file("static Element Test() { return <Missing />; }")));
        }

        @Test
        void ambiguousStaticOnDemandImports() {
            SourceFile a = new SourceFile("p/A.jasx", "package p; public class A { public static jasx.runtime.Element N() { return <i/>; } }");
            SourceFile b = new SourceFile("p/B.jasx", "package p; public class B { public static jasx.runtime.Element N() { return <i/>; } }");
            SourceFile use = new SourceFile("q/U.jasx", """
                    package q;
                    import static p.A.*;
                    import static p.B.*;
                    class U { static jasx.runtime.Element Test() { return <N />; } }
                    """);
            assertEquals(List.of("JASX2002"), codes(a, b, use));
        }

        @Test
        void childrenForComponentWithoutChildren() {
            assertEquals(List.of("JASX2003"), codes(file("""
                    static Element A() { return <p/>; }
                    static Element Test() { return <A>x</A>; }
                    """)));
        }

        @Test
        void wrongNumberOfChildrenForSingleChild() {
            assertEquals(List.of("JASX2004", "JASX2004"), codes(file("""
                    static Element Box(Element children) { return children; }
                    static Element Test() { return <><Box /><Box><a/><b/></Box></>; }
                    """)));
        }

        @Test
        void unknownProp() {
            assertEquals(List.of("JASX2005", "JASX2005"), codes(file("""
                    static Element A(String x) { return <p/>; }
                    static Element Test() { return <A x="1" y="2" ns:x="3" />; }
                    """)));
        }

        @Test
        void overloadedComponent() {
            assertEquals(List.of("JASX2006"), codes(file("""
                    static Element A(String x) { return <p/>; }
                    static Element A(int x) { return <p/>; }
                    """)));
        }

        @Test
        void missingRequiredProps() {
            assertEquals(List.of("JASX2007", "JASX2007"), codes(file("""
                    static Element A(String x, int y, @jasx.Optional String z) { return <p/>; }
                    static Element Test() { return <A />; }
                    """)));
        }

        @Test
        void childrenAsAttribute() {
            assertEquals(List.of("JASX2008"), codes(file("""
                    static Element Box(Element children) { return children; }
                    static Element Test() { return <Box children={null}><a/></Box>; }
                    """)));
        }

        @Test
        void invalidKey() {
            assertEquals(List.of("JASX2009", "JASX2009"), codes(file("static Element Test() { return <ul><li key /><li key=<b/> /></ul>; }")));
        }

        @Test
        void invalidComponentAnnotation() {
            SourceFile lib = new SourceFile("Lib.java", """
                    import jasx.Component;
                    import jasx.runtime.Element;
                    class Lib {
                      @Component Element NotStatic() { return null; }
                      @Component static Element lower() { return null; }
                      @Component static String NotElement() { return null; }
                    }
                    """);
            assertEquals(List.of("JASX2010", "JASX2010", "JASX2010"), codes(lib));
        }

        @Test
        void defaultMustBeALiteral() {
            assertEquals(List.of("JASX2011", "JASX2011"), codes(file("""
                    static Element A(@jasx.Default("make()") String x, @jasx.Default("1 + 2") int y) { return <p/>; }
                    """)));
        }

        @Test
        void unknownAttribute() {
            assertEquals(List.of("JASX2101"), codes(file("static Element Test() { return <div colour=\"red\" />; }")));
        }

        @Test
        void attributeOfAnotherElement() {
            assertEquals(List.of("JASX2101"), codes(file("static Element Test() { return <div href=\"/x\" />; }")));
        }

        @Test
        void childrenOnVoidElement() {
            assertEquals(List.of("JASX2102"), codes(file("static Element Test() { return <br>x</br>; }")));
        }

        @Test
        void javaEventHandlerOnHtml() {
            assertEquals(List.of("JASX2103"), codes(file("static Element Test() { return <button onclick={\"go()\"}>x</button>; }")));
        }

        @Test
        void unknownElement() {
            assertEquals(List.of("JASX2105"), codes(file("static Element Test() { return <blink>x</blink>; }")));
        }

        @Test
        void lowercaseTagIsNeverAComponent() {
            assertEquals(List.of("JASX2105"), codes(file("""
                    static Element row() { return <p/>; }
                    static Element Test() { return <row />; }
                    """)));
        }

        @Test
        void syntaxErrorsStopLoweringOfThatFileOnly() {
            CompilationResult r = compile(file("static Element Test() { return <p>x</div>; }"),
                    new SourceFile("U.jasx", "class U { static jasx.runtime.Element Test() { return <p/>; } }"));
            assertEquals(List.of("JASX1001"), r.diagnostics().stream().map(CompilerDiagnostic::code).toList());
            assertEquals(List.of("U.java"), r.files().stream().map(GeneratedFile::javaPath).toList());
        }

        @Test
        void diagnosticsHaveJasxLocations() {
            CompilerDiagnostic d = compile(file("static Element Test() {\n  return <div colour=\"red\" />;\n}")).diagnostics().get(0);
            assertEquals("T.jasx:6:15: error: <div> has no attribute 'colour' on target 'html' [JASX2101]", d.format());
        }
    }

    @Nested
    class Index {
        @Test
        void componentsJsonRoundTrip() {
            CompilationResult r = compile(file("""
                    static Element Card(String title, @jasx.Optional String sub, @jasx.Default("\\"x\\"") String d,
                                        List<Element> children) { return <p/>; }
                    """));
            ComponentIndex index = r.index();
            ComponentIndex copy = ComponentIndex.fromJson(index.toJson());
            assertEquals(index.all(), copy.all());
            ComponentIndex.Component card = copy.get("T", "Card");
            assertEquals(ComponentIndex.Children.LIST, card.children());
            assertEquals(List.of("REQUIRED", "OPTIONAL", "DEFAULT", "CHILDREN"),
                    card.params().stream().map(p -> p.kind().name()).toList());
            assertEquals("\"x\"", card.prop("d").defaultValue());
        }

        @Test
        void dependenciesResolveComponentsFromJars() {
            ComponentIndex deps = ComponentIndex.fromJson("""
                    [{"class": "lib.Ui", "method": "Chip", "params": [{"name": "label", "type": "java.lang.String", "kind": "required"}], "children": "none"}]
                    """);
            CompilationResult r = new JasxCompiler(HTML, deps).compile(List.of(new SourceFile("T.jasx", """
                    import static lib.Ui.*;
                    class T { static jasx.runtime.Element Test() { return <Chip label="c" />; } }
                    """)));
            assertEquals(List.of(), r.diagnostics());
            assertTrue(r.files().get(0).content().contains("lib.Ui.Chip(\"c\")"), r.files().get(0).content());
        }

        @Test
        void localComponentsTakePrecedenceOverDependencies() {
            ComponentIndex deps = ComponentIndex.fromJson("""
                    [{"class": "T", "method": "A", "params": [{"name": "x", "type": "int", "kind": "required"}], "children": "none"}]
                    """);
            CompilationResult r = new JasxCompiler(HTML, deps).compile(List.of(file("""
                    static Element A() { return <p/>; }
                    static Element Test() { return <A />; }
                    """)));
            assertEquals(List.of(), r.diagnostics());
        }
    }
}
