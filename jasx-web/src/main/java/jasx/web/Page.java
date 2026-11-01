package jasx.web;

import jasx.ui.Action;
import jasx.ui.Computation;
import jasx.ui.Effect;
import jasx.ui.Event;
import jasx.ui.Handler;
import jasx.ui.Scheduler;
import jasx.ui.UploadedFile;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    // Templates (targets/web.md §8.4): the statics of a shape sent once, then only the values
    // ---------------------------------------------------------------------------------------------

    /** At most this many shapes are numbered per page; others are always sent as HTML. */
    private static final int MAX_TEMPLATES = 500;

    /** A shape the page has rendered: its number, and whether the browser has been sent its statics. */
    private static final class Template {
        final int number;
        boolean sent;

        Template(int number) {
            this.number = number;
        }
    }

    /** The shapes rendered in patches, by their statics. */
    private final Map<List<String>, Template> templates = new HashMap<>();

    /**
     * How a patch carries {@code content}: {@code {"html": ...}}, or {@code {"t": N, "v": [...]}} for a shape the
     * browser knows. A shape is numbered the first time it is rendered and sent as HTML; the second time, its
     * statics go to the browser in a {@code tpl} patch, added before the patch that uses it, and from then on only
     * the values are sent. A template is used only when it is shorter than the HTML. Must hold the lock.
     */
    Map<String, Object> content(Markup content) {
        String html = content.toString();
        List<String> values = content.values();
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("html", html);
        if (values.isEmpty()) {
            return plain;
        }
        List<String> statics = content.statics();
        Template template = templates.get(statics);
        if (template == null) {
            if (templates.size() < MAX_TEMPLATES) {
                templates.put(statics, new Template(templates.size() + 1));
            }
            return plain;
        }
        if (!isShorter(html, values)) {
            return plain;
        }
        if (!template.sent) {
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("op", "tpl");
            definition.put("t", template.number);
            definition.put("s", statics);
            patches.add(definition);
            template.sent = true;
        }
        Map<String, Object> templated = new LinkedHashMap<>();
        templated.put("t", template.number);
        templated.put("v", values);
        return templated;
    }

    /** Whether sending the values (with a few characters of JSON each) is shorter than sending the HTML. */
    private static boolean isShorter(String html, List<String> values) {
        int valuesLength = 16;
        for (String value : values) {
            valuesLength += value.length() + 4;
        }
        return valuesLength < html.length();
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
            List<Upload> used = new ArrayList<>();
            WebEvent event = WebEvent.fromJson(json, token -> {
                Upload upload = uploads.remove(token);
                if (upload != null) {
                    used.add(upload);
                }
                return upload;
            });
            // Reads in a handler subscribe nothing; what it creates belongs to the page.
            Computation.runWithOwner(root, () -> {
                try {
                    invoke(handler, event);
                } catch (Exception | AssertionError e) {
                    // The page stays usable: what the handler changed before failing is still sent.
                    LOG.log(System.Logger.Level.ERROR, "event handler failed (" + event + ")", e);
                } finally {
                    used.forEach(Upload::delete); // the handler has saved what it needed (ui.md §4)
                }
                return null;
            });
            return flush(true);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Uploads (targets/web.md §8.1): files kept until the event that names them has been handled
    // ---------------------------------------------------------------------------------------------

    /** At most this many uploads wait for their event at once. */
    static final int MAX_PENDING_UPLOADS = 10;

    /** A file uploaded for a coming event, kept in a temporary file. */
    record Upload(String name, String contentType, long size, Path path) implements UploadedFile {
        @Override
        public InputStream open() throws IOException {
            return Files.newInputStream(path);
        }

        void delete() {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "could not delete the upload " + path, e);
            }
        }
    }

    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();

    /** What {@link #addUpload} can answer besides a token. */
    enum UploadRefusal { TOO_LARGE, TOO_MANY }

    /**
     * Keeps a file the browser uploads for an event it is about to send: {@code body} is copied to a temporary file,
     * up to {@code maxBytes}.
     *
     * @return the upload's token, or an {@link UploadRefusal}
     */
    Object addUpload(String name, String contentType, InputStream body, long maxBytes, String token)
            throws IOException {
        touch();
        if (uploads.size() >= MAX_PENDING_UPLOADS) {
            return UploadRefusal.TOO_MANY;
        }
        Path file = Files.createTempFile("jasx-upload-", ".bin");
        long size;
        try (OutputStream out = Files.newOutputStream(file)) {
            size = body.transferTo(new LimitedOutputStream(out, maxBytes));
        } catch (LimitedOutputStream.TooLarge e) {
            Files.deleteIfExists(file);
            return UploadRefusal.TOO_LARGE;
        }
        uploads.put(token, new Upload(name, contentType, size, file));
        return token;
    }

    /** Writes through to another stream, and fails once more than {@code max} bytes come. */
    private static final class LimitedOutputStream extends FilterOutputStream {
        static final class TooLarge extends IOException {
            private static final long serialVersionUID = 1L;
        }

        private final long max;
        private long written;

        LimitedOutputStream(OutputStream out, long max) {
            super(out);
            this.max = max;
        }

        @Override
        public void write(int b) throws IOException {
            count(1);
            out.write(b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            count(length);
            out.write(bytes, offset, length);
        }

        private void count(int length) throws TooLarge {
            written += length;
            if (written > max) {
                throw new TooLarge();
            }
        }
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
        uploads.values().forEach(Upload::delete); // uploads whose event never came
        uploads.clear();
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
