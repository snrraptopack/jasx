package testtarget;

import java.util.function.Supplier;

/** The runtime side of {@link FunctionLowering}: calls each function at once and brackets its value. */
public final class Functions {
    private Functions() {}

    /** A dynamic child, passed as a function. */
    public static Object dynamic(Supplier<Object> value) {
        return "[" + value.get() + "]";
    }

    /** A component call, passed as a function: called at once, its element unchanged. */
    public static jasx.runtime.Element component(Supplier<jasx.runtime.Element> call) {
        return call.get();
    }

    /** A dynamic attribute value, passed as a function. */
    public static Object attribute(Supplier<Object> value) {
        return "[" + value.get() + "]";
    }
}
