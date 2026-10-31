package jasx.compiler;

import jasx.parser.Diagnostic;
import jasx.parser.LineMap;

/** A diagnostic in a source file. javac diagnostics remapped to {@code .jasx} files have the code {@code javac}. */
public record CompilerDiagnostic(String path, Diagnostic diagnostic, LineMap lines) {

    public String code() {
        return diagnostic.code();
    }

    public boolean isError() {
        return diagnostic.severity() == Diagnostic.Severity.ERROR;
    }

    /** The console form (diagnostics.md §1); javac diagnostics carry the {@code javac:} prefix instead of a code. */
    public String format() {
        LineMap.Position p = lines.position(diagnostic.start());
        String location = path + ":" + p.line() + ":" + (p.column() + 1) + ": " + diagnostic.severity().jsonName() + ": ";
        return diagnostic.code().equals(JavacRunner.CODE)
                ? location + "javac: " + diagnostic.message()
                : location + diagnostic.message() + " [" + diagnostic.code() + "]";
    }

    @Override
    public String toString() {
        return format();
    }
}
