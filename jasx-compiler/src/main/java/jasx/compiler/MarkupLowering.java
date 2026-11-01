package jasx.compiler;

/**
 * A target's lowering: how each markup expression becomes Java (semantics.md §6, §7.4). This is the
 * interface of the reference implementation that §7.4 leaves to it.
 *
 * <p>The compiler does everything around it: it parses, normalizes children, resolves and checks components,
 * checks attributes against the target's schema, reports every JASX error, copies the Java code, writes
 * component calls and statement containers, and maps javac's diagnostics back to the {@code .jasx} file. A
 * lowering only decides what intrinsic elements, fragments, keys and children become, and what surrounds a
 * component call, by writing Java text around the pieces of the {@link MarkupTree}. Because the user's code
 * reaches the output only as {@link MarkupTree.Code} that the lowering places but can't change, and component
 * calls only through {@link LoweringOutput#call}, the requirements of §7.4 (Java code unchanged, components
 * called as §5.6 says, line preservation) hold for every lowering.
 *
 * <p>The default is {@link FactoryLowering}, which writes {@code F.h(...)} calls on the target's factory. A
 * target that only wants to change some positions (say, pass dynamic children as functions) extends it and
 * overrides those methods; a target that wants to generate something else entirely implements this interface.
 *
 * <p>A target names its lowering class in its descriptor ({@code "lowering": "com.acme.AcmeLowering"},
 * semantics.md §7.2). The class must have a public constructor that takes the {@link Target}. The compiler
 * creates one instance per {@code .jasx} file, so an instance may keep state for the file it lowers (such as
 * a counter for unique names). The class runs inside the compiler, so it belongs in the target's artifact with
 * the descriptor, and it must not bundle the compiler's own classes: the build gives it the compiler that loads
 * it ({@link TargetClassLoader}).
 */
public interface MarkupLowering {
    /** Writes an intrinsic element, such as {@code <div class="card">...</div>}. */
    void intrinsic(MarkupTree.Intrinsic element, LoweringOutput out);

    /** Writes a fragment {@code <>...</>}. */
    void fragment(MarkupTree.Fragment fragment, LoweringOutput out);

    /** Writes an element with a {@code key} (semantics.md §6.4); write its element with {@code out.markup}. */
    void keyed(MarkupTree.Keyed keyed, LoweringOutput out);

    /**
     * Writes one child of a component as a {@code jasx.runtime.Element}, for the component's
     * {@code children} parameter (semantics.md §5.5). The child must keep its meaning under the child value
     * contract (§3.4): for example an expression that is null or a list.
     */
    void componentChild(MarkupTree.Child child, LoweringOutput out);

    /**
     * Writes a component element. The call itself (semantics.md §5.6) is always written by the compiler, when
     * the lowering calls {@link LoweringOutput#call} exactly once; a lowering may write text around it, for
     * example to pass the call as a function that creates a component instance (§5.6). The default writes the
     * call alone.
     */
    default void component(MarkupTree.ComponentCall call, LoweringOutput out) {
        out.call(call);
    }
}
