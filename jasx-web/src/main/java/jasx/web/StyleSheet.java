package jasx.web;

/**
 * The style sheet of a {@code .jasx} file with a style block (semantics.md §10, targets/web.md §9). The
 * compiler creates one per file, through {@link Web#styleSheet}, with the CSS already scoped.
 *
 * @param scope the scope name, such as {@code jx-1a2b3c4d}: the CSS class the file's elements get
 * @param css the scoped CSS
 */
public record StyleSheet(String scope, String css) {}
