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
 *   &lt;li key={k}&gt;...&lt;/li&gt;       →  jasx.web.Web.keyed((k), () -> jasx.web.Web.h("li", ...))
 *   &lt;Card title="t" /&gt;      →  jasx.web.Web.component(() -> app.Ui.Card("t"))
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

    /**
     * {@code <li key={id}>...</li>}  →  {@code jasx.web.Web.keyed((id), () -> jasx.web.Web.h("li", ...))}: the
     * element is a function, so that a list can keep an unchanged item without running its code again, which
     * would create its components afresh (targets/web.md §3, §4.3).
     */
    @Override
    public void keyed(MarkupTree.Keyed keyed, LoweringOutput out) {
        out.write("jasx.web.Web.keyed(", keyed.start());
        out.value(keyed.key());
        out.write(", () -> ", keyed.start());
        out.markup(keyed.element());
        out.write(")", keyed.closeAnchor());
    }

    /**
     * {@code <Card title="t" />}  →  {@code jasx.web.Web.component(() -> app.Ui.Card("t"))}: the call (written by
     * the compiler, semantics.md §5.6) is a function, so that a hole running again can keep the component
     * instance with its state instead of calling the component again (targets/web.md §3, §4.4).
     */
    @Override
    public void component(MarkupTree.ComponentCall call, LoweringOutput out) {
        out.write("jasx.web.Web.component(() -> ", call.start());
        out.call(call);
        out.write(")", call.closeAnchor());
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
