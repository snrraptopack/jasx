package jasx.ui;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Fills a record from the fields of a submitted form: {@link FormEvent#as} (jasx-spec/ui.md §4.2).
 *
 * <p>Each record component is read from the field of the same name and converted to the component's type. The
 * rules are deliberately few: a value that can't be converted is an error naming the field, and validation
 * beyond types belongs to the application (or the record's compact constructor).
 */
final class FormBinding {
    private FormBinding() {}

    static <T extends Record> T bind(FormEvent form, Class<T> type) {
        RecordComponent[] components = type.getRecordComponents();
        Object[] arguments = new Object[components.length];
        Class<?>[] parameterTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            parameterTypes[i] = component.getType();
            arguments[i] = componentValue(form, component);
        }
        return construct(type, parameterTypes, arguments);
    }

    private static Object componentValue(FormEvent form, RecordComponent component) {
        String name = component.getName();
        Class<?> type = component.getType();
        if (type == List.class) {
            Class<?> elementType = listElementType(component.getGenericType(), name);
            List<Object> values = new ArrayList<>();
            for (String value : form.fields(name)) {
                values.add(convert(name, value, elementType));
            }
            return List.copyOf(values);
        }
        if (type == boolean.class || type == Boolean.class) {
            // A checkbox sends its value only when it is checked (ui.md §4.2).
            boolean present = form.fieldNames().contains(name);
            return present && !form.field(name).equalsIgnoreCase("false");
        }
        return convert(name, form.field(name), type);
    }

    /** The {@code T} of a {@code List<T>} component; a raw list holds strings. */
    private static Class<?> listElementType(Type genericType, String name) {
        if (!(genericType instanceof ParameterizedType parameterized)) {
            return String.class;
        }
        Type argument = parameterized.getActualTypeArguments()[0];
        if (argument instanceof Class<?> elementClass) {
            return elementClass;
        }
        throw new IllegalArgumentException("field \"" + name + "\": unsupported list type " + genericType);
    }

    /** One value converted to {@code type}. An empty value is "" for a String and null for other objects. */
    private static Object convert(String name, String value, Class<?> type) {
        if (type == String.class) {
            return value;
        }
        if (value.isBlank()) {
            if (type.isPrimitive()) {
                throw new IllegalArgumentException("field \"" + name + "\" is empty, but a " + type + " is needed");
            }
            return null;
        }
        String text = value.strip();
        try {
            return parse(type, text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "field \"" + name + "\": \"" + value + "\" is not a valid " + type.getSimpleName(), e);
        }
    }

    private static Object parse(Class<?> type, String text) {
        if (type.isEnum()) {
            return enumConstant(type, text);
        }
        Function<String, Object> parser = parser(type);
        if (parser == null) {
            throw new IllegalArgumentException("unsupported type " + type.getName());
        }
        return parser.apply(text);
    }

    private static Function<String, Object> parser(Class<?> type) {
        if (type == int.class || type == Integer.class) return Integer::valueOf;
        if (type == long.class || type == Long.class) return Long::valueOf;
        if (type == double.class || type == Double.class) return Double::valueOf;
        if (type == float.class || type == Float.class) return Float::valueOf;
        if (type == short.class || type == Short.class) return Short::valueOf;
        if (type == byte.class || type == Byte.class) return Byte::valueOf;
        if (type == char.class || type == Character.class) return FormBinding::singleCharacter;
        if (type == BigDecimal.class) return BigDecimal::new;
        if (type == BigInteger.class) return BigInteger::new;
        if (type == LocalDate.class) return LocalDate::parse;
        if (type == LocalTime.class) return LocalTime::parse;
        if (type == LocalDateTime.class) return LocalDateTime::parse; // what <input type="datetime-local"> sends
        if (type == UUID.class) return UUID::fromString;
        return null;
    }

    private static Object singleCharacter(String text) {
        if (text.length() != 1) {
            throw new IllegalArgumentException("not one character");
        }
        return text.charAt(0);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> type, String text) {
        return Enum.valueOf((Class<? extends Enum>) type, text);
    }

    private static <T> T construct(Class<T> type, Class<?>[] parameterTypes, Object[] arguments) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true); // a record declared in a component's file needn't be public
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException e) {
            // The record's own validation failed: let its exception through.
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalArgumentException("can't create " + type.getName(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("can't create " + type.getName(), e);
        }
    }
}
