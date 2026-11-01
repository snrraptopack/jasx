package jasx.web;

import jasx.ui.Action;
import jasx.ui.Computation;
import jasx.ui.Effect;
import jasx.ui.Event;
import jasx.ui.Handler;
import jasx.ui.Scheduler;
import java.io.IOException;
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
 * flush on a virtual thread, whose batch is pushed on the page's stream (§5).
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

    Page(String id) {
        this.id = id;
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
    // The stream (targets/web.md §6, §8.1)
    // ---------------------------------------------------------------------------------------------

    /** Where a stream writes: one server-sent event frame at a time. */
    interface StreamSink {
        void send(String frame) throws IOException;
    }

    /** The frame that tells the browser to load the page afresh (§6.1). */
    static final String RELOAD_FRAME = "data: {\"reload\":true}\n\n";

    /**
     * Streams the page to a browser that has applied every batch up to {@code since} (§6.1): first what it
     * missed, merged into one batch, then each pushed batch as it comes, until the connection fails or a newer
     * stream of the page replaces this one. If the log no longer holds what it missed, the browser is told to
     * reload. Blocks; a comment every {@code heartbeatSeconds} keeps the connection open.
     *
     * <p>Batches that answer events are not pushed: their requests carried them. If one was lost, the browser
     * notices the missing version and reconnects, and the catch-up includes it.
     */
    void stream(StreamSink sink, long since, int heartbeatSeconds) {
        int generation = streamGeneration.incrementAndGet();
        log.wakeAll(); // a stream this one replaces stops waiting
        streamOpen = true;
        touch();
        try {
            // What the browser missed is taken before the greeting, so that from the greeting on, the stream is
            // live: a batch made later is pushed (or, for an event's answer, carried by its request).
            boolean canCatchUp = log.covers(since);
            long cursor = since;
            List<BatchLog.Batch> missed = canCatchUp ? log.after(cursor) : List.of();
            sink.send(": connected\n\n");
            if (!canCatchUp) {
                sink.send(RELOAD_FRAME);
                return;
            }
            if (!missed.isEmpty()) {
                sink.send("data: " + BatchLog.merged(missed) + "\n\n");
                cursor = missed.get(missed.size() - 1).version();
            }
            while (streamGeneration.get() == generation) {
                List<BatchLog.Batch> next = log.awaitAfter(cursor, heartbeatSeconds * 1000L);
                lastSeen = System.nanoTime();
                if (streamGeneration.get() != generation) {
                    break; // a newer stream took over while this one waited
                }
                if (next.isEmpty()) {
                    sink.send(": ping\n\n");
                    continue;
                }
                for (BatchLog.Batch batch : next) {
                    if (!batch.answer()) {
                        sink.send("data: " + batch.json() + "\n\n");
                    }
                    cursor = batch.version();
                }
            }
        } catch (IOException e) {
            // The browser went away; it reconnects if it is still there.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (streamGeneration.get() == generation) {
                streamOpen = false;
                lastSeen = System.nanoTime();
            }
        }
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
        streamGeneration.incrementAndGet(); // ends a stream that is still running
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
