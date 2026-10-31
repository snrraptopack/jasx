package jasx.compiler;

/** The Java source generated for one {@code .jasx} file, with the span map back to it. */
public record GeneratedFile(SourceFile source, String javaPath, String content, SpanMap spanMap) {}
