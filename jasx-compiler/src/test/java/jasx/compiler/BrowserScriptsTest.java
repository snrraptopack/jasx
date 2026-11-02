package jasx.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Script blocks, browser attributes and refs (semantics.md §11): analysis, lowering and the file's module. */
class BrowserScriptsTest {
    private static final Target HTML = Target.load("html", BrowserScriptsTest.class.getClassLoader());

    /** The html target with browser scripts: the generated Java is only read, never compiled. */
    private static final Target SCRIPTED = new Target("scripted", HTML.factory(), HTML.attributesPackage(),
            HTML.schema(), true, true, true, null);

    private static final String F = HTML.factory();

    private static SourceFile file(String members) {
        return new SourceFile("web/T.jasx", "package web;\nimport jasx.runtime.Element;\n\nclass T {\n" + members + "\n}\n");
    }

    private static CompilationResult compile(Target target, String members) {
        return new JasxCompiler(target).compile(List.of(file(members)));
    }

    private static List<String> codes(Target target, String members) {
        return compile(target, members).diagnostics().stream().map(CompilerDiagnostic::code).toList();
    }

    private static String java(CompilationResult result) {
        assertFalse(result.hasErrors(), () -> result.diagnostics().stream().map(CompilerDiagnostic::format)
                .collect(Collectors.joining("\n")));
        return result.files().get(0).content();
    }

    private static final String MENU = """
            <script>
              import { toggleMenu } from "./menu.js";
              export function close(event) {}
            </script>
            static Element Menu(jasx.ui.Action save) {
              return <div><button @click="toggleMenu({menu: $menu, save, size: 3, label: 'Menu'})">m</button><nav ref="menu"></nav></div>;
            }
            """;

    @Nested
    class Lowering {
        @Test
        void aCallLowersToScriptWithItsPayload() {
            String java = java(compile(SCRIPTED, MENU));
            // A dynamic attribute's value is an expression, written in parentheses (semantics.md §11.6).
            assertTrue(java.contains(".attr(\"@click\", (" + F + ".script(\"web/T.jasx.js\", \"toggleMenu\", (java.lang.Object) "
                    + F + ".object(\"menu\", (java.lang.Object) " + F + ".ref(\"menu\"), \"save\", (java.lang.Object) (save), "
                    + "\"size\", (java.lang.Object) 3, \"label\", (java.lang.Object) \"Menu\"))))"), java);
            assertTrue(java.contains(".attr(\"ref\", \"menu\")"), java);
        }

        @Test
        void theModuleIsTheScriptWithTheExportsCallsNeed() {
            CompilationResult result = compile(SCRIPTED, MENU);
            CompilationResult.ScriptModule module = result.modules().get(0);
            assertEquals("web/T.jasx.js", module.path());
            assertTrue(module.content().contains("import { toggleMenu } from \"./menu.js\";"), module.content());
            assertTrue(module.content().contains("export { toggleMenu };"), module.content());
        }

        @Test
        void aFunctionTheScriptExportsAlreadyIsNotExportedAgain() {
            CompilationResult result = compile(SCRIPTED, MENU.replace("toggleMenu({menu: $menu, save, size: 3, label: 'Menu'})", "close()"));
            String content = result.modules().get(0).content();
            assertFalse(content.contains("export { close }"), content);
            assertTrue(java(result).contains(F + ".script(\"web/T.jasx.js\", \"close\", (java.lang.Object) null)"));
        }

        @Test
        void inlineCodeBecomesAFunctionOfTheModuleWithTheRefsItUses() {
            CompilationResult result = compile(SCRIPTED, """
                    static Element Menu() {
                      return <div><button @click="$menu.hidden = !$menu.hidden">m</button><nav ref="menu"></nav>
                        <li @dragover="event.preventDefault()">x</li></div>;
                    }
                    """);
            String java = java(result);
            assertTrue(java.contains(F + ".script(\"web/T.jasx.js\", \"jx$1\", " + F + ".object(\"$menu\", (java.lang.Object) "
                    + F + ".ref(\"menu\")))"), java);
            assertTrue(java.contains(F + ".script(\"web/T.jasx.js\", \"jx$2\", (java.lang.Object) null)"), java);
            String content = result.modules().get(0).content();
            assertTrue(content.contains("export function jx$1(event, { $menu }) {\n$menu.hidden = !$menu.hidden\n}"), content);
            assertTrue(content.contains("export function jx$2(event) {\nevent.preventDefault()\n}"), content);
        }

        @Test
        void aScriptBlockKeepsItsLinesAndTheClassAroundIt() {
            CompilationResult result = compile(SCRIPTED, MENU);
            String java = java(result);
            String source = file(MENU).content();
            assertEquals(source.lines().count(), java.lines().count(), "lines are preserved");
            assertTrue(result.declarations().stream().anyMatch(d -> d.component().method().equals("Menu")),
                    "the component after the script block is still found");
        }

        @Test
        void aJavaNameIsCopiedFromTheSourceSoJavacReportsItThere() {
            CompilationResult result = compile(SCRIPTED, MENU);
            GeneratedFile generated = result.files().get(0);
            int inGenerated = generated.content().indexOf("(save)") + 1;
            int inSource = file(MENU).content().indexOf("save, size");
            assertEquals(inSource, generated.spanMap().toSource(inGenerated));
        }
    }

