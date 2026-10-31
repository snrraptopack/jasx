package jasx.compiler;

import java.util.List;

/**
 * The result of compiling a set of sources. When there are errors, files with errors are not lowered,
 * so {@link #files()} may be incomplete.
 */
public record CompilationResult(List<GeneratedFile> files, List<CompilerDiagnostic> diagnostics, ComponentIndex index) {

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(CompilerDiagnostic::isError);
    }
}
