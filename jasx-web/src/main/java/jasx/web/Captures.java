package jasx.web;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Whether two keyed items would render the same (targets/web.md §4.3): they come from the same place in the code
 * and use equal values.
 *
 * <p>A keyed item reaches the runtime as a lambda, {@code () -> <li>{todo.title()}</li>}, so what it uses is
 * what the lambda captured ({@code todo}). Two runs of the same lambda expression produce objects of the same
 * class, whose fields are the captured values; they are compared with {@code equals}. So a list item whose
 * record didn't change is kept with its state, and one whose record changed is rendered afresh. State objects
 * are compared by identity, which is right: the same state, still followed by the item's own holes.
 *
 * <p>When the fields can't be read, the items are taken as different: they are rendered afresh, which is always
 * correct, only slower.
 */
final class Captures {
    private Captures() {}

    /** The captured fields of each lambda class, read once. Null for a class whose fields can't be read. */
    private static final ClassValue<List<Field>> FIELDS = new ClassValue<>() {
        @Override
        protected List<Field> computeValue(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            try {
                for (Field field : type.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true);
                        fields.add(field);
                    }
                }
            } catch (RuntimeException e) {
                return null; // inaccessible (a module that doesn't open it, say)
            }
            return List.copyOf(fields);
        }
    };

    /** Whether {@code a} and {@code b} are the same lambda expression with equal captured values. */
    static boolean same(Supplier<?> a, Supplier<?> b) {
        if (a == b) {
            return true;
        }
        Class<?> type = a.getClass();
        if (type != b.getClass()) {
            return false;
        }
        if (!type.isSynthetic() && !type.isHidden()) {
            return a.equals(b); // a supplier the application wrote: its own idea of equality
        }
        List<Field> fields = FIELDS.get(type);
        if (fields == null) {
            return false;
        }
        try {
            for (Field field : fields) {
                if (!Objects.equals(field.get(a), field.get(b))) {
                    return false;
                }
            }
            return true;
        } catch (IllegalAccessException e) {
            return false;
        }
    }
}
