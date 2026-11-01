package jasx.compiler;

import java.util.List;

/**
 * The result of compiling a set of sources. When there are errors, files with errors are not lowered,
 * so {@link #files()} may be incomplete.
 *
 * <p>Besides the generated files and the diagnostics, the result says where each component of the
 * sources is declared and where components and their props are used in markup. Editor tooling (the language
 * server) uses them for go-to-definition, hover, find references and rename; the build ignores them.
 *
 * @param files the generated Java files
 * @param diagnostics every problem found, in all files
 * @param index the components declared in the sources (not those of dependencies)
 * @param declarations where each component of {@code index} is declared
 * @param uses each component tag in markup that was resolved to a component, opening and closing tags
 *     alike, in files that could be lowered
 * @param props each attribute in markup that gives a component a prop, in files that could be lowered
 */
public record CompilationResult(
        List<GeneratedFile> files,
        List<CompilerDiagnostic> diagnostics,
        ComponentIndex index,
        List<Declaration> declarations,
        List<Use> uses,
        List<PropUse> props) {

    /** A component's method name in its source file: {@code [nameStart, nameEnd)} in {@code path}. */
    public record Declaration(ComponentIndex.Component component, String path, int nameStart, int nameEnd) {}

    /** A component tag's name in markup, {@code <Card>} or {@code </Card>}, and the component it names. */
    public record Use(ComponentIndex.Component component, String path, int nameStart, int nameEnd) {}

    /** An attribute's name in markup that gives {@code component} its prop {@code prop}: {@code title} in {@code <Card title="x">}. */
    public record PropUse(ComponentIndex.Component component, String prop, String path, int nameStart, int nameEnd) {}

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(CompilerDiagnostic::isError);
    }
}
