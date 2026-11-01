package jasx.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
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
 * <p>Ids of holes, elements and handlers come from one counter, so they are unique within the page.
 */
final class Page {
    private static final System.Logger LOG = System.getLogger(Page.class.getName());

    /** The page whose lock this thread holds (rendering, an event, a flush), for {@link Web#navigate}. */
    private static final ThreadLocal<Page> CURRENT = new ThreadLocal<>();

    /** Flushes in a row before giving up: effects that keep changing state would otherwise never stop. */
    private static final int MAX_FLUSH_ROUNDS = 100;

    final String id;
    private final ReentrantLock lock = new ReentrantLock();
    /** Owns the page's top-level holes and reads; it is never re-run, only disposed with the page. */
    private final Computation root;
    private int nextId;
    private final Map<Integer, Handler> handlers = new HashMap<>();
    /** The style sheets the browser has (targets/web.md §9). */
    private final Set<StyleSheet> sheets = new LinkedHashSet<>();

    /** Holes and effects to re-run: marked from any thread, re-run under the lock. */
    private final Set<Computation> dirty = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final List<Map<String, Object>> patches = new ArrayList<>();
    private long version;

    /** Batches for the stream, in version order; one that couldn't be sent goes back to the front. */
    private final BlockingDeque<String> outbox = new LinkedBlockingDeque<>();
    private final AtomicInteger streamGeneration = new AtomicInteger();
    private volatile boolean streamOpen;
    private volatile long lastSeen = System.nanoTime();

    Page(String id) {
        this.id = id;
        this.root = Computation.untracked(() -> new Computation(this) {
            @Override
            void invalidate() {
                // Code outside holes is not reactive (targets/web.md §4.1): nothing to re-run.
            }
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
        return locked(() -> root.track(() -> {
            Renderer renderer = new Renderer(this, false);
            String html = renderer.document(rootValue.get(), scriptTag);
            sheets.addAll(renderer.sheets());
            // State set while rendering (rare): its patches wait for the stream.
            String batch = flush();
            if (batch != null) {
                outbox.add(batch);
            }
            return html;
        }));
    }

    int nextId() {
        return ++nextId;
    }

    /** Registers a handler; it is dropped when {@code owner} re-runs (targets/web.md §4.2). */
    int addHandler(Handler handler, Computation owner) {
        int handlerId = nextId();
        handlers.put(handlerId, handler);
        Computation by = owner != null ? owner : root;
        by.onReset(() -> handlers.remove(handlerId));
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
        lastSeen = System.nanoTime();
        return locked(() -> {
            Handler handler = handlers.get(((Number) json.get("handler")).intValue());
            if (handler == null) {
                return null; // its hole re-ran since the browser rendered it (§8.3)
            }
            Event event = Event.fromJson(json);
            Computation.untracked(() -> {
                try {
                    handler.handle(event);
                } catch (Exception | AssertionError e) {
                    // The page stays usable: what the handler changed before failing is still sent.
                    LOG.log(System.Logger.Level.ERROR, "event handler failed (" + event + ")", e);
                }
                return null;
            });
            return flush();
        });
    }

    /** Marks a hole or effect to re-run. From another thread, schedules a flush of this page. */
    void markDirty(Computation computation) {
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
            String batch = flush();
            if (batch != null) {
                outbox.add(batch);
            }
            return null;
        }));
    }

    /**
     * Re-runs the dirty holes in document order, then the dirty effects, until nothing is dirty, and turns
     * the patches into one batch (targets/web.md §5, §8.4). Must hold the lock.
     *
     * @return the batch as JSON, or null if nothing changed in the browser
     */
    private String flush() {
        for (int round = 0; !dirty.isEmpty(); round++) {
            if (round == MAX_FLUSH_ROUNDS) {
                LOG.log(System.Logger.Level.WARNING, "page " + id + ": effects keep changing state; stopped after "
                        + MAX_FLUSH_ROUNDS + " flushes");
                dirty.clear();
                break;
            }
            List<Computation> now = new ArrayList<>(dirty);
            dirty.removeAll(now);
            now.sort(Comparator.comparingLong(c -> c.order));

            // A hole inside another dirty hole is disposed when its parent re-runs, and then skipped.
            for (Computation computation : now) {
                if (computation.isDisposed()) {
                    continue;
                }
                switch (computation) {
                    case ChildHole hole -> hole.rerun();
                    case AttributeHole hole -> hole.rerun();
                    default -> {}
                }
            }
            for (Computation computation : now) {
                if (computation instanceof Effect effect && !effect.isDisposed()) {
                    effect.run();
                }
            }
        }

        if (patches.isEmpty()) {
            return null;
        }
        version++;
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("version", version);
        batch.put("patches", List.copyOf(patches));
        patches.clear();
        return Json.write(batch);
    }

    // ---------------------------------------------------------------------------------------------
    // The stream (targets/web.md §6, §8.1)
    // ---------------------------------------------------------------------------------------------

    /** Where a stream writes: one server-sent event frame at a time. */
    interface StreamSink {
        void send(String frame) throws IOException;
    }

    /**
     * Sends the page's pushed batches to {@code sink} until the connection fails or a newer stream for the
     * page replaces this one. Blocks; a comment every {@code heartbeatSeconds} keeps the connection open.
     */
    void stream(StreamSink sink, int heartbeatSeconds) {
        int generation = streamGeneration.incrementAndGet();
        streamOpen = true;
        try {
            sink.send(": connected\n\n");
            while (streamGeneration.get() == generation) {
                String batch = outbox.poll(heartbeatSeconds, TimeUnit.SECONDS);
                lastSeen = System.nanoTime();
                if (batch == null) {
                    sink.send(": ping\n\n");
                    continue;
                }
                if (streamGeneration.get() != generation) {
                    outbox.addFirst(batch); // a newer stream took over while this one waited
                    break;
                }
                try {
                    sink.send("data: " + batch + "\n\n");
                } catch (IOException e) {
                    outbox.addFirst(batch); // the next stream sends it, so no version is skipped
                    throw e;
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

    /** Whether the page has had no stream and no event for {@code timeoutNanos}. */
    boolean isExpired(long timeoutNanos) {
        return !streamOpen && System.nanoTime() - lastSeen > timeoutNanos;
    }

    /** Disposes everything, which also unsubscribes the page from state shared with other pages. */
    void discard() {
        streamGeneration.incrementAndGet(); // ends a stream that is still running
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
