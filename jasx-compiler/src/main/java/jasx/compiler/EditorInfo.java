package jasx.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What an editor needs to know about one source set's build, so that the JASX language server can analyse
 * it as the build compiles it. Build plugins write it as JSON to
 * {@code <build directory>/jasx/editor/<sourceSet>.json} ({@code build/} for Gradle, {@code target/} for
 * Maven), and the language server reads it back.
 *
 * <pre>
 * {
 *   "target": "html",
 *   "jasxClasspath": ["/.../jasx-target-html.jar", ...],
 *   "compileClasspath": ["/.../jasx-runtime.jar", ...],
 *   "processorPath": [...],
 *   "release": 21,
 *   "generatedSources": "/.../build/generated/sources/jasx/main"
 * }
 * </pre>
 *
 * @param target           the id of the target
 * @param jasxClasspath    where the target and the components of dependencies are read from
 * @param compileClasspath what javac compiles against
 * @param processorPath    the annotation processors javac runs
 * @param release          the Java release the build compiles for, or null for the JDK's own
 * @param generatedSources where the build writes the Java generated from {@code .jasx} files, or null (a file
 *     written by an older plugin). The language server keeps the files there current while the user edits,
 *     so that the editor's Java support sees components in {@code .java} files without a build.
 */
public record EditorInfo(String target, List<String> jasxClasspath, List<String> compileClasspath,
        List<String> processorPath, Integer release, String generatedSources) {

    /** The path of the file for a source set, relative to the build directory. */
    public static String relativePath(String sourceSet) {
        return "jasx/editor/" + sourceSet + ".json";
    }

    public String toJson() {
        return "{\n"
                + "  \"target\": " + Json.quote(target) + ",\n"
                + "  \"jasxClasspath\": " + array(jasxClasspath) + ",\n"
                + "  \"compileClasspath\": " + array(compileClasspath) + ",\n"
                + "  \"processorPath\": " + array(processorPath) + ",\n"
                + "  \"release\": " + (release == null ? "null" : release.toString()) + ",\n"
                + "  \"generatedSources\": " + (generatedSources == null ? "null" : Json.quote(generatedSources)) + "\n"
                + "}\n";
    }

    /**
     * Reads the JSON form.
     *
     * @throws IllegalArgumentException if it is not valid
     */
    public static EditorInfo fromJson(String json) {
        Map<String, Object> object = Json.object(Json.parse(json));
        Object release = object.get("release");
        return new EditorInfo(Json.string(object.get("target")), strings(object.get("jasxClasspath")),
                strings(object.get("compileClasspath")), strings(object.get("processorPath")),
                release instanceof Number number ? number.intValue() : null,
                Json.string(object.get("generatedSources")));
    }

    private static String array(List<String> values) {
        List<String> quoted = new ArrayList<>();
        for (String value : values) {
            quoted.add(Json.quote(value));
        }
        return "[" + String.join(", ", quoted) + "]";
    }

    private static List<String> strings(Object array) {
        List<String> strings = new ArrayList<>();
        for (Object value : Json.array(array)) {
            strings.add((String) value);
        }
        return strings;
    }
}
