package testtarget;

import jasx.compiler.FactoryLowering;
import jasx.compiler.LoweringOutput;
import jasx.compiler.MarkupTree;
import jasx.compiler.Target;

/** Lowerings that break the rules of {@link LoweringOutput}, to test that the compiler catches them. */
public final class BrokenLowerings {
    private BrokenLowerings() {}

    /** Leaves out the user's code of every dynamic child. */
    public static class Dropping extends FactoryLowering {
        public Dropping(Target target) {
            super(target);
        }

        @Override
        protected void dynamicChild(MarkupTree.Dynamic child, LoweringOutput out) {
            out.write("null", child.start());
        }
    }

    /** Writes the user's code of every dynamic child twice. */
    public static class Duplicating extends FactoryLowering {
        public Duplicating(Target target) {
            super(target);
        }

        @Override
        protected void dynamicChild(MarkupTree.Dynamic child, LoweringOutput out) {
            super.dynamicChild(child, out);
            out.write(", ", child.start());
            super.dynamicChild(child, out);
        }
    }

    /** Writes a line break, which would shift the lines of the generated file. */
    public static class LineBreaking extends FactoryLowering {
        public LineBreaking(Target target) {
            super(target);
        }

        @Override
        public void fragment(MarkupTree.Fragment fragment, LoweringOutput out) {
            out.write("\n", fragment.start());
            super.fragment(fragment, out);
        }
    }
}
