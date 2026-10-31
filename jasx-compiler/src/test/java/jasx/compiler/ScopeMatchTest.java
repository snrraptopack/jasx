package jasx.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jasx.compiler.ScopeMatch.AttributeValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which elements a file's scoped compounds may match (semantics.md §10.5). */
class ScopeMatchTest {
    private static final AttributeValue ABSENT = AttributeValue.ABSENT;
    private static final AttributeValue ANY = AttributeValue.ANY;

    private static boolean mayMatch(String compound, String tag, AttributeValue classAttribute, AttributeValue id) {
        return ScopeMatch.of(List.of(compound)).mayMatch(tag, classAttribute, id);
    }

    private static boolean mayMatch(String compound, String tag) {
        return mayMatch(compound, tag, ABSENT, ABSENT);
    }

    @Test
    void aTypeSelectorMatchesItsTagOnly() {
        assertTrue(mayMatch("li", "li"));
        assertTrue(mayMatch("LI:hover", "li"));
        assertFalse(mayMatch("li", "ul"));
        assertTrue(mayMatch("*", "ul"));
    }

    @Test
    void aClassSelectorNeedsTheClass() {
        assertTrue(mayMatch(".card", "div", AttributeValue.literal("card big"), ABSENT));
        assertTrue(mayMatch(".card", "div", AttributeValue.literal("  big\tcard "), ABSENT));
        assertFalse(mayMatch(".card", "div", AttributeValue.literal("other"), ABSENT));
        assertFalse(mayMatch(".card", "div", AttributeValue.literal("cards"), ABSENT));
        assertFalse(mayMatch(".card", "div"));
        assertTrue(mayMatch(".card", "div", ANY, ABSENT)); // class={c}
        assertFalse(mayMatch(".a.b", "div", AttributeValue.literal("a"), ABSENT));
    }

    @Test
    void anIdSelectorNeedsTheId() {
        assertTrue(mayMatch("#main", "main", ABSENT, AttributeValue.literal("main")));
        assertFalse(mayMatch("#main", "main", ABSENT, AttributeValue.literal("other")));
        assertFalse(mayMatch("#main", "main"));
        assertTrue(mayMatch("#main", "main", ABSENT, ANY));
    }

    @Test
    void onlyTheTopLevelCounts() {
        assertTrue(mayMatch(":not(.x)", "p"));
        assertTrue(mayMatch("p[class~=\"a.b\"]", "p"));
        assertTrue(mayMatch(":is(li, .x)", "p"));
    }

    @Test
    void unreadableCompoundsMatchEverything() {
        assertTrue(mayMatch("&:hover", "p"));
        assertTrue(mayMatch(".a\\:b", "p"));
        assertTrue(mayMatch("svg|rect", "p"));
    }

    @Test
    void anyCompoundIsEnough() {
        ScopeMatch match = ScopeMatch.of(List.of("li", ".note"));
        assertTrue(match.mayMatch("p", AttributeValue.literal("note"), ABSENT));
        assertFalse(match.mayMatch("p", ABSENT, ABSENT));
        assertFalse(ScopeMatch.of(List.of()).mayMatch("p", ANY, ANY)); // only :global rules
    }

    @Test
    void scopedCssReportsItsScopedCompounds() {
        ScopedCss.Result result = ScopedCss.scope("ul > li:hover, .t::before { } :global(body) { }", "jx-1");
        assertEquals(List.of("li:hover", ".t"), result.scopedCompounds());
    }
}
