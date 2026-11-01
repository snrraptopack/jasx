package jasx.ui;

import java.util.List;
import java.util.Set;

/**
 * The submission of a form (jasx-spec/ui.md §4): its fields, read by name, as they are when it is submitted.
 *
 * <pre>{@code
 * record NewTodo(String title, int priority, boolean urgent) {}
 *
 * <form onsubmit={e -> { store.add(e.as(NewTodo.class)); e.resetForm(); }}>
 * }</pre>
 */
public interface FormEvent extends Event {

    /** The first value of field {@code name}, or the empty string if the form has no such value. */
    String field(String name);

    /** Every value of field {@code name}, such as the checked boxes of a group, in form order. */
    List<String> fields(String name);

    /** The names of the fields that have a value, in form order. */
    Set<String> fieldNames();

    /**
     * Clears the form (its fields go back to their initial values) once the event's other changes are applied.
     * Typed text belongs to the user's side, so setting state can't clear it; this asks for it.
     */
    void resetForm();

    /**
     * The fields as a record of type {@code type}: each component is read from the field of the same name
     * (ui.md §4.2). Supported component types are {@code String}, the primitive types and their boxes,
     * {@code BigDecimal}, {@code BigInteger}, enums, {@code LocalDate}, {@code LocalTime},
     * {@code LocalDateTime}, {@code UUID}, and {@code List} of any of these (every value of the field).
     *
     * <p>A {@code boolean} is true when the field has a value other than {@code "false"} (a checked checkbox).
     * An empty field gives {@code ""} for a {@code String}, {@code null} for a boxed or other object type, and
     * an error for a primitive number.
     *
     * @throws IllegalArgumentException naming the field whose value can't be converted
     */
    default <T extends Record> T as(Class<T> type) {
        return FormBinding.bind(this, type);
    }
}
