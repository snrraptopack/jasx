package jasx.parser;

import java.util.List;
import jasx.parser.ast.Ast.JasxCompilationUnit;

/** The AST of a compilation unit and the syntax diagnostics reported while parsing it. */
public record ParseResult(JasxCompilationUnit unit, List<Diagnostic> diagnostics) {

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
    }
}
