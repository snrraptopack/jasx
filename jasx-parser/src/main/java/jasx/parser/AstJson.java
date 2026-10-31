package jasx.parser;

import java.util.List;
import jasx.parser.ast.Ast.JasxAttribute;
import jasx.parser.ast.Ast.JasxClosingElement;
import jasx.parser.ast.Ast.JasxClosingFragment;
import jasx.parser.ast.Ast.JasxCompilationUnit;
import jasx.parser.ast.Ast.JasxElement;
import jasx.parser.ast.Ast.JasxEmptyExpression;
import jasx.parser.ast.Ast.JasxExpressionContainer;
import jasx.parser.ast.Ast.JasxMarkupStatement;
import jasx.parser.ast.Ast.JasxStatementContainer;
import jasx.parser.ast.Ast.JasxFragment;
import jasx.parser.ast.Ast.JasxIdentifier;
import jasx.parser.ast.Ast.JasxNamespacedName;
import jasx.parser.ast.Ast.JasxOpeningElement;
import jasx.parser.ast.Ast.JasxOpeningFragment;
import jasx.parser.ast.Ast.JasxQualifiedName;
import jasx.parser.ast.Ast.JasxStringLiteral;
import jasx.parser.ast.Ast.JasxText;
import jasx.parser.ast.Ast.JasxStyleBlock;
import jasx.parser.ast.Ast.JavaCode;
import jasx.parser.ast.Ast.JavaExpression;
import jasx.parser.ast.Ast.JavaStatement;
import jasx.parser.ast.Ast.Node;

/**
 * Serializes ASTs (jasx-spec/ast.md §8) and diagnostics (jasx-spec/diagnostics.md §1) to JSON. Fields
 * are emitted in the order {@code type, range, loc}, then the node's fields in the order of ast.md.
 */
public final class AstJson {
    private final JsonWriter w = new JsonWriter();
    private final LineMap lines;

    private AstJson(String source) {
        this.lines = new LineMap(source);
    }

    public static String toJson(JasxCompilationUnit unit, String source) {
        AstJson json = new AstJson(source);
        json.node(unit);
        return json.w.toString();
    }

    public static String diagnosticsToJson(List<Diagnostic> diagnostics, String source) {
        AstJson json = new AstJson(source);
        json.w.beginArray();
        for (Diagnostic d : diagnostics) {
            json.w.beginObject();
            json.w.name("code").value(d.code());
            json.w.name("severity").value(d.severity().jsonName());
            json.w.name("message").value(d.message());
            json.location(d.start(), d.end());
            if (!d.related().isEmpty()) {
                json.w.name("related").beginArray();
                for (Diagnostic.Related r : d.related()) {
                    json.w.beginObject();
                    json.w.name("message").value(r.message());
                    json.location(r.start(), r.end());
                    json.w.endObject();
                }
                json.w.endArray();
            }
            json.w.endObject();
        }
        json.w.endArray();
        return json.w.toString();
    }

    private void node(Node n) {
        if (n == null) {
            w.nullValue();
            return;
        }
        w.beginObject();
        w.name("type").value(n.getClass().getSimpleName());
        location(n.start(), n.end());
        switch (n) {
            case JasxCompilationUnit u -> {
                w.name("specVersion").value(JasxParser.SPEC_VERSION);
                w.name("sourceFile").value(u.sourceFile());
                nodes("segments", u.segments());
            }
            case JavaCode j -> w.name("source").value(j.source());
            case JasxStyleBlock b -> w.name("css").value(b.css());
            case JavaExpression e -> nodes("segments", e.segments());
            case JasxEmptyExpression e -> w.name("source").value(e.source());
            case JasxElement e -> {
                field("openingElement", e.openingElement());
                nodes("children", e.children());
                field("closingElement", e.closingElement());
            }
            case JasxOpeningElement o -> {
                field("name", o.name());
                nodes("attributes", o.attributes());
                w.name("selfClosing").value(o.selfClosing());
            }
            case JasxClosingElement c -> field("name", c.name());
            case JasxFragment f -> {
                field("openingFragment", f.openingFragment());
                nodes("children", f.children());
                field("closingFragment", f.closingFragment());
            }
            case JasxOpeningFragment o -> {}
            case JasxClosingFragment c -> {}
            case JasxIdentifier i -> w.name("name").value(i.name());
            case JasxQualifiedName q -> {
                field("qualifier", q.qualifier());
                field("name", q.name());
            }
            case JasxNamespacedName ns -> {
                field("namespace", ns.namespace());
                field("name", ns.name());
            }
            case JasxAttribute a -> {
                field("name", a.name());
                field("value", a.value());
            }
            case JasxStringLiteral s -> {
                w.name("value").value(s.value());
                w.name("raw").value(s.raw());
            }
            case JasxText t -> {
                w.name("value").value(t.value());
                w.name("raw").value(t.raw());
            }
            case JasxExpressionContainer c -> field("expression", c.expression());
            case JasxStatementContainer c -> field("statement", c.statement());
            case JavaStatement st -> nodes("segments", st.segments());
            case JasxMarkupStatement m -> field("markup", m.markup());
        }
        w.endObject();
    }

    private void field(String name, Node value) {
        w.name(name);
        node(value);
    }

    private void nodes(String name, List<? extends Node> values) {
        w.name(name).beginArray();
        values.forEach(this::node);
        w.endArray();
    }

    private void location(int start, int end) {
        w.name("range").beginArray().value(start).value(end).endArray();
        w.name("loc").beginObject();
        position("start", start);
        position("end", end);
        w.endObject();
    }

    private void position(String name, int offset) {
        LineMap.Position p = lines.position(offset);
        w.name(name).beginObject().name("line").value(p.line()).name("column").value(p.column()).endObject();
    }
}
