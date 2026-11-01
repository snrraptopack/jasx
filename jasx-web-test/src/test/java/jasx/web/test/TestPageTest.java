package jasx.web.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jasx.runtime.Element;
import jasx.ui.State;
import jasx.web.Web;
import jasx.web.attrs.Button;
import jasx.web.attrs.Form;
import jasx.web.attrs.Input;
import jasx.web.attrs.Li;
import jasx.web.attrs.Option;
import jasx.web.attrs.Select;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link TestPage} on pages built with the web target's runtime API (what compiled .jasx markup calls). */
class TestPageTest {
    private static Element counter() {
        State<Integer> count = Web.state(0);
        return Web.h("div", null,
                Web.h("button", Button.create().className("minus").onclick(() -> count.update(n -> n - 1)), "-"),
                Web.h("output", null, Web.dynamic(() -> count.get())),
                Web.h("button", Button.create().className("plus").onclick(() -> count.update(n -> n + 1)), "+"));
    }

    @Test
    void aClickRunsTheHandlerAndTheAnswerUpdatesThePage() {
        try (TestPage page = TestPage.render(TestPageTest::counter)) {
            assertEquals("0", page.text("output"));
            page.click("button.plus").click("button.plus").click("button.minus");
            assertEquals("1", page.text("output"));
        }
    }

    /** A to-do list: a form that adds keyed items, each with a checkbox. */
    private record Todo(int id, String title, boolean done) {}

    private static Element todos() {
        State<List<Todo>> items = Web.state(List.of());
        return Web.h("div", null,
                Web.h("form", Form.create().onsubmit(e -> {
                            List<Todo> next = new ArrayList<>(items.get());
                            next.add(new Todo(next.size() + 1, e.field("title"), false));
                            items.set(List.copyOf(next));
                            e.resetForm();
                        }),
                        Web.h("input", Input.create().name("title")),
                        Web.h("button", null, "Add")),
                Web.h("ul", null, Web.dynamic(() -> items.get().stream().map(t -> Web.keyed(t.id(), () ->
                        Web.h("li", Li.create().className(t.done() ? "done" : null),
                                Web.h("input", Input.create().type("checkbox").checked(t.done()).onchange(() -> items.set(
                                        items.get().stream().map(o -> o.id() == t.id() ? new Todo(o.id(), o.title(), !o.done()) : o).toList()))),
                                Web.h("span", null, t.title())))).toList())),
                Web.h("p", null, Web.dynamic(() -> items.get().stream().filter(t -> !t.done()).count() + " left")));
    }

    @Test
    void formsSubmitTheirFieldsAndKeyedListsUpdate() {
        try (TestPage page = TestPage.render(TestPageTest::todos)) {
            page.submit("form", Map.of("title", "Milk"));
            page.type("input[name=title]", "Eggs").click("form button"); // clicking the submit button submits too
            assertEquals(List.of("Milk", "Eggs"), page.findAll("ul > li span").stream().map(TestElement::text).toList());
            assertEquals("", page.find("input[name=title]").value(), "the form was reset");
            assertEquals("2 left", page.text("p"));

            page.click("ul > li input[type=checkbox]");
            assertTrue(page.find("ul > li").hasClass("done"));
            assertTrue(page.find("ul > li input").checked());
            assertEquals("1 left", page.text("p"));
        }
    }

    @Test
    void typingAndChoosingSendTheFieldsValue() {
        try (TestPage page = TestPage.render(() -> {
            State<String> query = Web.state("");
            State<String> size = Web.state("m");
            return Web.h("div", null,
                    Web.h("input", Input.create().oninput(e -> query.set(e.value()))),
                    Web.h("select", Select.create().onchange(e -> size.set(e.value())),
                            Web.h("option", Option.create().value("s"), "Small"),
                            Web.h("option", Option.create().value("m").selected(true), "Medium"),
                            Web.h("option", Option.create().value("l"), "Large")),
                    Web.h("p", null, Web.dynamic(() -> query.get() + "/" + size.get())));
        })) {
            assertEquals("m", page.find("select").value());
            page.type("input", "shoes").change("select", "l");
            assertEquals("shoes/l", page.text("p"));
        }
    }

    @Test
    void aKeyEventCarriesItsKey() {
        try (TestPage page = TestPage.render(() -> {
            State<String> last = Web.state("none");
            return Web.h("div", null,
                    Web.h("input", Input.create().onkeydown(e -> last.set(e.key()))),
                    Web.h("p", null, Web.dynamic(() -> last.get())));
        })) {
            page.keydown("input", "Enter");
            assertEquals("Enter", page.text("p"));
        }
    }

    @Test
    void stateChangedElsewhereIsPushedAndAwaited() {
        State<String> news = Web.state("first");
        try (TestPage page = TestPage.render(() -> Web.h("p", null, Web.dynamic(() -> news.get())))) {
            news.set("second"); // from the test's thread: pushed on the page's stream
            page.await(() -> assertEquals("second", page.text("p")));
        }
    }

    @Test
    void navigationIsRecorded() {
        try (TestPage page = TestPage.render(() -> Web.h("button", Button.create().onclick(() -> Web.navigate("/done")), "Go"))) {
            page.click("button");
            assertEquals("/done", page.navigatedTo());
        }
    }

    @Test
    void mistakesFailWithTheHtml() {
        try (TestPage page = TestPage.render(() -> Web.h("div", null, Web.h("span", null, "plain"), Web.h("p", null, "text")))) {
            AssertionError missing = assertThrows(AssertionError.class, () -> page.find("button"));
            assertTrue(missing.getMessage().contains("<span>plain</span>"), missing.getMessage());
            AssertionError inert = assertThrows(AssertionError.class, () -> page.click("span"));
            assertTrue(inert.getMessage().contains("does nothing"), inert.getMessage());
            assertFalse(page.has("button"));
        }
    }

    @Test
    void selectorsMatchTagsIdsClassesAttributesAndCombinators() {
        Dom.Element root = HtmlParser.parse(
                "<div id=\"a\" class=\"x y\"><p class=\"y\">1</p><section><p data-k=\"v\">2</p></section></div><p>3</p>");
        assertEquals(List.of("1", "2"), texts(root, "#a p"));
        assertEquals(List.of("1"), texts(root, "div > p"));
        assertEquals(List.of("1"), texts(root, "p.y"));
        assertEquals(List.of("2"), texts(root, "[data-k=v]"));
        assertEquals(List.of("2"), texts(root, "section [data-k=\"v\"]"));
        assertEquals(List.of("1", "2", "3"), texts(root, "p"));
        assertEquals(List.of("1", "2"), texts(root, "div.x.y p, section > p"), "a list: either selector");
    }

    private static List<String> texts(Dom.Element root, String selector) {
        return Selector.parse(selector).select(root).stream().map(element -> Dom.text(element)).toList();
    }
}
