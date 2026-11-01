package jasx.web;

import jasx.ui.Action;
import jasx.ui.Computation;
import jasx.ui.Effect;
import jasx.ui.Event;
import jasx.ui.Handler;
import jasx.ui.Scheduler;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One page, alive on the server while the browser shows it (targets/web.md §6).
 *
 * <p>Everything that touches the page's holes and handlers runs under its lock: the first rendering, each
 * event, and each flush. State changed from another thread marks holes of the page dirty and schedules a
 * flush on a virtual thread, whose batch is pushed on the page's stream (§5), which a {@link SharedStream} serves.
 *
 * <p>It is the {@link Scheduler} of its holes and effects (jasx-spec/ui.md §3): a hole whose state changed is
 * {@linkplain #schedule scheduled}, and re-run by the next {@link #flush}.
 *
 * <p>Ids of holes, elements and handlers come from one counter, so they are unique within the page.
 */
final class Page implements Scheduler {
    private static final System.Logger LOG = System.getLogger(Page.class.getName());

    /** The page whose lock this thread holds (rendering, an event, a flush), for {@link Web#navigate}. */
    private static final ThreadLocal<Page> CURRENT = new ThreadLocal<>();

    /** Flushes in a row before giving up: effects that keep changing state would otherwise never stop. */
    private static final int MAX_FLUSH_ROUNDS = 100;

    final String id;
    /** The browser session that rendered the page (targets/web.md §6.3): only it may act on the page. */
    private final String session;
    private final ReentrantLock lock = new ReentrantLock();
    /**
     * Owns what the page creates outside holes (top-level holes, state and effects made while rendering or
     * in a handler); it is never re-run, only disposed with the page.
     */
    private final Computation root;
    private int nextId;
    /** Each handler is a {@link Handler} or an {@link Action} (jasx-spec/ui.md §4.1). */
    private final Map<Integer, Object> handlers = new HashMap<>();
    /** The style sheets the browser has (targets/web.md §9). */
    private final Set<StyleSheet> sheets = new LinkedHashSet<>();

    /** Holes and effects to re-run: marked from any thread, re-run under the lock. */
    private final Set<Computation> dirty = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final List<Map<String, Object>> patches = new ArrayList<>();

    /** Every batch, numbered, kept so a browser that missed some can catch up (§6.1). */
    private final BatchLog log = new BatchLog();
    private final AtomicInteger streamGeneration = new AtomicInteger();
    private volatile boolean streamOpen;
    private volatile long lastSeen = System.nanoTime();
    /**
     * Whether a browser ever connected (a stream, an event or a ping). A page rendered for a crawler, a link
     * preview or a prefetch never runs its script, so it is kept only for the first-connect timeout (§6).
     */
    private volatile boolean connected;

    Page(String id, String session) {
        this.id = id;
        this.session = session;
        this.root = Computation.runWithOwner(null, () -> new Computation(this) {
            @Override
            protected void invalidate() {
                // Code outside holes is not reactive (targets/web.md §4.1): nothing to re-run.
            }

            @Override
            public void update() {}
        });
    }

    /** The page whose lock this thread holds, or null. */
    static Page current() {
        return CURRENT.get();
    }

    /** The session that rendered the page. */
    String session() {
        return session;
    }

    /**
     * Whether a request carrying {@code session} may act on the page. Compared in constant time, so the time an
     * answer takes tells nothing about the value.
     */
    boolean belongsTo(String session) {
        if (session == null) {
            return false;
        }
        return MessageDigest.isEqual(this.session.getBytes(StandardCharsets.US_ASCII),
                session.getBytes(StandardCharsets.US_ASCII));
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    /**
     * The first rendering of the page: its whole HTML (targets/web.md §6). {@code rootValue} is called with
     * the lock held, so that the components it calls create their state for this page.
     */
    String render(java.util.function.Supplier<?> rootValue, String scriptTag) {
        return locked(() -> Computation.runWithOwner(root, () -> {
            Renderer renderer = new Renderer(this, false);
            String html = renderer.document(rootValue.get(), scriptTag);
            sheets.addAll(renderer.sheets());
            // State set while rendering (rare): the stream delivers its batch when the browser connects.
            flush(false);
            return html;
        }));
    }

    int nextId() {
        return ++nextId;
    }

    /**
     * Registers a {@link Handler} or {@link Action}; it is dropped when {@code owner} re-runs (targets/web.md
     * §4.2).
     */
    int addHandler(Object handler, Computation owner) {
        int handlerId = nextId();
        handlers.put(handlerId, handler);
        Computation by = owner != null ? owner : root;
        by.onCleanup(() -> handlers.remove(handlerId));
        return handlerId;
    }

    /** Adds a {@code css} patch for each style sheet the browser doesn't have yet (targets/web.md §9). */
    void useSheets(Set<StyleSheet> used) {
        for (StyleSheet sheet : used) {
            if (sheets.add(sheet)) {
                Map<String, Object> patch = new LinkedHashMap<>();
                patch.put("op", "css");
                patch.put("css", sheet.css());
                patches.add(patch);
            }
        }
    }

    void addPatch(Map<String, Object> patch) {
        patches.add(patch);
    }

    // ---------------------------------------------------------------------------------------------
    // Events and flushes (targets/web.md §5, §7)
    // ---------------------------------------------------------------------------------------------

    /**
     * Runs the handler of an event and flushes.
     *
     * @return the batch the event produced, or null if nothing changed (or the handler no longer exists)
     */
    String event(Map<String, Object> json) {
        touch();
        return locked(() -> {
            Object handler = handlers.get(((Number) json.get("handler")).intValue());
            if (handler == null) {
                return null; // its hole re-ran since the browser rendered it (§8.3)
            }
            WebEvent event = WebEvent.fromJson(json);
            // Reads in a handler subscribe nothing; what it creates belongs to the page.
            Computation.runWithOwner(root, () -> {
                try {
                    invoke(handler, event);
                } catch (Exception | AssertionError e) {
                    // The page stays usable: what the handler changed before failing is still sent.
                    LOG.log(System.Logger.Level.ERROR, "event handler failed (" + event + ")", e);
                }
                return null;
            });
            return flush(true);
        });
    }

    /**
     * Calls a handler with its event. The event is every kind of event at once ({@link WebEvent}), so whatever
     * kind the handler's attribute declared, it accepts it.
     */
    @SuppressWarnings("unchecked")
    private static void invoke(Object handler, WebEvent event) throws Exception {
        switch (handler) {
            case Handler<?> withEvent -> ((Handler<Event>) withEvent).handle(event);
            case Action action -> action.run();
            default -> throw new IllegalStateException("not a handler: " + handler);
        }
    }

    /** Marks a hole or effect to re-run. From another thread, schedules a flush of this page. */
    @Override
    public void schedule(Computation computation) {
        dirty.add(computation);
        if (!lock.isHeldByCurrentThread()) {
            requestFlush();
        }
    }

    /** Flushes on a virtual thread (once, however many changes arrive meanwhile) and pushes the batch. */
    private void requestFlush() {
        if (!flushScheduled.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("jasx-flush-" + id).start(() -> locked(() -> {
            flushScheduled.set(false); // changes from now on need another flush
            flush(false); // the stream pushes it
            return null;
        }));
    }

    /**
     * Re-runs the dirty holes in document order, then the dirty effects, until nothing is dirty, and turns
     * the patches into the page's next batch (targets/web.md §5, §8.4). Must hold the lock.
     *
     * @param answer whether the batch answers an event (its request returns it) or is pushed on the stream
     * @return the batch as JSON, or null if nothing changed in the browser
     */
    private String flush(boolean answer) {
        for (int round = 0; !dirty.isEmpty(); round++) {
            if (round == MAX_FLUSH_ROUNDS) {
                LOG.log(System.Logger.Level.WARNING, "page " + id + ": effects keep changing state; stopped after "
                        + MAX_FLUSH_ROUNDS + " flushes");
                dirty.clear();
                break;
            }
            List<Computation> now = new ArrayList<>(dirty);
            dirty.removeAll(now);
            now.sort(Comparator.comparingLong(Computation::order));

            // Holes first, then effects (jasx-spec/ui.md §3). A hole inside another dirty hole is disposed
            // when its parent re-runs, and then skipped.
            for (Computation computation : now) {
                if (!(computation instanceof Effect) && !computation.isDisposed()) {
                    updateIsolated(computation);
                }
            }
            for (Computation computation : now) {
                if (computation instanceof Effect effect && !effect.isDisposed()) {
                    updateIsolated(effect);
                }
            }
        }

        if (patches.isEmpty()) {
            return null;
        }
        String batch = log.add(List.copyOf(patches), answer);
        patches.clear();
        return batch;
    }

    /**
     * Updates one hole or effect. If it throws, it keeps its previous run (jasx-spec/ui.md §2.4): the browser
     * keeps showing what it showed, with working handlers, and the rest of the flush goes on (targets/web.md §5).
     */
    private void updateIsolated(Computation computation) {
        try {
            computation.update();
        } catch (RuntimeException | AssertionError e) {
            String what = computation instanceof Effect ? "an effect" : "a hole, which keeps its previous content,";
            LOG.log(System.Logger.Level.ERROR, "page " + id + ": " + what + " failed to update", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The stream (targets/web.md §6, §6.2): served by a SharedStream, which may carry other pages too
    // ---------------------------------------------------------------------------------------------

    /**
     * Makes {@code stream} the page's stream, replacing the one it had: the browser reconnected, or the tab that
     * streams for every tab changed (§6.2). {@code wake} is called after each new batch until
     * {@link #detachStream}.
     *
     * @return the stream's generation, which tells it later whether it still streams the page
     */
    synchronized int attachStream(Runnable wake) {
        int generation = streamGeneration.incrementAndGet();
        log.wakeAll(); // the stream this one replaces notices, and lets the page go
        log.watch(wake);
        streamOpen = true;
        touch();
        return generation;
    }

    /** Whether the stream of {@code generation} still streams the page: no newer stream took it, and it is alive. */
    boolean isStreamedBy(int generation) {
        return streamGeneration.get() == generation;
    }

    /** The stream of {@code generation} no longer streams the page; from now on the page can expire (§6). */
    synchronized void detachStream(int generation, Runnable wake) {
        log.unwatch(wake);
        if (streamGeneration.get() == generation) {
            streamOpen = false;
            lastSeen = System.nanoTime();
        }
    }

    /** Whether a browser that applied every batch up to {@code since} can catch up (§6.1). */
    boolean canCatchUp(long since) {
        return log.covers(since);
    }

    /** The batches after {@code version}, in order. */
    List<BatchLog.Batch> batchesAfter(long version) {
        return log.after(version);
    }

    /**
     * Whether the page can be discarded: a browser connected, but it has had no stream and no sign of life for
     * {@code timeoutNanos}; or no browser ever connected within {@code firstConnectTimeoutNanos} (§6).
     */
    boolean isExpired(long timeoutNanos, long firstConnectTimeoutNanos) {
        long idle = System.nanoTime() - lastSeen;
        if (!connected) {
            return idle > firstConnectTimeoutNanos;
        }
        return !streamOpen && idle > timeoutNanos;
    }

    /** A sign of life from the browser: an event, a stream, or a ping from a hidden tab (§6). */
    void touch() {
        connected = true;
        lastSeen = System.nanoTime();
    }

    /** Disposes everything, which also unsubscribes the page from state shared with other pages. */
    void discard() {
        streamGeneration.incrementAndGet(); // the stream that carries the page lets it go
        log.wakeAll();
        locked(() -> {
            root.dispose();
            handlers.clear();
            dirty.clear();
            return null;
        });
    }

    // ---------------------------------------------------------------------------------------------

    private <T> T locked(java.util.function.Supplier<T> body) {
        lock.lock();
        Page previous = CURRENT.get();
        CURRENT.set(this);
        try {
            return body.get();
        } finally {
            CURRENT.set(previous);
            lock.unlock();
        }
    }
}
