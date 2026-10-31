package jasx.compiler;

/** A source file given to the compiler: a {@code .jasx} file, or a {@code .java} file to index components from. */
public record SourceFile(String path, String content) {

    public boolean isJasx() {
        return path.endsWith(".jasx");
    }

    /** The file name without its directory and extension: {@code a/B.jasx} gives {@code B}. */
    public String baseName() {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** The path of the generated Java file: {@code a/B.jasx} becomes {@code a/B.java}. */
    public String javaPath() {
        return isJasx() ? path.substring(0, path.length() - ".jasx".length()) + ".java" : path;
    }
}
