package jasx.gradle;

import org.gradle.api.provider.Property;

/**
 * The {@code jasx { ... }} block of a build script.
 *
 * <pre>
 * jasx {
 *     target.set("html")     // the default
 *     typeCheck.set(true)    // the default
 * }
 * </pre>
 */
public abstract class JasxExtension {

    /**
     * The id of the target that gives markup its meaning. The target itself must be on the compile
     * classpath, for example {@code implementation("io.github.snrraptopack:jasx-target-html:VERSION")}.
     */
    public abstract Property<String> getTarget();

    /**
     * Whether {@code compileJasx} type-checks the generated Java with javac, so that javac's errors in
     * {@code .jasx} files are reported once, at their {@code .jasx} locations (see {@link JasxCompile}).
     *
     * <p>The check compiles the source set a second time (without writing class files). Turn it off if that
     * is too slow for a large source set: {@code compileJava} then reports those errors in the generated
     * files under {@code build/generated/sources/jasx}, on the same line numbers as the {@code .jasx}
     * source.
     */
    public abstract Property<Boolean> getTypeCheck();
}
