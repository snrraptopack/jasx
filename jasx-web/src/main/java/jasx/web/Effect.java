package jasx.web;

/**
 * An effect, made by {@link Web#effect} (targets/web.md §4): it runs once when created, and again after every
 * update in which a value it read changed, after the holes (§5).
 */
final class Effect extends Computation {
    private final Runnable fn;

    Effect(Page page, Runnable fn) {
        super(page);
        this.fn = fn;
    }

    void run() {
        reset();
        track(() -> {
            fn.run();
            return null;
        });
    }

    @Override
    void invalidate() {
        if (page != null) {
            page.markDirty(this); // runs in the page's next flush
        } else {
            run(); // an effect outside any page has no flush to wait for
        }
    }
}
