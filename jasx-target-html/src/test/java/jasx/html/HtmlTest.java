package jasx.html;

import static jasx.html.Html.fragment;
import static jasx.html.Html.h;
import static jasx.html.Html.keyed;
import static jasx.html.Html.rawHtml;
import static jasx.html.Html.render;
import static jasx.html.Html.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jasx.html.attrs.A;
import jasx.html.attrs.Button;
import jasx.html.attrs.Custom;
import jasx.html.attrs.Input;
import jasx.html.attrs.Label;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class HtmlTest {

    @Test
    void elementsAndText() {
        assertEquals("<div><p>a</p>b</div>", render(h("div", null, h("p", null, "a"), "b")));
    }

    @Test
    void attributesInOrderWithBooleans() {
        assertEquals("<button class=\"x\" disabled type=\"submit\">Go</button>",
                render(h("button", Button.create().className("x").disabled(true).formnovalidate(false).type("submit"), "Go")));
    }

    @Test
    void nullAttributeIsOmitted() {
        assertEquals("<a>x</a>", render(h("a", A.create().href(null), "x")));
    }

    @Test
    void keywordAliases() {
        assertEquals("<label for=\"n\"></label>", render(h("label", Label.create().htmlFor("n"))));
    }

    @Test
    void voidElementsHaveNoClosingTag() {
        assertEquals("<input type=\"checkbox\" checked><br>",
                render(fragment(h("input", Input.create().type("checkbox").checked(true)), h("br", null))));
    }

    @Test
    void escaping() {
        assertEquals("<p title=\"a &quot;q&quot; &amp; &lt;b&gt;\">&lt;i&gt; &amp; \"q\"</p>",
                render(h("p", A.create().title("a \"q\" & <b>"), "<i> & \"q\"")));
    }

    @Test
    void childValueContract() {
        Object[] children = {
            null, Optional.empty(), Optional.of("o"), true, 1, List.of("l", List.of("m")),
            new int[] {2, 3}, Stream.of("s"), text("<t>"), keyed(1, h("b", null)), rawHtml("<hr>")
        };
        assertEquals("<p>otrue1lm23s&lt;t&gt;<b></b><hr></p>", render(h("p", null, children)));
    }

    @Test
    void nullChildrenArrayMeansNoChildren() {
        assertEquals("<p></p>", render(h("p", null, (Object[]) null)));
    }

    @Test
    void extensionAndCustomElementAttributes() {
        assertEquals("<my-el data-id=\"7\" hx-post=\"/x\"></my-el>",
                render(h("my-el", Custom.create().attr("data-id", 7).attr("hx-post", "/x"))));
    }

    @Test
    void elementAsAttributeValueIsRenderedAndEscaped() {
        assertEquals("<a title=\"&lt;b&gt;x&lt;/b&gt;\"></a>", render(h("a", A.create().title(h("b", null, "x")))));
    }

    @Test
    void scriptAndStyleContentIsNotEscaped() {
        assertEquals("<style>a > b { }</style><script>if (a < b && c) {}</script>",
                render(fragment(h("style", null, "a > b { }"), h("script", null, "if (a < b && c) {}"))));
    }

    @Test
    void textIsEscapedAgainAfterRawTextElements() {
        assertEquals("<div><style>a>b</style>&lt;</div>", render(h("div", null, h("style", null, "a>b"), "<")));
    }

    @Test
    void closingTagInsideRawTextIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> render(h("script", null, "x</SCRIPT><b>")));
    }

    @Test
    void toStringRenders() {
        assertEquals("<p>x</p>", h("p", null, "x").toString());
    }
}
