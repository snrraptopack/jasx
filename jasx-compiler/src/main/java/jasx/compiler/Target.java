package jasx.compiler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * A target, loaded from its descriptor {@code META-INF/jasx/target.json} on the classpath (semantics.md §7).
 *
 * @param factory the factory class {@code F} of {@code F.h(...)}
 * @param attributesPackage the package of the generated attribute builders
 * @param javaEventHandlers whether event attributes accept Java expressions ({@code eventHandlers: "java"})
 * @param styles whether the target supports style blocks ({@code styles: "css"}, semantics.md §10.6)
 */
public record Target(String id, String factory, String attributesPackage, Schema schema, boolean javaEventHandlers,
        boolean styles) {
    public static final String DESCRIPTOR = "META-INF/jasx/target.json";

    /** Loads the target {@code id} from the descriptors visible to {@code loader}. */
    public static Target load(String id, ClassLoader loader) {
        List<String> available = new ArrayList<>();
        try {
            Enumeration<URL> descriptors = loader.getResources(DESCRIPTOR);
            while (descriptors.hasMoreElements()) {
                Map<String, Object> descriptor = Json.object(Json.parse(read(descriptors.nextElement())));
                String descriptorId = Json.string(descriptor.get("id"));
                available.add(descriptorId);
                if (id.equals(descriptorId)) {
                    return fromDescriptor(descriptor, loader);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new IllegalArgumentException("no JASX target '" + id + "' on the classpath (found: " + available + ")");
    }

    private static Target fromDescriptor(Map<String, Object> descriptor, ClassLoader loader) throws IOException {
        String id = Json.string(descriptor.get("id"));
        String lowering = Json.string(descriptor.get("lowering"));
        if (lowering != null && !lowering.equals("factory")) {
            throw new IllegalArgumentException("target '" + id + "' uses custom lowering '" + lowering
                    + "', which this compiler does not support yet");
        }
        String schemaPath = Json.string(descriptor.get("schema"));
        URL schemaUrl = loader.getResource(schemaPath);
        if (schemaUrl == null) {
            throw new IllegalArgumentException("schema '" + schemaPath + "' of target '" + id + "' not found");
        }
        Schema schema = Schema.fromJson(Json.object(Json.parse(read(schemaUrl))));
        return new Target(id, Json.string(descriptor.get("factory")), Json.string(descriptor.get("attributes")),
                schema, "java".equals(descriptor.get("eventHandlers")), "css".equals(descriptor.get("styles")));
    }

    private static String read(URL url) throws IOException {
        return Resources.read(url);
    }
}
