package jasx.web.test;

import jasx.web.test.Dom.Element;

/**
 * An element of a {@link TestPage}, as it is now: its text, attributes and, for a field, what it shows. It reads the
 * page's current DOM, so it reflects updates as long as the element is still in the page.
 */
public final class TestElement {
    private final TestPage page;
    private final Element element;

    TestElement(TestPage page, Element element) {
        this.page = page;
        this.element = element;
    }

    Element node() {
        return element;
    }

    /** The tag name, such as {@code button}. */
    public String tag() {
        return element.tag;
    }

    /** The text content, with surrounding whitespace removed. */
    public String text() {
        synchronized (page) {
            return Dom.text(element).strip();
        }
    }

    /** The value of attribute {@code name}: "" for a bare attribute, null if absent. */
    public String attribute(String name) {
        synchronized (page) {
            return element.attribute(name);
        }
    }

    public boolean hasAttribute(String name) {
        return attribute(name) != null;
    }

    /** Whether the element's {@code class} attribute has {@code name}. */
    public boolean hasClass(String name) {
        String classes = attribute("class");
        return classes != null && java.util.List.of(classes.strip().split("\\s+")).contains(name);
    }

    /** What a field shows: typed text, a select's chosen value, or the value its markup gives. */
    public String value() {
        synchronized (page) {
            return Fields.value(element);
        }
    }

    /** Whether a checkbox or radio button is checked. */
    public boolean checked() {
        synchronized (page) {
            return Fields.checked(element);
        }
    }

    /** The element's HTML, as the renderer writes it. */
    public String html() {
        synchronized (page) {
            return Dom.html(element);
        }
    }

    @Override
    public String toString() {
        return html();
    }
}
