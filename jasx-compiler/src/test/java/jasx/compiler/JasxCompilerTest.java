package jasx.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jasx.html.Html;
import jasx.parser.LineMap;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

        @Test
        void typeCheckReportsErrorsAtTheirJasxPosition(@TempDir Path work) throws IOException {
            SourceFile f = file("static Element Test() { return <p>{undefinedName}</p>; }");
            JavacRunner.BuildOptions options = new JavacRunner.BuildOptions(CLASSPATH, "", null, work);

            List<CompilerDiagnostic> diagnostics = JavacRunner.typeCheck(compile(f).files(), List.of(), options);

            assertEquals(1, diagnostics.size(), () -> format(diagnostics));
            assertEquals("T.jasx", diagnostics.get(0).path());
            assertEquals(f.content().indexOf("undefinedName"), diagnostics.get(0).diagnostic().start());
        }

        @Test
        void typeCheckCompilesForTheGivenRelease(@TempDir Path work) throws IOException {
            // List.getFirst() is new in Java 21.
            SourceFile f = file("static Element Test() { return <p>{List.of(1).getFirst()}</p>; }");

            List<CompilerDiagnostic> for21 = JavacRunner.typeCheck(compile(f).files(), List.of(),
                    new JavacRunner.BuildOptions(CLASSPATH, "", 21, work));
            List<CompilerDiagnostic> for17 = JavacRunner.typeCheck(compile(f).files(), List.of(),
                    new JavacRunner.BuildOptions(CLASSPATH, "", 17, work));

            assertTrue(for21.stream().noneMatch(CompilerDiagnostic::isError), () -> format(for21));
            assertTrue(for17.stream().anyMatch(CompilerDiagnostic::isError), () -> format(for17));
            assertTrue(format(for17).contains("getFirst"), () -> format(for17));
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

    /** semantics.md §9: files with top-level fields and methods are wrapped in their file class. */
    @Nested
    class FileClasses {
        @Test
        void topLevelComponentsAreUsedFromAnotherPackage() {
            SourceFile ui = new SourceFile("app/ui/Ui.jasx", """
                    package app.ui;

                    import jasx.runtime.Element;
                    import java.util.List;

                    public Element Card(String title, List<Element> children) {
                      return <section><h2>{title}</h2>{children}</section>;
                    }
                    """);
            SourceFile page = new SourceFile("app/Page.jasx", """
                    package app;

                    import jasx.runtime.Element;
                    import static app.ui.Ui.*;

                    class Page {
                      static Element Test() { return <Card title="T"><p>body</p></Card>; }
                    }
                    """);
            assertEquals("<section><h2>T</h2><p>body</p></section>", render("app.Page", List.of(ui, page), List.of()));
        }

        @Test
        void everyTopLevelDeclarationBecomesStatic() {
            // Each of these would fail to compile if it weren't static: Test() uses all of them.
            assertEquals("<p>3 ok x</p>", render("T", List.of(new SourceFile("T.jasx", """
                    import jasx.runtime.Element;

                    int[] NUMBERS = {1, 2, 3};
                    java.util.function.Supplier<String> OK = () -> { return "ok"; };
                    enum Mode { X }
                    class Holder { static String name(Mode m) { return m.name().toLowerCase(); } }

                    Element Test() {
                      return <p>{NUMBERS.length} {OK.get()} {Holder.name(Mode.X)}</p>;
                    }
                    """)), List.of()));
        }

        @Test
        void staticIsNotAddedTwice() {
            CompilationResult r = compile(new SourceFile("T.jasx", """
                    import jasx.runtime.Element;
                    public static Element A() { return <p/>; }
                    """));
            String java = r.files().get(0).content();
            assertTrue(java.contains("@jasx.Component public static Element A()"), java);
            assertFalse(java.contains("static static"), java);
        }

        @Test
        void linesArePreserved() {
            String source = """
                    package app;

                    import jasx.runtime.Element;

                    /** A component. */
                    public Element Badge(String text) {
                      return <span class="badge">{text}</span>;
                    }

                    private String helper() { return "x"; }
                    """;
            CompilationResult r = compile(new SourceFile("app/Ui.jasx", source));
            String[] sourceLines = source.split("\n");
            String[] javaLines = r.files().get(0).content().split("\n");
            assertTrue(javaLines[2].endsWith("public final class Ui { private Ui() {} "), javaLines[2]);
            assertEquals("/** A component. */", javaLines[4]);
            assertTrue(javaLines[5].contains("Badge(String text) {"), javaLines[5]);
            assertTrue(javaLines[6].contains("jasx.html.Html.h(\"span\""), javaLines[6]);
            assertEquals("private static String helper() { return \"x\"; }", javaLines[9]);
            assertEquals("}", javaLines[sourceLines.length]);
        }

        @Test
        void topLevelTypesAreNestedInTheFileClass() {
            CompilationResult r = compile(new SourceFile("app/Ui.jasx", """
                    package app;
                    import jasx.runtime.Element;
                    class Icons { static Element Star() { return <i/>; } }
                    Element Button() { return <button/>; }
                    """));
            assertEquals(List.of(), r.diagnostics());
            List<String> classes = r.index().all().stream().map(c -> c.className() + "." + c.method()).toList();
            assertEquals(List.of("app.Ui.Icons.Star", "app.Ui.Button"), classes);
        }

        @Test
        void aFileWithoutTopLevelMembersIsUnchanged() {
            SourceFile plain = file("static Element A() { return <p/>; }");
            String java = compile(plain).files().get(0).content();
            assertFalse(java.contains("public final class"), java);
        }

        @Test
        void theFileNameMustBeAClassName() {
            CompilationResult r = compile(new SourceFile("my-page.jasx", """
                    import jasx.runtime.Element;
                    Element Page() { return <p/>; }
                    """));
            assertEquals(List.of("JASX2012"), r.diagnostics().stream().map(CompilerDiagnostic::code).toList());
            assertEquals("my-page.jasx:2:1: error: this file declares fields or methods at the top level, so it needs"
                    + " a file class named after the file, but 'my-page' is not a valid Java class name [JASX2012]",
                    r.diagnostics().get(0).format());
            assertEquals(List.of(), r.files());
        }

        @Test
        void constructorsAndInitializersAreNotAllowedAtTheTopLevel() {
            CompilationResult r = compile(new SourceFile("T.jasx", """
                    import jasx.runtime.Element;
                    T() {}
                    static { System.out.println(); }
                    Element A() { return <p/>; }
                    """));
            assertEquals(List.of("T.jasx:2:1", "T.jasx:3:1"), r.diagnostics().stream()
                    .map(d -> d.format().substring(0, d.format().indexOf(": ")))
                    .toList());
            assertEquals(List.of("JASX2013", "JASX2013"), r.diagnostics().stream().map(CompilerDiagnostic::code).toList());
            assertEquals(List.of(), r.files());
        }

        @Test
        void javacErrorsInTopLevelCodeAreRemapped() {
            CompilationResult r = compile(new SourceFile("T.jasx", """
                    import jasx.runtime.Element;

                    Element A() {
                      return <p>{missing}</p>;
                    }
                    """));
            JavacRunner.Result compiled = JavacRunner.compile(r.files(), List.of(), CLASSPATH);
            assertEquals("T.jasx:4:14: error: javac: cannot find symbol",
                    compiled.diagnostics().get(0).format().lines().findFirst().orElseThrow());
        }
    }

    /** semantics.md §10: style blocks. */
    @Nested
    class StyleBlocks {
        /** The scope name of a file with this qualified name (semantics.md §10.3), computed independently. */
        private static String scope(String qualifiedName) throws Exception {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(qualifiedName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "jx-" + java.util.HexFormat.of().formatHex(hash).substring(0, 8);
        }

        private static final SourceFile UI = new SourceFile("app/Ui.jasx", """
                package app;

                import jasx.runtime.Element;
                import java.util.List;

                <style>
                  .card { padding: 1rem; }
                </style>

                public Element Card(List<Element> children) {
                  return <div class="card">{children}</div>;
                }
                """);

        @Test
        void aStyleBlockAppliesOnlyToElementsWrittenInItsFile() throws Exception {
            SourceFile page = new SourceFile("app/Page.jasx", """
                    package app;

                    import jasx.runtime.Element;
                    import jasx.html.Html;

                    class Page {
                      static Element Test() {
                        return <>{Html.styles()}<Ui.Card><p>not scoped</p></Ui.Card></>;
                      }
                    }
                    """);
            String ui = scope("app.Ui");
            assertEquals("<style>.card." + ui + " { padding: 1rem; }</style>"
                            + "<div class=\"card " + ui + "\"><p>not scoped</p></div>",
                    render("app.Page", List.of(UI, page), List.of()));
        }

        @Test
        void styleSheetsAreCollectedInTheOrderTheyAreFirstUsed() throws Exception {
            SourceFile page = new SourceFile("app/Page.jasx", """
                    package app;

                    import jasx.runtime.Element;
                    import jasx.html.Html;

                    <style>p { margin: 0; }</style>

                    Element Test() {
                      return <>{Html.styles()}<p>a</p><Ui.Card><p>b</p></Ui.Card><p>c</p></>;
                    }
                    """);
            String ui = scope("app.Ui");
            String pg = scope("app.Page");
            assertEquals("<style>p." + pg + " { margin: 0; }\n.card." + ui + " { padding: 1rem; }</style>"
                            + "<p class=\"" + pg + "\">a</p>"
                            + "<div class=\"card " + ui + "\"><p class=\"" + pg + "\">b</p></div>"
                            + "<p class=\"" + pg + "\">c</p>",
                    render("app.Page", List.of(UI, page), List.of()));
        }

        @Test
        void theStyleBlockKeepsLinesAligned() {
            CompilationResult r = compile(new SourceFile("T.jasx", """
                    import jasx.runtime.Element;
                    <style>
                      a { }
                    </style>
                    Element A() {
                      return <p>{missing}</p>;
                    }
                    """));
            // The field goes right after the file class header, on the first line; the block's lines stay empty.
            String[] javaLines = r.files().get(0).content().split("\n");
            assertTrue(javaLines[0].contains("private T() {} static final java.lang.Object jasx$styles = "), javaLines[0]);
            assertEquals("", javaLines[1]);
            assertEquals("", javaLines[2]);
            JavacRunner.Result compiled = JavacRunner.compile(r.files(), List.of(), CLASSPATH);
            assertEquals("T.jasx:6:14: error: javac: cannot find symbol",
                    compiled.diagnostics().get(0).format().lines().findFirst().orElseThrow());
        }

        @Test
        void cssErrorsAreReportedInTheJasxFile() {
            CompilationResult r = compile(new SourceFile("T.jasx", """
                    import jasx.runtime.Element;
                    <style>
                      a { color: red;
                    </style>
                    Element A() { return <p/>; }
                    """));
            assertEquals(List.of("T.jasx:3:5: error: this block is not closed [JASX2014]"),
                    r.diagnostics().stream().map(CompilerDiagnostic::format).toList());
            assertEquals(List.of(), r.files());
        }

        @Test
        void aStyleBlockMustBeInsideAClass() {
            // At the top level of a file without a file class: in no class at all.
            assertEquals(List.of("JASX2015"), codes(new SourceFile("T.jasx", "<style>a{}</style>\nclass T {}\n")));
        }

        @Test
        void aStyleBlockCanBeInAMethodBody() {
            // Before the return, between statements, and in a lambda body.
            assertEquals("<p class=\"jx-e632b709\">2</p>", render("""
                    static Element Test() {
                      <style>p { margin: 0; }</style>
                      int n = 1;
                      <style>b { }</style>
                      Runnable r = () -> { <style>i { }</style> };
                      return <p>{n + 1}</p>;
                    }
                    """));
        }

        @Test
        void aStyleBlockInATopLevelMethodUsesTheFileClass() {
            CompilationResult r = compile(new SourceFile("T.jasx", """
                    import jasx.runtime.Element;
                    Element A() {
                      <style>p { }</style>
                      return <p/>;
                    }
                    """));
            assertEquals(List.of(), r.diagnostics());
            assertTrue(r.files().get(0).content().contains("jasx.html.Html.scoped(T.jasx$styles, "),
                    r.files().get(0).content());
        }

        @Test
        void aStyleBlockCanBeInAnyClassBody() {
            CompilationResult r = compile(new SourceFile("T.jasx", """
                    import jasx.runtime.Element;
                    class T {
                      static class Inner {
                        <style>a { }</style>
                      }
                      static Element A() { return <a/>; }
                    }
                    """));
            // The style sheet is declared in the top-level class, right after its '{' (semantics.md §10.5).
            String java = r.files().get(0).content();
            assertEquals(List.of(), r.diagnostics());
            assertTrue(java.contains("class T {static final java.lang.Object jasx$styles = "), java);
            assertTrue(java.contains("jasx.html.Html.scoped(T.jasx$styles, "), java);
        }

        @Test
        void severalStyleBlocksMakeOneStyleSheet() {
            assertEquals("<style>a.jx-e632b709 { }\nb.jx-e632b709 { }</style><b class=\"jx-e632b709\"></b>", render("""
                    <style>a { }</style>
                    <style>b { }</style>
                    static Element Test() { return <>{jasx.html.Html.styles()}<b/></>; }
                    """));
        }

        @Test
        void aStyleBlockInsideMarkupRendersNothingWhereItIs() {
            assertEquals("<style>h2.jx-e632b709 { margin: 0; }</style><div>"
                            + "<h2 class=\"jx-e632b709\">T</h2></div>",
                    render("""
                    static Element Test() {
                      return (
                        <>
                          {jasx.html.Html.styles()}
                          <div>
                            <style>
                              h2 { margin: 0; }
                            </style>
                            <h2>T</h2>
                          </div>
                        </>
                      );
                    }
                    """));
        }

        @Test
        void aStyleBlockIsNotPassedToAComponentAsAChild() {
            assertEquals("<section><p class=\"jx-e632b709\">only child</p></section>", render("""
                    static Element Box(Element children) { return <section>{children}</section>; }
                    static Element Test() {
                      return <Box><style>p { }</style><p>only child</p></Box>;
                    }
                    """));
        }

        @Test
        void aStyleElementWithAnExpressionIsStillAnElement() {
            assertEquals("<style>a > b {}</style>", render("""
                    static Element Test() { String css = "a > b {}"; return <style>{css}</style>; }
                    """));
        }

        @Test
        void cssErrorsInsideMarkupAreReportedInTheJasxFile() {
            CompilationResult r = compile(file("static Element A() { return <div><style>a {</style></div>; }"));
            assertEquals(List.of("T.jasx:5:43: error: this block is not closed [JASX2014]"),
                    r.diagnostics().stream().map(CompilerDiagnostic::format).toList());
        }

        @Test
        void theTargetMustSupportStyleBlocks() {
            Target withoutStyles = new Target("plain", HTML.factory(), HTML.attributesPackage(), HTML.schema(),
                    HTML.javaEventHandlers(), false);
            CompilationResult r = new JasxCompiler(withoutStyles).compile(List.of(file("<style>a{}</style>")));
            assertEquals(List.of("JASX2106"), r.diagnostics().stream().map(CompilerDiagnostic::code).toList());
        }

        @Test
        void filesWithoutAStyleBlockAreNotScoped() {
            String java = compile(file("static Element A() { return <p/>; }")).files().get(0).content();
            assertFalse(java.contains("scoped("), java);
        }
    }

    /** CompilationResult.declarations and uses, for editor tooling. */
    @Nested
    class EditorInformation {
        @Test
        void declarationsAndUsesPointAtNames() {
            SourceFile ui = new SourceFile("app/Ui.jasx", """
                    package app;
                    import jasx.runtime.Element;
                    import java.util.List;
                    public Element Card(List<Element> children) { return <div>{children}</div>; }
                    """);
            SourceFile page = new SourceFile("app/Page.jasx", """
                    package app;
                    import jasx.runtime.Element;
                    class Page {
                      static Element Test() { return <Ui.Card><p/></Ui.Card>; }
                    }
                    """);
            CompilationResult r = compile(ui, page);
            assertEquals(List.of(), r.diagnostics());

            CompilationResult.Declaration card = r.declarations().stream()
                    .filter(d -> d.component().method().equals("Card")).findFirst().orElseThrow();
            assertEquals("app/Ui.jasx", card.path());
            assertEquals("Card", ui.content().substring(card.nameStart(), card.nameEnd()));

            List<String> uses = r.uses().stream()
                    .map(u -> u.path() + ":" + page.content().substring(u.nameStart(), u.nameEnd()) + "->" + u.component().method())
                    .toList();
            assertEquals(List.of("app/Page.jasx:Ui.Card->Card", "app/Page.jasx:Ui.Card->Card"), uses);
        }

        @Test
        void usesAreRecordedEvenWhenTheFileHasOtherErrors() {
            CompilationResult r = compile(file("""
                    static Element A(String x) { return <p/>; }
                    static Element B() { return <A />; }
                    """));
            assertEquals(List.of("JASX2007"), r.diagnostics().stream().map(CompilerDiagnostic::code).toList());
            assertEquals(1, r.uses().size());
        }
    }

    @Nested
    class VisibleComponentNames {
        private final ComponentIndex index = new JasxCompiler(HTML).compile(List.of(new SourceFile("lib/Ui.jasx",
                "package lib;\npublic class Ui { public static jasx.runtime.Element Card() { return <i/>; } }\n"))).index();

        /** The name offered for lib.Ui.Card in a file of package app with the given imports. */
        private String nameIn(String imports) {
            String text = "package app;\n" + imports + "\nclass Page { Object x = null; }\n";
            SourceFile page = new SourceFile("app/Page.jasx", text);
            return VisibleComponents.at(page, text.indexOf("null"), index).get(0).name();
        }

        @Test
        void fullyQualifiedWhenNothingIsImported() {
            assertEquals("lib.Ui.Card", nameIn(""));
        }

        @Test
        void classAndNameWhenTheClassIsImported() {
            assertEquals("Ui.Card", nameIn("import lib.Ui;"));
            assertEquals("Ui.Card", nameIn("import lib.*;"));
        }

        @Test
        void simpleNameWhenStaticallyImported() {
            assertEquals("Card", nameIn("import static lib.Ui.*;"));
            assertEquals("Card", nameIn("import static lib.Ui.Card;"));
        }

        @Test
        void aTagNameIsResolvedHoweverItIsWritten() {
            String text = "package app;\nimport lib.Ui;\nclass Page { Object x = null; }\n";
            SourceFile page = new SourceFile("app/Page.jasx", text);
            int offset = text.indexOf("null");
            assertEquals("Card", VisibleComponents.named("Ui.Card", page, offset, index).method());
            assertEquals("Card", VisibleComponents.named("lib.Ui.Card", page, offset, index).method());
            assertNull(VisibleComponents.named("Card", page, offset, index));
        }
    }

    /** semantics.md §3.5, §6.8. */
    @Nested
    class StatementContainers {
        @Test
        void asTheChildrenOfAComponent() {
            assertEquals("<ul><li>1</li><li>2</li></ul>", render("""
                    static Element List(List<Element> children) { return <ul>{children}</ul>; }
                    static Element Test() {
                      return <List>{for (int i = 1; i <= 2; i++) { <li>{i}</li> }}</List>;
                    }
                    """));
        }

        @Test
        void aStatementThatAddsNothingRendersNothing() {
            assertEquals("<p></p>", render("""
                    static Element Test() { return <p>{if (false) { <b/> }}</p>; }
                    """));
        }

        @Test
        void aStyleBlockInAStatementJoinsTheStyleSheet() {
            String html = render("""
                    static Element Test() {
                      return <div>{jasx.html.Html.styles()}{if (true) { <style>b { color: red; }</style> <b>x</b> }}</div>;
                    }
                    """);
            assertTrue(html.contains("b.jx-") && html.contains("<b class=\"jx-"), html);
        }

        @Test
        void returnIsRejectedByJavacAtItsJasxPosition() {
            SourceFile f = file("static Element Test() { return <p>{if (true) { return null; }}</p>; }");
            CompilationResult r = compile(f);
            assertFalse(r.hasErrors(), () -> format(r.diagnostics()));
            JavacRunner.Result compiled = JavacRunner.compile(r.files(), List.of(), CLASSPATH);
            assertFalse(compiled.success());
            CompilerDiagnostic d = compiled.diagnostics().get(0);
            assertEquals("T.jasx", d.path());
            assertEquals(f.content().indexOf("return null"), d.diagnostic().start());
        }
    }
}
