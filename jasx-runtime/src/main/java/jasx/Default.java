package jasx;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a component parameter as an optional prop with a default (semantics.md §5.2). The value is the
 * source text of a single Java literal, for example {@code @Default("\"Untitled\"")} or {@code @Default("5")}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface Default {
    String value();
}
