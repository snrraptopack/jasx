package jasx.web.test;

import jasx.web.test.Dom.Element;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Form fields as a browser sees them: what a field shows (its value apart from its markup, as typing changes it),
 * and what a form submits, in the order and with the rules of the browser's {@code FormData}.
 */
final class Fields {
    private Fields() {}

    /** Input types that a form never submits by themselves (a submit button only as the submitter). */
    private static final Set<String> NOT_SUBMITTED = Set.of("submit", "button", "reset", "image", "file");

    static boolean isField(Element element) {
        return element.tag.equals("input") || element.tag.equals("select") || element.tag.equals("textarea");
    }

    static String type(Element input) {
        String type = input.attribute("type");
        return type == null ? "text" : type.toLowerCase(java.util.Locale.ROOT);
    }

    static boolean isCheckable(Element element) {
        return element.tag.equals("input") && (type(element).equals("checkbox") || type(element).equals("radio"));
    }

    static boolean isSubmitButton(Element element) {
        if (element.tag.equals("button")) {
            String type = element.attribute("type");
            return type == null || type.equalsIgnoreCase("submit");
        }
        return element.tag.equals("input") && (type(element).equals("submit") || type(element).equals("image"));
    }

    /** What a field shows. */
    static String value(Element field) {
        return switch (field.tag) {
            case "textarea" -> field.value != null ? field.value : Dom.text(field);
            case "select" -> {
                List<String> selected = selectedValues(field);
                yield selected.isEmpty() ? "" : selected.get(0);
            }
            case "option" -> optionValue(field);
            default -> {
                if (field.value != null) yield field.value;
                String value = field.attribute("value");
                yield value != null ? value : (isCheckable(field) ? "on" : "");
            }
        };
    }

    static boolean checked(Element field) {
        return field.checked != null ? field.checked : field.hasAttribute("checked");
    }

    /** The values of a select's selected options; without any, the first option of a single select. */
    static List<String> selectedValues(Element select) {
        List<Element> options = options(select);
        List<String> values = new ArrayList<>();
        for (Element option : options) {
            if (isSelected(option)) {
                values.add(optionValue(option));
            }
        }
        boolean single = !select.hasAttribute("multiple");
        if (values.isEmpty() && single && !options.isEmpty()) {
            values.add(optionValue(options.get(0)));
        }
        return single && values.size() > 1 ? List.of(values.get(values.size() - 1)) : values;
    }

    /** Chooses the option whose value is {@code value} (for a single select, only that one). */
    static void select(Element select, String value) {
        boolean found = false;
        for (Element option : options(select)) {
            boolean match = optionValue(option).equals(value);
            found |= match;
            option.selected = match;
        }
        if (!found) {
            throw new AssertionError("the select has no option with the value \"" + value + "\": " + Dom.html(select));
        }
    }

    private static List<Element> options(Element select) {
        List<Element> options = new ArrayList<>();
        Dom.eachElement(select, element -> {
            if (element.tag.equals("option")) {
                options.add(element);
            }
        });
        return options;
    }

    private static boolean isSelected(Element option) {
        return option.selected != null ? option.selected : option.hasAttribute("selected");
    }

    private static String optionValue(Element option) {
        String value = option.attribute("value");
        return value != null ? value : Dom.text(option).strip();
    }

    /** Returns a field to what its markup says: what a form reset does. */
    static void reset(Element field) {
        field.value = null;
        field.checked = null;
        if (field.tag.equals("select")) {
            for (Element option : options(field)) {
                option.selected = null;
            }
        }
    }

    /** The fields of a form, in its order, as the browser's {@code FormData} gives them. */
    static Map<String, List<String>> submitted(Element form, Element submitter) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        Dom.eachElement(form, element -> {
            String name = element.attribute("name");
            boolean control = isField(element) || element.tag.equals("button");
            if (!control || name == null || name.isEmpty() || element.hasAttribute("disabled")) {
                return;
            }
            if (element.tag.equals("button") || (element.tag.equals("input") && NOT_SUBMITTED.contains(type(element)))) {
                if (element == submitter) {
                    add(fields, name, element.attribute("value") != null ? element.attribute("value") : "");
                }
                return;
            }
            if (isCheckable(element) && !checked(element)) {
                return;
            }
            if (element.tag.equals("select")) {
                selectedValues(element).forEach(value -> add(fields, name, value));
                return;
            }
            add(fields, name, value(element));
        });
        return fields;
    }

    private static void add(Map<String, List<String>> fields, String name, String value) {
        fields.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }
}
