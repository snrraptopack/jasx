package jasx.web.compiler;

import jasx.compiler.FactoryLowering;
import jasx.compiler.LoweringOutput;
import jasx.compiler.MarkupTree;
import jasx.compiler.Target;

/**
 * The lowering of the web target (targets/web.md §3, semantics.md §7.4): factory lowering, except that every
 * dynamic position becomes a function the runtime can call again when the state it reads changes.
 *
 * <pre>
 *   {expression}            →  jasx.web.Web.dynamic(() -> (expression))
 *   {for (...) { ... }}     →  jasx.web.Web.dynamic(() -> switch (0) { ... })
 *   class={expression}      →  .className(() -> (expression))
 * </pre>
 *
 * <p>Handlers, props and keys are evaluated once, as in factory lowering. The class runs inside the compiler
 * (it is named by the target's descriptor) and is never loaded by applications.
 */
public final class WebLowering extends FactoryLowering {
    public WebLowering(Target target) {
        super(target);
    }

    /** {@code {expression}}  →  {@code jasx.web.Web.dynamic(() -> (expression))} */
    @Override
    protected void dynamicChild(MarkupTree.Dynamic child, LoweringOutput out) {
        out.write("jasx.web.Web.dynamic(() -> (", child.start());
        out.code(child.expression());
        out.write("))", child.end() - 1);
    }

    /** {@code {for ...}}  →  {@code jasx.web.Web.dynamic(() -> switch (0) { ... })} (semantics.md §6.8) */
    @Override
    protected void statementChild(MarkupTree.Statement child, LoweringOutput out) {
        out.write("jasx.web.Web.dynamic(() -> ", child.start());
        out.code(child.value());
        out.write(")", child.end() - 1);
    }

    /** A dynamic attribute {@code a={expression}}  →  {@code .a(() -> (expression))}; others unchanged. */
    @Override
    protected void attributeValue(MarkupTree.Attribute attribute, LoweringOutput out) {
        if (attribute.kind() != MarkupTree.AttributeKind.DYNAMIC) {
            super.attributeValue(attribute, out);
            return;
        }
        out.write("() -> ", attribute.start());
        out.value(attribute.value());
    }
}
