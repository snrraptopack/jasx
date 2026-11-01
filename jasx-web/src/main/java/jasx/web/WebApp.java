package jasx.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The pages of an application, independent of any HTTP server (targets/web.md §6, §8). A server adapter
 * ({@link jasx.web.server.WebServer} for the JDK's HTTP server, or one for servlets or Spring) routes four kinds
 * of request to it, each described by a {@link Request}:
 *
 * <ul>
 *   <li>a page: {@link #render} renders it and keeps it alive;
 *   <li>{@code POST <basePath>/event?page=ID}: {@link #event};
 *   <li>{@code GET <basePath>/stream?pages=ID:N,ID:N}: {@link #stream}, one connection for every tab;
 *   <li>{@code POST <basePath>/ping?page=ID}: {@link #ping};
 * </ul>
 *
 * and serves {@link #clientScript()} at {@code GET <basePath>/web.js}. The adapter sends each {@link Response}
 * as it is: status, headers and body.
 *
 * <p>{@code WebApp} also does what keeps pages safe (§6.3), so every adapter gets it: each page belongs to the
 * browser session that rendered it (a cookie it issues), events must be JSON from the same site, and requests
 * have {@linkplain Limits limits}.
 *
 * <p>Pages that have had no stream and no event for the page timeout are discarded by a background thread.
 */
public final class WebApp implements AutoCloseable {
    /**
     * A request as the adapter received it: just what {@code WebApp} reads. Header names are case-insensitive.
     */
    public interface Request {
        /** The first value of query parameter {@code name}, decoded, or null. */
        String query(String name);

        /** The first value of header {@code name}, or null. */
        String header(String name);

        /**
         * Whether the browser reached the server over HTTPS, directly or through a proxy that says so. The session
         * cookie is then marked {@code Secure}.
         */
        boolean secure();
    }

    /** The answer to send: status, headers (such as {@code Content-Type} and {@code Set-Cookie}) and body. */
    public record Response(int status, String body, Map<String, String> headers) {
        public Response(int status, String body) {
            this(status, body, Map.of());
        }

        static final Response NOTHING_CHANGED = new Response(204, "");
        static final Response BAD_REQUEST = new Response(400, "");
        static final Response FORBIDDEN = new Response(403, "");
        static final Response GONE = new Response(410, "");
        static final Response TOO_LARGE = new Response(413, "");
        static final Response NOT_JSON = new Response(415, "");

        static Response json(String body) {
            return new Response(200, body, Map.of("Content-Type", "application/json", "Cache-Control", "no-store"));
        }
    }

    /** Where {@link #stream} writes: the adapter starts the response in {@link #open}, then sends frames. */
    public interface EventStream {
        /** Answers status 200 with {@code Content-Type: text/event-stream} and {@code Cache-Control: no-store}. */
        void open() throws IOException;

        /** Writes and flushes one server-sent event frame (or comment). */
        void send(String frame) throws IOException;
    }

    /**
     * What one browser may ask of the server (targets/web.md §6.3).
     *
     * @param maxEventBytes      the largest event body accepted; adapters stop reading there, and answer 413
     * @param maxPagesPerSession the most pages one browser session keeps alive; rendering one more discards its
     *                           oldest, so a script requesting pages in a loop can't fill the server's memory
     */
    public record Limits(int maxEventBytes, int maxPagesPerSession) {
        public static final Limits DEFAULT = new Limits(1024 * 1024, 50);
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int HEARTBEAT_SECONDS = 25;

    /** How long a page waits for its browser to connect, by default (targets/web.md §6). */
    public static final Duration DEFAULT_FIRST_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The session of pages made by the package's own trusted calls (the tests'), which no browser can present: it
     * is random, and a browser's cookie is always one {@code WebApp} issued to it.
     */
    private static final String IN_PROCESS_SESSION = newId();

    private final String basePath;
    private final long pageTimeoutNanos;
    private final long firstConnectTimeoutNanos;
    private final Limits limits;
    private final Map<String, Page> pages = new ConcurrentHashMap<>();
    /** Each session's pages, oldest first, for {@link Limits#maxPagesPerSession}. */
    private final Map<String, Deque<Page>> pagesBySession = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    /** An application with the base path {@code /_jasx} and a page timeout of 2 minutes. */
    public WebApp() {
        this("/_jasx", Duration.ofMinutes(2));
    }

    /** An application whose pages wait 30 seconds (or the page timeout, if shorter) for their browser. */
    public WebApp(String basePath, Duration pageTimeout) {
        this(basePath, pageTimeout, min(DEFAULT_FIRST_CONNECT_TIMEOUT, pageTimeout));
    }

    public WebApp(String basePath, Duration pageTimeout, Duration firstConnectTimeout) {
        this(basePath, pageTimeout, firstConnectTimeout, Limits.DEFAULT);
    }

    /**
     * @param pageTimeout         how long a page is kept after its browser goes away (no stream, no event)
     * @param firstConnectTimeout how long a page is kept if no browser ever connects to it: a page rendered for
     *                            a crawler, a link preview, a prefetch or curl never runs its script
     * @param limits              what one browser may ask of the server
     */
    public WebApp(String basePath, Duration pageTimeout, Duration firstConnectTimeout, Limits limits) {
        this.basePath = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        this.pageTimeoutNanos = pageTimeout.toNanos();
        this.firstConnectTimeoutNanos = firstConnectTimeout.toNanos();
        this.limits = limits;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("jasx-pages").factory());
        Duration shortest = min(pageTimeout, firstConnectTimeout);
        long every = Math.max(1, Math.min(30, shortest.toSeconds() / 2));
        sweeper.scheduleWithFixedDelay(this::discardExpiredPages, every, every, TimeUnit.SECONDS);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** Where the client script, events and streams are served, such as {@code /_jasx}. */
    public String basePath() {
        return basePath;
    }

    public Limits limits() {
        return limits;
    }

    // ---------------------------------------------------------------------------------------------
    // The four requests (targets/web.md §8.1)
    // ---------------------------------------------------------------------------------------------

    /**
     * Renders a new page for the browser of {@code request} and keeps it alive (targets/web.md §6). {@code root} is
     * called once, with the page's lock held, so components can create state.
     *
     * <p>A browser without a session gets one: the response sets its cookie (§6.3). Pages are not refused to other
     * sites, because following a link to a page is normal; what a page can do afterwards is bound to the session.
     *
     * @return status 200 with the page's HTML, the client script inserted
     */
    public Response render(Request request, Supplier<?> root) {
        String session = sessionOf(request);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/html; charset=utf-8");
        headers.put("Cache-Control", "no-store");
        if (session == null) {
            session = newId();
            headers.put("Set-Cookie", SessionCookie.header(session, request.secure()));
        }
        return new Response(200, render(root, session), headers);
    }

    /**
     * Handles {@code POST <basePath>/event?page=ID} with the request body {@code body} (targets/web.md §8.3).
     *
     * @return the batch the event produced (200), 204 if nothing changed, 410 if the page is unknown to this
     *         browser, 400 for a malformed body, 403 for a request from another site, 413 for a body over the
     *         limit, 415 for a body that isn't declared as JSON
     */
    public Response event(Request request, String body) {
        if (isFromAnotherSite(request)) {
            return Response.FORBIDDEN;
        }
        if (!isJson(request.header("Content-Type"))) {
            return Response.NOT_JSON;
        }
        if (body.length() > limits.maxEventBytes()) {
            return Response.TOO_LARGE;
        }
        return event(pageFor(request.query("page"), sessionOf(request)), body);
    }

    /**
     * Handles {@code GET <basePath>/stream?pages=A:12,B:7} (targets/web.md §6.1, §6.2, §8.1): one connection for
     * the pages of every tab of a browser. For each page, it sends the batches after the version its browser
     * applied, merged into one, then the page's pushed batches, each frame naming its page. Blocks until the
     * connection fails or newer connections have taken all its pages, so call it on a thread that may block (a
     * virtual thread).
     *
     * <p>A page that is unknown to this browser, or that can't catch it up, gets a frame telling its tab to reload.
     *
     * @return empty once the stream has been served; or the response to send instead, before {@code out} was
     *         touched: 403 for a request from another site
     */
    public Optional<Response> stream(Request request, EventStream out) throws IOException {
        if (isFromAnotherSite(request)) {
            return Optional.of(Response.FORBIDDEN);
        }
        String session = sessionOf(request);
        out.open();
        new SharedStream().run(streamPages(request.query("pages")), pageId -> pageFor(pageId, session),
                out::send, HEARTBEAT_SECONDS);
        return Optional.empty();
    }

    /**
     * Handles {@code POST <basePath>/ping?page=ID} (targets/web.md §6): in a browser whose tabs can't share a
     * stream (§6.2), a tab that is hidden closes its own, so that hidden tabs don't use up the browser's few
     * connections per server, and pings instead to keep its page alive.
     *
     * @return 204; 410 if the page is unknown to this browser; 403 for a request from another site
     */
    public Response ping(Request request) {
        if (isFromAnotherSite(request)) {
            return Response.FORBIDDEN;
        }
        return ping(pageFor(request.query("page"), sessionOf(request)));
    }

    /**
     * Reads the {@code pages} query parameter of a stream request, {@code A:12,B:7}: page ids with the last
     * version each browser applied. A missing or malformed version reads as 0 (the start); entries without an id,
     * and those beyond the first {@value SharedStream#MAX_PAGES}, are ignored.
     */
    static Map<String, Long> streamPages(String parameter) {
        Map<String, Long> pages = new LinkedHashMap<>();
        if (parameter == null) {
            return pages;
        }
        for (String entry : parameter.split(",")) {
            int colon = entry.lastIndexOf(':');
            String pageId = colon < 0 ? entry : entry.substring(0, colon);
            String version = colon < 0 ? "" : entry.substring(colon + 1);
            if (pageId.isEmpty()) {
                continue;
            }
            if (pages.size() == SharedStream.MAX_PAGES) {
                break;
            }
            pages.put(pageId, parseVersion(version));
        }
        return pages;
    }

    private static long parseVersion(String text) {
        try {
            return text.isEmpty() ? 0 : Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Whether the page {@code pageId} is alive. */
    public boolean hasPage(String pageId) {
        return pageId != null && pages.containsKey(pageId);
    }

    /** The number of pages alive. */
    public int pageCount() {
        return pages.size();
    }

    /** The browser client, served at {@code <basePath>/web.js}. */
    public static String clientScript() {
        return ClientScript.TEXT;
    }

    /** Stops the background thread and discards every page. */
    @Override
    public void close() {
        sweeper.shutdownNow();
        pages.values().forEach(Page::discard);
        pages.clear();
        pagesBySession.clear();
    }

    // ---------------------------------------------------------------------------------------------
    // Requests from the package itself, trusted: the tests call these without HTTP (no session checks)
    // ---------------------------------------------------------------------------------------------

    /** Renders a page in the in-process session; returns its HTML. */
    String render(Supplier<?> root) {
        return render(root, IN_PROCESS_SESSION);
    }

    Response event(String pageId, String body) {
        return event(pageFor(pageId, IN_PROCESS_SESSION), body);
    }

    Response ping(String pageId) {
        return ping(pageFor(pageId, IN_PROCESS_SESSION));
    }

    void stream(Map<String, Long> requested, SharedStream.FrameSink sink) {
        new SharedStream().run(requested, pageId -> pageFor(pageId, IN_PROCESS_SESSION), sink, HEARTBEAT_SECONDS);
    }

    // ---------------------------------------------------------------------------------------------
    // Pages and sessions (targets/web.md §6, §6.3)
    // ---------------------------------------------------------------------------------------------

    private String render(Supplier<?> root, String session) {
        Page page = new Page(newId(), session);
        pages.put(page.id, page);
        addToSession(page);
        String scriptTag = "<script src=\"" + basePath + "/web.js\" data-jx-page=\"" + page.id + "\" defer></script>";
        try {
            return page.render(root, scriptTag);
        } catch (RuntimeException | Error e) {
            // What rendered before the failure may already follow shared state: dispose it, or that state would
            // keep the page alive (and the sweeper no longer sees it).
            try {
                discard(page);
            } catch (RuntimeException | Error cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    private Response event(Page page, String body) {
        if (page == null) {
            return Response.GONE;
        }
        Map<String, Object> event;
        try {
            event = Json.parseObject(body);
        } catch (IllegalArgumentException | ClassCastException e) {
            return Response.BAD_REQUEST;
        }
        if (!(event.get("handler") instanceof Number)) {
            return Response.BAD_REQUEST;
        }
        String batch = page.event(event);
        return batch != null ? Response.json(batch) : Response.NOTHING_CHANGED;
    }

    private static Response ping(Page page) {
        if (page == null) {
            return Response.GONE;
        }
        page.touch();
        return Response.NOTHING_CHANGED;
    }

    /**
     * The page {@code pageId} if the request's session rendered it; otherwise null, exactly as for a page that
     * doesn't exist, so an answer never tells whether a page id is real.
     */
    private Page pageFor(String pageId, String session) {
        Page page = pageId != null ? pages.get(pageId) : null;
        return page != null && page.belongsTo(session) ? page : null;
    }

    /** Keeps the page in its session's list, discarding the session's oldest page beyond the limit. */
    private void addToSession(Page page) {
        Page[] overflow = {null};
        pagesBySession.compute(page.session(), (session, sessionPages) -> {
            Deque<Page> list = sessionPages != null ? sessionPages : new ArrayDeque<>();
            list.addLast(page);
            if (list.size() > limits.maxPagesPerSession()) {
                overflow[0] = list.removeFirst();
            }
            return list;
        });
        if (overflow[0] != null) {
            discard(overflow[0]);
        }
    }

    private void discard(Page page) {
        pages.remove(page.id);
        pagesBySession.computeIfPresent(page.session(), (session, sessionPages) -> {
            sessionPages.remove(page);
            return sessionPages.isEmpty() ? null : sessionPages;
        });
        page.discard();
    }

    private void discardExpiredPages() {
        for (Page page : pages.values()) {
            if (page.isExpired(pageTimeoutNanos, firstConnectTimeoutNanos)) {
                discard(page);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Request checks (targets/web.md §6.3)
    // ---------------------------------------------------------------------------------------------

    private static String sessionOf(Request request) {
        return SessionCookie.read(request.header("Cookie"));
    }

    /**
     * Whether the browser says the request comes from another site ({@code Sec-Fetch-Site}). The client only
     * ever calls its own origin, so anything but {@code same-origin} (or {@code none}: typed by the user) is
     * another page's doing. Browsers that don't send the header are covered by the other checks.
     */
    private static boolean isFromAnotherSite(Request request) {
        String site = request.header("Sec-Fetch-Site");
        return site != null && !site.equals("same-origin") && !site.equals("none");
    }

    /**
     * Whether a body is declared as JSON. A form on another site can only send form encodings or plain text
     * without the browser asking the server first (CORS), and this server never says yes.
     */
    private static boolean isJson(String contentType) {
        return contentType != null && contentType.strip().toLowerCase(Locale.ROOT).startsWith("application/json");
    }

    /** 128 random bits: page ids and sessions are what let a browser act on a page (targets/web.md §6.3). */
    private static String newId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The client script, read once from the jar. */
    private static final class ClientScript {
        static final String TEXT = read();

        private static String read() {
            try (InputStream in = WebApp.class.getResourceAsStream("/jasx/web/web.js")) {
                if (in == null) {
                    throw new IllegalStateException("jasx/web/web.js is missing from the jasx-web jar");
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
