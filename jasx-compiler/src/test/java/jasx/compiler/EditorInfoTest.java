package jasx.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The file build plugins write for the language server. */
class EditorInfoTest {

    @Test
    void roundTrips() {
        EditorInfo info = new EditorInfo("html", List.of("C:\\lib\\a.jar", "/x/\"quoted\".jar"),
                List.of("/c.jar"), List.of(), 21, "C:\\p\\build\\generated\\sources\\jasx\\main");
        assertEquals(info, EditorInfo.fromJson(info.toJson()));
    }

    @Test
    void releaseMayBeAbsent() {
        EditorInfo info = new EditorInfo("html", List.of(), List.of(), List.of(), null, null);
        assertEquals(null, EditorInfo.fromJson(info.toJson()).release());
    }

    @Test
    void aFileFromAnOlderPluginHasNoGeneratedSources() {
        String older = "{\"target\": \"html\", \"jasxClasspath\": [], \"compileClasspath\": [], \"processorPath\": [],"
                + " \"release\": 21}";
        assertEquals(null, EditorInfo.fromJson(older).generatedSources());
    }

    @Test
    void pathPerSourceSet() {
        assertEquals("jasx/editor/test.json", EditorInfo.relativePath("test"));
    }
}
