package jasx.compiler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
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
 * @param lowering the target's own lowering class ({@code "lowering": "<class name>"}, semantics.md §7.4), or
 *     null for factory lowering
 */
public record Target(String id, String factory, String attributesPackage, Schema schema, boolean javaEventHandlers,
        boolean styles, Class<? extends MarkupLowering> lowering) {
    public static final String DESCRIPTOR = "META-INF/jasx/target.json";

    /** A target with factory lowering. */
    public Target(String id, String factory, String attributesPackage, Schema schema, boolean javaEventHandlers,
            boolean styles) {
        this(id, factory, attributesPackage, schema, javaEventHandlers, styles, null);
    }

    /**
     * Loads the target {@code id} from the descriptors visible to {@code loader}. A target with its own
     * lowering needs a loader that can load the lowering class and gives it this compiler's classes, such as
     * a {@link TargetClassLoader}; the loader must stay open while the target is used.
     */
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
        String schemaPath = Json.string(descriptor.get("schema"));
        URL schemaUrl = loader.getResource(schemaPath);
        if (schemaUrl == null) {
            throw new IllegalArgumentException("schema '" + schemaPath + "' of target '" + id + "' not found");
        }
        Schema schema = Schema.fromJson(Json.object(Json.parse(read(schemaUrl))));
        String lowering = Json.string(descriptor.get("lowering"));
        boolean factoryLowering = lowering == null || lowering.equals("factory");
        return new Target(id, Json.string(descriptor.get("factory")), Json.string(descriptor.get("attributes")),
                schema, "java".equals(descriptor.get("eventHandlers")), "css".equals(descriptor.get("styles")),
                factoryLowering ? null : loweringClass(id, lowering, loader));
    }

    /** Loads the lowering class {@code name} of target {@code id}, which must implement {@link MarkupLowering}. */
    private static Class<? extends MarkupLowering> loweringClass(String id, String name, ClassLoader loader) {
        Class<?> loaded;
        try {
            loaded = Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalArgumentException("the lowering class '" + name + "' of target '" + id
                    + "' can't be loaded: " + e, e);
        }
        if (!MarkupLowering.class.isAssignableFrom(loaded)) {
            throw new IllegalArgumentException("the lowering class '" + name + "' of target '" + id + "' doesn't "
                    + "implement " + MarkupLowering.class.getName());
        }
        return loaded.asSubclass(MarkupLowering.class);
    }

    /** A new instance of the target's lowering, for one file (see {@link MarkupLowering}). */
    public MarkupLowering newLowering() {
        if (lowering == null) {
            return new FactoryLowering(this);
        }
        try {
            Constructor<? extends MarkupLowering> constructor = lowering.getConstructor(Target.class);
            return constructor.newInstance(this);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("the lowering class '" + lowering.getName() + "' of target '" + id
                    + "' has no public constructor taking a " + Target.class.getName(), e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("the lowering of target '" + id + "' failed to start", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the lowering of target '" + id + "' can't be created", e);
        }
    }

    private static String read(URL url) throws IOException {
        return Resources.read(url);
    }
}
