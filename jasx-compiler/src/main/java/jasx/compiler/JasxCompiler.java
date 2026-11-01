package jasx.compiler;

import java.util.ArrayList;
import java.util.List;
import jasx.parser.Diagnostic;
import jasx.parser.JasxParser;
import jasx.parser.LineMap;
import jasx.parser.ParseResult;
import jasx.parser.ast.Ast.JasxStyleBlock;
import jasx.parser.ast.Ast.Markup;
import jasx.parser.ast.Ast.Segment;

/**
 * The JASX compiler: turns {@code .jasx} source files into plain Java source files.
 *
 * <p>A compilation runs in two passes over all the files:
 *
 * <ol>
 *   <li><b>Scan.</b> Each {@code .jasx} file is parsed, and every file ({@code .jasx} and {@code .java}) is
 *       scanned for its package, imports, classes and method headers. The components found are added to
 *       one shared component index.
 *   <li><b>Lower.</b> Each {@code .jasx} file is rewritten to Java: Java code is copied unchanged, and each
 *       markup expression becomes calls on the target's factory and direct component calls.
 * </ol>
 *
 * <p>All files must be scanned before any is lowered, because markup in one file can use a component
 * declared in another file.
 *
 * <p>A {@code .jasx} file with fields or methods at its top level is also wrapped in its <em>file class</em>,
 * named after the file (semantics.md §9, {@link FileClass}). The CSS of its style blocks is scoped to the file and
 * attached to its elements (semantics.md §10, {@link StyleBlocks}).
 *
 * <p>{@code .java} files are only scanned (for {@code @jasx.Component} methods). They are never rewritten;
 * the build compiles them as they are.
 *
 * <p>See jasx-spec/semantics.md §5 (components), §6 (lowering), §9 (file classes) and §10 (style blocks).
 */
public final class JasxCompiler {
    private final Target target;

    /** Components published by the classpath (jars), used when a name isn't declared in the sources. */
    private final ComponentIndex dependencies;

    /**
     * @param target the target that gives markup its meaning, usually {@link Target#load}
     * @param dependencies the components of the classpath, usually {@link ComponentIndex#fromClasspath}
     */
    public JasxCompiler(Target target, ComponentIndex dependencies) {
        this.target = target;
        this.dependencies = dependencies;
    }

    /** A compiler without dependencies: only components declared in the compiled sources are known. */
    public JasxCompiler(Target target) {
        this(target, new ComponentIndex());
    }

    /** What the scan pass learned about one source file, kept for the lowering pass. */
    private record ScannedFile(
            SourceFile file,
            LineMap lines,
            /** The parse result, or null for a {@code .java} file. */
            ParseResult parsed,
            JavaStructure structure,
            /** Text to insert into the Java code: {@code @jasx.Component}, and the file class (§5.1, §9.4). */
            List<Lowering.Insertion> insertions,
            /** The file's style sheet, or null if it has no style block (semantics.md §10). */
            StyleBlocks.Plan styles,
            /** True if the file can't be lowered because of an error found while scanning it. */
            boolean unlowerable) {}

    /** Compiles {@code sources}. Diagnostics of every file are collected; nothing is thrown for errors. */
    public CompilationResult compile(List<SourceFile> sources) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();

        // Pass 1: scan every file and collect the components it declares.
        ComponentIndex localComponents = new ComponentIndex();
        List<CompilationResult.Declaration> declarations = new ArrayList<>();
        List<ScannedFile> scannedFiles = new ArrayList<>();
        for (SourceFile file : sources) {
            scannedFiles.add(scan(file, localComponents, declarations, diagnostics));
        }

        // A component declared in the sources wins over a dependency's component with the same class and name.
        ComponentIndex allComponents = localComponents.withFallback(dependencies);

