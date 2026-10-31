package jasx.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jasx.compiler.ComponentIndex.Children;
import jasx.compiler.ComponentIndex.Component;
import jasx.compiler.ComponentIndex.Prop;
import jasx.compiler.ComponentIndex.PropKind;
import jasx.parser.Diagnostic;
import jasx.parser.JasxParser;
import jasx.parser.LineMap;
import jasx.parser.ast.Ast.Child;
import jasx.parser.ast.Ast.ElementName;
import jasx.parser.ast.Ast.JasxAttribute;
import jasx.parser.ast.Ast.JasxCompilationUnit;
import jasx.parser.ast.Ast.JasxElement;
import jasx.parser.ast.Ast.JasxEmptyExpression;
import jasx.parser.ast.Ast.JasxExpressionContainer;
import jasx.parser.ast.Ast.JasxFragment;
import jasx.parser.ast.Ast.JasxIdentifier;
import jasx.parser.ast.Ast.JasxNamespacedName;
import jasx.parser.ast.Ast.JasxStringLiteral;
import jasx.parser.ast.Ast.JasxText;
import jasx.parser.ast.Ast.JavaCode;
import jasx.parser.ast.Ast.JavaExpression;
import jasx.parser.ast.Ast.Markup;
import jasx.parser.ast.Ast.Segment;

/**
 * Factory lowering of one {@code .jasx} file (semantics.md §6): every markup segment becomes calls on the
 * target's factory and direct component calls; Java code is copied byte for byte.
 */
final class Lowering {
    private static final String OBJECT_CAST = "(java.lang.Object) ";
    private static final String ELEMENT = "jasx.runtime.Element";

    /** A normalized child value (semantics.md §3). */
    private sealed interface ChildValue {
        int start();

        int end();
    }

    private record TextValue(String text, int start, int end) implements ChildValue {}

    private record ExpressionValue(JasxExpressionContainer container) implements ChildValue {
        public int start() {
            return container.start();
        }

        public int end() {
            return container.end();
        }
    }

    private record MarkupValue(Markup markup) implements ChildValue {
        public int start() {
            return markup.start();
        }

        public int end() {
            return markup.end();
        }
    }

    private final SourceFile file;
    private final String src;
    private final Target target;
    private final Schema schema;
    private final String factory;
    private final Resolver resolver;
    private final Emitter out;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    Lowering(SourceFile file, LineMap lines, Target target, Resolver resolver) {
        this.file = file;
        this.src = file.content();
        this.target = target;
        this.schema = target.schema();
        this.factory = target.factory();
        this.resolver = resolver;
        this.out = new Emitter(src, lines);
    }

    List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    /** Lowers {@code unit}, inserting {@code @jasx.Component} at each offset in {@code annotations} (sorted). */
    GeneratedFile lower(JasxCompilationUnit unit, List<Integer> annotations) {
        int next = 0;
        for (Segment segment : unit.segments()) {
            if (segment instanceof JavaCode) {
                int at = segment.start();
                while (next < annotations.size() && annotations.get(next) < segment.end()) {
                    int insert = annotations.get(next++);
                    if (insert >= at) {
                        out.copy(at, insert);
                        out.emit("@jasx.Component ", insert);
                        at = insert;
                    }
                }
                out.copy(at, segment.end());
            } else {
                markup((Markup) segment);
            }
        }
        return new GeneratedFile(file, file.javaPath(), out.text(), out.spans());
    }

    private void markup(Markup markup) {
        switch (markup) {
            case JasxFragment f -> {
                out.emit(factory + ".fragment(", f.start());
                List<ChildValue> children = normalize(f.children());
                for (int i = 0; i < children.size(); i++) {
                    if (i > 0) {
                        out.emit(", ", children.get(i).start());
                    }
                    child(children.get(i));
                }
                out.emit(")", f.closingFragment() != null ? f.closingFragment().start() : f.end());
            }
            case JasxElement e -> {
                JasxAttribute key = null;
                List<JasxAttribute> attributes = new ArrayList<>();
                for (JasxAttribute a : e.openingElement().attributes()) {
                    if (a.name() instanceof JasxIdentifier id && id.name().equals("key")) {
                        key = a;
                    } else {
                        attributes.add(a);
                    }
                }
                if (key != null) {
                    out.emit(factory + ".keyed(", e.start());
                    keyValue(key);
                    out.emit(", ", e.start());
                }
                if (isIntrinsic(e.openingElement().name())) {
                    intrinsic(e, attributes);
                } else {
                    component(e, attributes);
                }
                if (key != null) {
                    out.emit(")", closingAnchor(e));
                }
            }
        }
    }

    /** semantics.md §2: a lowercase identifier, or one containing '-', names an intrinsic element. */
    private static boolean isIntrinsic(ElementName name) {
        return name instanceof JasxIdentifier id
                && (id.name().contains("-") || Character.isLowerCase(id.name().charAt(0)));
    }

