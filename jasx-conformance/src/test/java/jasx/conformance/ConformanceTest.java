package jasx.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import jasx.compiler.CompilationResult;
import jasx.compiler.CompilerDiagnostic;
import jasx.compiler.JasxCompiler;
import jasx.compiler.JavacRunner;
import jasx.compiler.SourceFile;
import jasx.compiler.Target;
import jasx.html.Html;
import jasx.parser.AstJson;
import jasx.parser.JasxParser;
import jasx.parser.LineMap;
import jasx.parser.ParseResult;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The conformance suite of jasx-spec/conformance.md, one dynamic test per fixture: Parser (tests/parse),
 * Diagnostics (tests/errors), and the html reference target (tests/render).
 */
class ConformanceTest {
    private static final Path TESTS = Path.of(System.getProperty("jasx.spec.dir"), "tests");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> DIAGNOSTIC_FIELDS = List.of("code", "severity", "range", "loc");

    @TestFactory
    Stream<DynamicTest> parser() throws IOException {
        return fixtures("parse", ".ast.json").map(name -> DynamicTest.dynamicTest(name, () -> {
            String source = read("parse", name + ".jasx");
            ParseResult result = JasxParser.parse(source, name + ".jasx");
            LineMap lines = new LineMap(source);
            assertTrue(result.diagnostics().isEmpty(), () -> "unexpected diagnostics:\n" + result.diagnostics().stream()
                    .map(d -> d.format(name + ".jasx", lines)).collect(Collectors.joining("\n")));

            JsonNode expected = normalizeAst(JSON.readTree(read("parse", name + ".ast.json")));
            JsonNode actual = normalizeAst(JSON.readTree(AstJson.toJson(result.unit(), source)));
            String difference = firstDifference("$", expected, actual);
            if (difference != null) {
                fail("AST differs from " + name + ".ast.json at " + difference);
            }
        }));
    }

    /** conformance.md §2: every expected AST is valid against ast.schema.json, and so is every AST we emit. */
    @TestFactory
    Stream<DynamicTest> astSchema() throws IOException {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(JSON.readTree(Files.readString(TESTS.resolveSibling("ast.schema.json"), StandardCharsets.UTF_8)));
        // Guard against a validator that accepts everything.
        assertTrue(!schema.validate(JSON.readTree("{\"type\": \"JasxCompilationUnit\", \"segments\": [1]}")).isEmpty(),
                "ast.schema.json accepted an invalid AST");
        return fixtures("parse", ".ast.json").map(name -> DynamicTest.dynamicTest(name, () -> {
            String source = read("parse", name + ".jasx");
            JsonNode expected = JSON.readTree(read("parse", name + ".ast.json"));
            JsonNode actual = JSON.readTree(AstJson.toJson(JasxParser.parse(source, name + ".jasx").unit(), source));
            assertEquals(Set.of(), schema.validate(expected), "fixture is invalid against ast.schema.json");
            assertEquals(Set.of(), schema.validate(actual), "emitted AST is invalid against ast.schema.json");
        }));
    }

    @TestFactory
    Stream<DynamicTest> diagnostics() throws IOException {
        return fixtures("errors", ".diagnostics.json").map(name -> DynamicTest.dynamicTest(name, () -> {
            String source = read("errors", name + ".jasx");
            ParseResult result = JasxParser.parse(source, name + ".jasx");

            List<JsonNode> expected = project(JSON.readTree(read("errors", name + ".diagnostics.json")));
            List<JsonNode> actual = project(JSON.readTree(AstJson.diagnosticsToJson(result.diagnostics(), source)));
            assertTrue(!actual.isEmpty(), "expected diagnostics, but none were reported");
            // conformance.md §3: every expected diagnostic is reported, and the first reported one is the
            // first expected one. Extra diagnostics after the first (error recovery) are allowed.
            assertEquals(expected.get(0), actual.get(0), "first reported diagnostic");
            for (JsonNode e : expected) {
                assertTrue(actual.contains(e), () -> "missing diagnostic " + e + "\nreported: " + actual);
            }
        }));
    }

