package jasx;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a static method returning {@link jasx.runtime.Element} as a component (semantics.md §5.1). Methods
 * in {@code .jasx} files are components automatically; in {@code .java} files, annotate them.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Component {}