    private void intrinsic(JasxElement e, List<JasxAttribute> attributes) {
        JasxIdentifier name = (JasxIdentifier) e.openingElement().name();
        String tag = name.name();
        Schema.ElementSpec spec = schema.element(tag);
        boolean custom = spec == null && tag.contains("-") && schema.customElements();
        if (spec == null && !custom) {
            error("JASX2105", "<" + tag + "> is not an element of target '" + target.id() + "'", name.start(), name.end());
        }
        List<ChildValue> children = normalize(e.children());
        if (spec != null && spec.isVoid() && !children.isEmpty()) {
            error("JASX2102", "<" + tag + "> is a void element and cannot have children",
                    children.get(0).start(), children.get(0).end());
        }
        out.emit(factory + ".h(" + javaString(tag) + ", ", e.start());
        if (attributes.isEmpty()) {
            out.emit("null", e.start());
        } else {
            String builder = spec == null ? "Custom" : Schema.pascalCase(tag);
            out.emit(target.attributesPackage() + "." + builder + ".create()", e.start());
            for (JasxAttribute a : attributes) {
                attributeCall(tag, spec == null, a);
            }
        }
        for (ChildValue c : children) {
            out.emit(", ", c.start());
            child(c);
        }
        out.emit(")", closingAnchor(e));
    }

    private void attributeCall(String tag, boolean byNameOnly, JasxAttribute a) {
        String name = JasxParser.attributeNameText(a.name());
        boolean byName = byNameOnly || a.name() instanceof JasxNamespacedName || schema.matchesPattern(name);
        String method = "attr";
        if (!byName) {
            Schema.AttributeSpec spec = schema.attribute(tag, name);
            if (spec == null) {
                error("JASX2101", "<" + tag + "> has no attribute '" + name + "' on target '" + target.id() + "'",
                        a.start(), a.end());
                byName = true;
            } else {
                method = spec.method();
                if (spec.event() && !target.javaEventHandlers() && a.value() instanceof JasxExpressionContainer v) {
                    error("JASX2103", "event attribute '" + name + "' accepts only a string on target '" + target.id()
                            + "', which has no Java event handlers", v.start(), v.end());
                }
            }
        }
        out.emit("." + method + "(" + (byName ? javaString(name) + ", " : ""), a.start());
        attributeValue(a);
        out.emit(")", a.end());
    }

    private void attributeValue(JasxAttribute a) {
        switch (a.value()) {
            case null -> out.emit("true", a.start());
            case JasxStringLiteral s -> out.emit(javaString(s.value()), s.start());
            case JasxExpressionContainer c -> {
                if (c.expression() instanceof JavaExpression expression) {
                    out.emit("(", c.start());
                    expression(expression);
                    out.emit(")", c.end() - 1);
                } else {
                    out.emit("null", c.start());
                }
            }
            case Markup m -> markup(m);
        }
    }

    private void keyValue(JasxAttribute key) {
        if (key.value() instanceof JasxStringLiteral s) {
            out.emit(javaString(s.value()), s.start());
        } else if (key.value() instanceof JasxExpressionContainer c && c.expression() instanceof JavaExpression e) {
            out.emit("(", c.start());
            expression(e);
            out.emit(")", c.end() - 1);
        } else {
            error("JASX2009", "a key must be a string literal or an expression", key.start(), key.end());
            out.emit("null", key.start());
        }
    }

    private void component(JasxElement e, List<JasxAttribute> attributes) {
        Resolver.Resolution resolution = resolver.resolve(e.openingElement().name(), e.start());
        if (resolution.error() != null) {
            diagnostics.add(resolution.error());
            out.emit("null", e.start());
            return;
        }
        Component c = resolution.component();
        String tag = "<" + JasxParser.nameText(e.openingElement().name()) + ">";
        Map<String, JasxAttribute> given = new LinkedHashMap<>();
        for (JasxAttribute a : attributes) {
            String name = JasxParser.attributeNameText(a.name());
            if (name.equals("children") && c.children() != Children.NONE) {
                error("JASX2008", "pass the children of " + tag + " as child content, not as an attribute",
                        a.start(), a.end());
            } else if (!(a.name() instanceof JasxIdentifier) || c.prop(name) == null) {
                error("JASX2005", tag + " has no prop '" + name + "'", a.start(), a.end());
            } else {
                given.put(name, a);
            }
        }
        List<ChildValue> children = normalize(e.children());
        if (c.children() == Children.NONE && !children.isEmpty()) {
            error("JASX2003", tag + " takes no children", children.get(0).start(), children.get(0).end());
        } else if (c.children() == Children.SINGLE && children.size() != 1) {
            error("JASX2004", tag + " takes exactly one child, but has " + children.size(), e.start(), e.end());
        }
        for (Prop p : c.params()) {
            if (p.kind() == PropKind.REQUIRED && !given.containsKey(p.name())) {
                error("JASX2007", tag + " is missing the required prop '" + p.name() + "'",
                        e.openingElement().start(), e.openingElement().end());
            }
        }
        out.emit(c.className() + "." + c.method() + "(", e.start());
        for (int i = 0; i < c.params().size(); i++) {
            Prop p = c.params().get(i);
            if (i > 0) {
                out.emit(", ", e.start());
            }
            JasxAttribute a = given.get(p.name());
            if (p.kind() == PropKind.CHILDREN) {
                componentChildren(c.children(), children, e);
            } else if (a != null) {
                attributeValue(a);
            } else if (p.kind() == PropKind.DEFAULT) {
                out.emit(p.defaultValue(), e.start());
            } else {
                out.emit(absentValue(p.type()), e.start());
            }
        }
        out.emit(")", closingAnchor(e));
    }

