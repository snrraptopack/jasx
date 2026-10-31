package jasx.html;

/**
 * The style sheet of a {@code .jasx} file with a style block (jasx-spec/semantics.md §10, targets/html.md
 * §3.5). The compiler creates one per file, through {@link Html#styleSheet}, with the CSS already scoped.
 *
 * @param scope the scope name, such as {@code jx-1a2b3c4d}: the CSS class the file's elements get
 * @param css the scoped CSS, whose selectors only match elements with that class
 */
public record StyleSheet(String scope, String css) {}
