package jasx.gradle;

import org.gradle.api.provider.Property;

/**
 * The {@code jasx { ... }} block of a build script.
 *
 * <pre>
 * jasx {
 *     target.set("html")   // the default
 * }
 * </pre>
 */
public abstract class JasxExtension {

    /**
     * The id of the target that gives markup its meaning. The target itself must be on the compile
     * classpath, for example {@code implementation("dev.jasx:jasx-target-html:VERSION")}.
     */
    public abstract Property<String> getTarget();
}