    private void componentChildren(Children kind, List<ChildValue> children, JasxElement e) {
        if (kind == Children.SINGLE) {
            if (children.size() == 1) {
                asElement(children.get(0));
            } else {
                out.emit("null", e.start());
            }
            return;
        }
        out.emit("java.util.List.<" + ELEMENT + ">of(", e.start());
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                out.emit(", ", children.get(i).start());
            }
            asElement(children.get(i));
        }
        out.emit(")", closingAnchor(e));
    }

    /** A child value as an Element: text through {@code text}, an expression through {@code fragment}. */
    private void asElement(ChildValue c) {
        switch (c) {
            case TextValue t -> out.emit(factory + ".text(" + javaString(t.text()) + ")", t.start());
            case MarkupValue m -> markup(m.markup());
            case ExpressionValue v -> {
                out.emit(factory + ".fragment(" + OBJECT_CAST + "(", v.start());
                expression((JavaExpression) v.container().expression());
                out.emit("))", v.end() - 1);
            }
        }
    }

    /** A child of an intrinsic element or fragment: one variadic argument (semantics.md §6.7). */
    private void child(ChildValue c) {
        switch (c) {
            case TextValue t -> out.emit(javaString(t.text()), t.start());
            case MarkupValue m -> markup(m.markup());
            case ExpressionValue v -> {
                out.emit(OBJECT_CAST + "(", v.start());
                expression((JavaExpression) v.container().expression());
                out.emit(")", v.end() - 1);
            }
        }
    }

    private void expression(JavaExpression expression) {
        for (Segment s : expression.segments()) {
            if (s instanceof JavaCode) {
                out.copy(s.start(), s.end());
            } else {
                markup((Markup) s);
            }
        }
    }

    /** Children normalization (semantics.md §3.1–§3.3). */
    private List<ChildValue> normalize(List<Child> children) {
        List<ChildValue> values = new ArrayList<>();
        for (Child c : children) {
            switch (c) {
                case JasxText t -> {
                    String text = normalizeText(t.value());
                    if (text != null) {
                        values.add(new TextValue(text, firstNonWhitespace(t.start(), t.end()), t.end()));
                    }
                }
                case JasxExpressionContainer container -> {
                    if (!(container.expression() instanceof JasxEmptyExpression)) {
                        values.add(new ExpressionValue(container));
                    }
                }
                case Markup m -> values.add(new MarkupValue(m));
            }
        }
        return values;
    }

    /** The JSX text algorithm (semantics.md §3.1); null if the text is removed. */
    static String normalizeText(String value) {
        String[] lines = value.split("\r\n|\r|\n", -1);
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) {
                line = stripLeading(line);
            }
            if (i < lines.length - 1) {
                line = stripTrailing(line);
            }
            if (!line.isEmpty()) {
                kept.add(line);
            }
        }
        return kept.isEmpty() ? null : String.join(" ", kept);
    }

    private static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return s.substring(i);
    }

    private static String stripTrailing(String s) {
        int i = s.length();
        while (i > 0 && (s.charAt(i - 1) == ' ' || s.charAt(i - 1) == '\t')) {
            i--;
        }
        return s.substring(0, i);
    }

    private int firstNonWhitespace(int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(src.charAt(i))) {
                return i;
            }
        }
        return start;
    }

    private static int closingAnchor(JasxElement e) {
        return e.closingElement() != null ? e.closingElement().start() : Math.max(e.start(), e.end() - 1);
    }

    /** The argument for an absent optional prop: null, or the default value of a primitive type. */
    private static String absentValue(String type) {
        return switch (type) {
            case "boolean" -> "false";
            case "char" -> "'\\0'";
            case "byte" -> "(byte) 0";
            case "short" -> "(short) 0";
            case "int" -> "0";
            case "long" -> "0L";
            case "float" -> "0.0f";
            case "double" -> "0.0";
            default -> "null";
        };
    }

    /** A Java string literal for {@code value}. */
    static String javaString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\%03o", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private void error(String code, String message, int start, int end) {
        diagnostics.add(Diagnostic.error(code, message, start, end));
    }
}
