package board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jasx.web.test.TestElement;
import jasx.web.test.TestPage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The board, tested without a browser and without starting Spring: a TaskService is a plain object, and TestPage
 * renders the component, then clicks, types and submits as a user would, and reads the page's HTML.
 */
class BoardTest {
    private final TaskService tasks = new TaskService();

    private static List<String> titles(TestPage page) {
        return page.findAll("ul > li a").stream().map(TestElement::text).toList();
    }

    @Test
    void addingATaskShowsItAndResetsTheForm() {
        try (TestPage page = TestPage.render(() -> Views.Board(tasks, "all"))) {
            page.submit("form", Map.of("title", "Write tests", "assignee", "Yaw"));

            assertEquals("Write tests", titles(page).get(3));
            assertEquals("Yaw", page.findAll("ul > li .who").get(3).text());
            assertEquals("", page.find("input[name=title]").value(), "the form was reset");
            assertTrue(page.text("p.hint").startsWith("3 open."));
        }
    }

    @Test
    void eachWindowFiltersOnItsOwn() {
        try (TestPage mine = TestPage.render(() -> Views.Board(tasks, "all"));
             TestPage theirs = TestPage.render(() -> Views.Board(tasks, "all"))) {
            mine.click(".filters button", "Done");

            assertEquals(List.of("Set up the Spring Boot project"), titles(mine));
            assertTrue(mine.find(".filters button", "Done").hasClass("active"));
            assertEquals(3, titles(theirs).size(), "the other window's filter didn't change");
        }
    }

    @Test
    void aChangeInOneWindowReachesTheOthers() {
        try (TestPage mine = TestPage.render(() -> Views.Board(tasks, "all"));
             TestPage theirs = TestPage.render(() -> Views.Board(tasks, "all"))) {
            mine.click("ul > li input[type=checkbox]"); // the first task was done: open again

            assertTrue(mine.text("p.hint").startsWith("3 open."));
            theirs.await(() -> assertTrue(theirs.text("p.hint").startsWith("3 open.")));
        }
    }

    @Test
    void aTaskCanBeRenamedAndCommentedOn() {
        try (TestPage page = TestPage.render(() -> Views.TaskPage(tasks, 2))) {
            page.submit("article form", Map.of("title", "Write the board page"));
            page.submit("input[name=text]", Map.of("text", "Looks good")); // the form that holds this field

            assertEquals("Write the board page", tasks.find(2).orElseThrow().title());
            assertEquals(List.of("Looks good"), page.findAll("article ul li").stream().map(TestElement::text).toList());
        }
    }
}