    /**
     * conformance.md §4: lower with the html target, compile, render {@code Test()}, and compare with the
     * expected HTML (minus its trailing newline).
     */
    @TestFactory
    Stream<DynamicTest> htmlTarget() throws IOException {
        Target html = Target.load("html", ConformanceTest.class.getClassLoader());
        return fixtures("render", ".html").map(name -> DynamicTest.dynamicTest(name, () -> {
            String source = read("render", name + ".jasx");
            CompilationResult result = new JasxCompiler(html).compile(List.of(new SourceFile(name + ".jasx", source)));
            assertTrue(result.diagnostics().isEmpty(), () -> "unexpected diagnostics:\n" + lines(result.diagnostics()));

            String generated = result.files().get(0).content();
            JavacRunner.Result compiled = JavacRunner.compile(result.files(), List.of(), System.getProperty("java.class.path"));
            assertTrue(compiled.success(), () -> "javac failed:\n" + lines(compiled.diagnostics()) + "\ngenerated:\n" + generated);

            Method test = compiled.classLoader(ConformanceTest.class.getClassLoader()).loadClass("T").getDeclaredMethod("Test");
            test.setAccessible(true);
            String expected = read("render", name + ".html");
            if (expected.endsWith("\n")) {
                expected = expected.substring(0, expected.length() - 1);
            }
            assertEquals(expected, Html.render(test.invoke(null)), () -> "generated:\n" + generated);
        }));
    }

    private static String lines(List<CompilerDiagnostic> diagnostics) {
        return diagnostics.stream().map(CompilerDiagnostic::format).collect(Collectors.joining("\n"));
    }

    private static Stream<String> fixtures(String level, String expectedSuffix) throws IOException {
        try (Stream<Path> files = Files.list(TESTS.resolve(level))) {
            List<String> names = files.map(p -> p.getFileName().toString())
                    .filter(f -> f.endsWith(expectedSuffix))
                    .map(f -> f.substring(0, f.length() - expectedSuffix.length()))
                    .sorted()
                    .toList();
            if (names.isEmpty()) {
                throw new IllegalStateException("no fixtures in " + TESTS.resolve(level));
            }
            return names.stream();
        }
    }

    private static String read(String level, String file) throws IOException {
        return Files.readString(TESTS.resolve(level).resolve(file), StandardCharsets.UTF_8);
    }

    /** conformance.md §2: ignore {@code sourceFile} and fields whose names start with {@code _}. */
    private static JsonNode normalizeAst(JsonNode root) {
        ((ObjectNode) root).remove("sourceFile");
        removeUnderscoreFields(root);
        return root;
    }

    private static void removeUnderscoreFields(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String n : names) {
                if (n.startsWith("_")) {
                    object.remove(n);
                } else {
                    removeUnderscoreFields(object.get(n));
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(ConformanceTest::removeUnderscoreFields);
        }
    }

    /** conformance.md §3: diagnostics are compared on code, severity, range and loc only. */
    private static List<JsonNode> project(JsonNode diagnostics) {
        List<JsonNode> projected = new ArrayList<>();
        for (JsonNode d : diagnostics) {
            ObjectNode p = JSON.createObjectNode();
            DIAGNOSTIC_FIELDS.forEach(f -> p.set(f, d.get(f)));
            projected.add(p);
        }
        return projected;
    }

    /** Returns a description of the first difference between two JSON trees, or null if they are equal. */
    private static String firstDifference(String path, JsonNode expected, JsonNode actual) {
        if (expected.isObject() && actual.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = expected.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                JsonNode other = actual.get(field.getKey());
                if (other == null) {
                    return path + "." + field.getKey() + ": missing (expected " + field.getValue() + ")";
                }
                String d = firstDifference(path + "." + field.getKey(), field.getValue(), other);
                if (d != null) {
                    return d;
                }
            }
            for (Iterator<String> it = actual.fieldNames(); it.hasNext(); ) {
                String name = it.next();
                if (!expected.has(name)) {
                    return path + "." + name + ": unexpected field " + actual.get(name);
                }
            }
            return null;
        }
        if (expected.isArray() && actual.isArray()) {
            for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
                String d = firstDifference(path + "[" + i + "]", expected.get(i), actual.get(i));
                if (d != null) {
                    return d;
                }
            }
            return expected.size() == actual.size() ? null
                    : path + ": expected " + expected.size() + " elements, got " + actual.size();
        }
        return expected.equals(actual) ? null : path + ": expected " + expected + ", got " + actual;
    }
}