        // Pass 2: lower each .jasx file to Java.
        List<GeneratedFile> generatedFiles = new ArrayList<>();
        List<CompilationResult.Use> uses = new ArrayList<>();
        List<CompilationResult.PropUse> props = new ArrayList<>();
        for (ScannedFile scanned : scannedFiles) {
            GeneratedFile generated = lower(scanned, allComponents, uses, props, diagnostics);
            if (generated != null) {
                generatedFiles.add(generated);
            }
        }
        return new CompilationResult(generatedFiles, diagnostics, localComponents, declarations, uses, props);
    }

    // ---------------------------------------------------------------------------------------------
    // Pass 1: scan
    // ---------------------------------------------------------------------------------------------

    /**
     * Parses (if {@code .jasx}) and scans one file, adding its components to {@code components} and where
     * they are declared to {@code declarations}.
     */
    private ScannedFile scan(SourceFile file, ComponentIndex components,
            List<CompilationResult.Declaration> declarations, List<CompilerDiagnostic> diagnostics) {
        LineMap lines = new LineMap(file.content());

        // Only .jasx files contain markup, so only they are parsed.
        ParseResult parsed = null;
        List<JavaStructure.Opaque> opaque = List.of();
        if (file.isJasx()) {
            parsed = JasxParser.parse(file.content(), file.path());
            report(diagnostics, file, lines, parsed.diagnostics());
            opaque = opaqueRanges(parsed);
        }

        // Read the declarations. The scanner skips markup, so braces in markup can't confuse it. Only a
        // .jasx file can have a file class (semantics.md §9).
        String fileClassName = file.isJasx() ? file.baseName() : null;
        JavaStructure structure = JavaStructure.scan(file.content(), opaque, fileClassName);

        // Find the components and add them to the shared index.
        ComponentScanner.Result found = ComponentScanner.scan(file, structure);
        report(diagnostics, file, lines, found.errors());

        List<Lowering.Insertion> insertions = new ArrayList<>();
        List<Diagnostic> fileClassErrors = FileClass.check(file, structure);
        report(diagnostics, file, lines, fileClassErrors);
        insertions.addAll(FileClass.insertions(file, structure));

        for (ComponentScanner.FoundComponent c : found.components()) {
            boolean added = components.add(c.component());
            if (!added) {
                // Components are resolved by name only, so two with the same name in one class would be
                // ambiguous (semantics.md §5.1). This also catches the same class compiled twice.
                Diagnostic overloaded = Diagnostic.error("JASX2006",
                        "component " + c.component().method() + " is already declared in "
                                + c.component().className() + "; components cannot be overloaded",
                        c.nameStart(), c.nameEnd());
                report(diagnostics, file, lines, List.of(overloaded));
                continue;
            }
            declarations.add(new CompilationResult.Declaration(c.component(), file.path(), c.nameStart(), c.nameEnd()));
            if (c.needsAnnotation()) {
                insertions.add(new Lowering.Insertion(c.headerStart(), Lowering.Insertion.ANNOTATION, "@jasx.Component "));
            }
        }

        // The style blocks, if any: checked and scoped now, so that their errors come with the scan's.
        StyleBlocks.Result styles = parsed != null ? StyleBlocks.check(file, parsed, structure, target) : null;
        List<Diagnostic> styleErrors = styles != null ? styles.errors() : List.of();
        report(diagnostics, file, lines, styleErrors);

        boolean unlowerable = !fileClassErrors.isEmpty() || !styleErrors.isEmpty();
        StyleBlocks.Plan plan = styles != null ? styles.plan() : null;
        if (plan != null) {
            insertions.add(plan.fieldDeclaration(target.factory())); // the style sheet field (semantics.md §10.5)
        }
        return new ScannedFile(file, lines, parsed, structure, insertions, plan, unlowerable);
    }

    /** The markup expressions and style blocks at the top level of a parsed file, which aren't Java. */
    static List<JavaStructure.Opaque> opaqueRanges(ParseResult parsed) {
        List<JavaStructure.Opaque> ranges = new ArrayList<>();
        for (Segment segment : parsed.unit().segments()) {
            if (segment instanceof Markup) {
                ranges.add(new JavaStructure.Opaque(segment.start(), segment.end(), false));
            } else if (segment instanceof JasxStyleBlock) {
                ranges.add(new JavaStructure.Opaque(segment.start(), segment.end(), true));
            }
        }
        return ranges;
    }

    // ---------------------------------------------------------------------------------------------
    // Pass 2: lower
    // ---------------------------------------------------------------------------------------------

    /**
     * Lowers one scanned file; returns null if it is not a {@code .jasx} file or if it has errors. The
     * component tags it resolves are added to {@code uses}, and the props they are given to {@code props}, even
     * when the file has errors.
     */
    private GeneratedFile lower(ScannedFile scanned, ComponentIndex components, List<CompilationResult.Use> uses,
            List<CompilationResult.PropUse> props, List<CompilerDiagnostic> diagnostics) {
        // .java files are not rewritten.
        if (scanned.parsed() == null) {
            return null;
        }
        // A file with syntax errors has no reliable AST to lower. Its errors were reported in pass 1.
        if (scanned.parsed().hasErrors() || scanned.unlowerable()) {
            return null;
        }

        Resolver resolver = new Resolver(scanned.structure(), components);
        Lowering lowering = new Lowering(scanned.file(), scanned.lines(), target, resolver);
        GeneratedFile generated = lowering.lower(scanned.parsed().unit(), scanned.insertions(), scanned.styles());
        report(diagnostics, scanned.file(), scanned.lines(), lowering.diagnostics());
        uses.addAll(lowering.uses());
        props.addAll(lowering.props());

        // Code generated around an error (an unknown component, say) is not meaningful, so don't return it.
        boolean hasErrors = lowering.diagnostics().stream()
                .anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
        return hasErrors ? null : generated;
    }

    private static void report(List<CompilerDiagnostic> into, SourceFile file, LineMap lines, List<Diagnostic> found) {
        for (Diagnostic d : found) {
            into.add(new CompilerDiagnostic(file.path(), d, lines));
        }
    }
}
