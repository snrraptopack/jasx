package jasx.ui;

/**
 * An event handler that needs nothing from its event: {@code onclick={() -> count.update(n -> n + 1)}}
 * (jasx-spec/ui.md §4.1). Every event attribute accepts one; Java tells it from a {@link Handler} by the number
 * of parameters of the lambda.
 */
@FunctionalInterface
public interface Action {
    void run() throws Exception;
}