    @Nested
    class Errors {
        @Test
        void aBrowserAttributeTakesAString() {
            assertEquals(List.of("JASX2017"), codes(SCRIPTED, "static Element A() { return <b @click={x}>x</b>; }"));
            assertEquals(List.of("JASX2017"), codes(SCRIPTED, "static Element A() { return <b @click>x</b>; }"));
        }

        @Test
        void browserAttributesAndRefsBelongOnElements() {
            assertEquals(List.of("JASX2018", "JASX2018"), codes(SCRIPTED, """
                    static Element B() { return <b>x</b>; }
                    static Element A() { return <B @click="window.print()" ref="b" />; }
                    """));
        }

        @Test
        void aCallMustNameAFunctionOfTheScript() {
            CompilationResult result = compile(SCRIPTED, "static Element A() { return <b @click=\"alert('hi')\">x</b>; }");
            assertEquals(List.of("JASX2019"), result.diagnostics().stream().map(CompilerDiagnostic::code).toList());
            int start = file("static Element A() { return <b @click=\"alert('hi')\">x</b>; }").content().indexOf("alert");
            assertEquals(start, result.diagnostics().get(0).diagnostic().start());
            assertEquals(List.of(), codes(SCRIPTED, "static Element A() { return <b @click=\"window.alert('hi')\">x</b>; }"),
                    "a browser global is inline code");
        }

        @Test
        void aMalformedPayloadIsReported() {
            String members = "<script>function go() {}</script>\nstatic Element A() { return <b @click='go(PAYLOAD)'>x</b>; }";
            for (String payload : List.of("{a: 1", "\\\"text\\\"", "a.b(1)", "{1: x}", "x y")) {
                assertEquals(List.of("JASX2020"), codes(SCRIPTED, members.replace("'go(PAYLOAD)'", "\"go(" + payload + ")\"")),
                        payload);
            }
        }

        @Test
        void aRefMustBeDeclaredInTheFile() {
            assertEquals(List.of("JASX2021"), codes(SCRIPTED, "static Element A() { return <b @click=\"$nav.hidden = true\">x</b>; }"));
        }

        @Test
        void aRefIsANameInAString() {
            assertEquals(List.of("JASX2022"), codes(SCRIPTED, "static Element A() { return <b ref={x}>x</b>; }"));
            assertEquals(List.of("JASX2022"), codes(SCRIPTED, "static Element A() { return <b ref=\"a-b\">x</b>; }"));
        }

        @Test
        void aTargetWithoutScriptsRejectsThem() {
            assertEquals(List.of("JASX2107"), codes(HTML, "<script>let a = 1;</script>"));
            assertEquals(List.of("JASX2107", "JASX2107"),
                    codes(HTML, "static Element A() { return <b @click=\"window.print()\" ref=\"b\">x</b>; }"));
        }

        @Test
        void onATargetWithScriptsEventAttributesTakeNoString() {
            assertEquals(List.of("JASX2108"), codes(SCRIPTED, "static Element A() { return <b onclick=\"window.print()\">x</b>; }"));
            assertEquals(List.of(), codes(HTML, "static Element A() { return <b onclick=\"window.print()\">x</b>; }"),
                    "on html, a string is inline JavaScript, as before");
        }
    }

    /** Reading a script (semantics.md §11.1). */
    @Nested
    class Reading {
        @Test
        void importsDeclarationsAndExports() {
            Map<String, Boolean> names = ScriptReader.topLevelNames("""
                    import d from "./a.js";
                    import { a, b as c } from "./b.js";
                    import * as ns from "./c.js";
                    import e, { f } from "./d.js";
                    function g() { const inner = 1; }
                    export async function h() {}
                    export default class K {}
                    const x = 1, y = 2;
                    let z = () => { let hidden; };
                    export { g };
                    const { destructured } = o;
                    """);
            assertEquals(Map.ofEntries(Map.entry("d", false), Map.entry("a", false), Map.entry("c", false),
                    Map.entry("ns", false), Map.entry("e", false), Map.entry("f", false), Map.entry("g", true),
                    Map.entry("h", true), Map.entry("K", true), Map.entry("x", false), Map.entry("y", false),
                    Map.entry("z", false)), names);
        }

        @Test
        void stringsCommentsTemplatesAndRegularExpressionsAreSkipped() {
            Map<String, Boolean> names = ScriptReader.topLevelNames("""
                    const s = "function no() {}"; // function nope() {}
                    /* function never() {} */
                    const t = `${ { function inTemplate() {} } }`;
                    const r = /function inRegex() {}/g;
                    function yes() {}
                    """);
            assertEquals(List.of("s", "t", "r", "yes"), List.copyOf(names.keySet()));
        }

        @Test
        void refsInInlineCode() {
            List<String> refs = ScriptReader.refs("$menu.hidden = '$notARef' + $nav.id; // $neither").stream()
                    .map(ScriptReader.RefUse::name).toList();
            assertEquals(List.of("menu", "nav"), refs);
        }
    }
}
