package testtarget;

import jasx.compiler.FactoryLowering;
import jasx.compiler.LoweringOutput;
import jasx.compiler.MarkupTree;
import jasx.compiler.Target;

/**
 * A custom lowering for the compiler's tests (semantics.md §7.4), written the way a third-party target would
 * write one: it extends factory lowering and passes every dynamic child and dynamic attribute as a function,
 * as a reactive target could. {@link Functions} calls the functions at once and brackets the result, so the
 * tests can see which positions went through it.
 *
 * <p>It is outside the {@code jasx.compiler} package on purpose: the tests load it through a
 * {@link jasx.compiler.TargetClassLoader}, as a build loads a target's lowering from the project's classpath.
 */
public class FunctionLowering extends FactoryLowering {
    public FunctionLowering(Target target) {
        super(target);
    }

    /** {@code {expression}}  →  {@code testtarget.Functions.dynamic(() -> (expression))} */
    @Override
    protected void dynamicChild(MarkupTree.Dynamic child, LoweringOutput out) {
        out.write("testtarget.Functions.dynamic(() -> (", child.start());
        out.code(child.expression());
        out.write("))", child.end() - 1);
    }

    /** {@code <Card />}  →  {@code testtarget.Functions.component(() -> app.T.Card())} (semantics.md §5.6) */
    @Override
    public void component(MarkupTree.ComponentCall call, LoweringOutput out) {
        out.write("testtarget.Functions.component(() -> ", call.start());
        out.call(call);
        out.write(")", call.closeAnchor());
    }

    /** {@code title={expression}}  →  {@code .title(testtarget.Functions.attribute(() -> (expression)))} */
    @Override
    protected void attributeValue(MarkupTree.Attribute attribute, LoweringOutput out) {
        if (attribute.kind() != MarkupTree.AttributeKind.DYNAMIC) {
            super.attributeValue(attribute, out);
            return;
        }
        out.write("testtarget.Functions.attribute(() -> ", attribute.start());
        out.value(attribute.value());
        out.write(")", attribute.end());
    }
}
