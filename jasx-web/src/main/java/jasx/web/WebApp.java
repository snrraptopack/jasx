package jasx.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The pages of an application, independent of any HTTP server (targets/web.md §6, §8). A server adapter
 * ({@link jasx.web.server.WebServer} for the JDK's HTTP server, or one for Spring) calls it for the four
 * kinds of request:
 *
 * <ul>
 *   <li>a page: {@link #render} renders it and keeps it alive;
 *   <li>{@code POST <basePath>/event?page=ID}: {@link #event};
 *   <li>{@code GET <basePath>/stream?pages=ID:N,ID:N}: {@link #stream}, one connection for every tab;
 *   <li>{@code POST <basePath>/ping?page=ID}: {@link #ping};
 * </ul>
 *
 * and serves {@link #clientScript()} at {@code GET <basePath>/web.js}.
 *
 * <p>Pages that have had no stream and no event for the page timeout are discarded by a background thread.
 */
public final class WebApp implements AutoCloseable {
    /** The answer to an event or a stream request that needs no body, or names an unknown page. */
    public record Response(int status, String body) {
        static final Response NOTHING_CHANGED = new Response(204, "");
        static final Response GONE = new Response(410, "");
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int HEARTBEAT_SECONDS = 25;

    /** How long a page waits for its browser to connect, by default (targets/web.md §6). */
    public static final Duration DEFAULT_FIRST_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final String basePath;
    private final long pageTimeoutNanos;
    private final long firstConnectTimeoutNanos;
    private final Map<String, Page> pages = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    /** An application with the base path {@code /_jasx} and a page timeout of 2 minutes. */
    public WebApp() {
        this("/_jasx", Duration.ofMinutes(2));
    }

    /** An application whose pages wait 30 seconds (or the page timeout, if shorter) for their browser. */
    public WebApp(String basePath, Duration pageTimeout) {
        this(basePath, pageTimeout, min(DEFAULT_FIRST_CONNECT_TIMEOUT, pageTimeout));
    }

    /**
     * @param pageTimeout         how long a page is kept after its browser goes away (no stream, no event)
     * @param firstConnectTimeout how long a page is kept if no browser ever connects to it: a page rendered for
     *                            a crawler, a link preview, a prefetch or curl never runs its script
     */
    public WebApp(String basePath, Duration pageTimeout, Duration firstConnectTimeout) {
        this.basePath = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        this.pageTimeoutNanos = pageTimeout.toNanos();
        this.firstConnectTimeoutNanos = firstConnectTimeout.toNanos();
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

    /**
     * Renders a new page and keeps it alive (targets/web.md §6). {@code root} is called once, with the page's
     * lock held, so components can create state.
     *
     * @return the page's HTML, with the client script inserted
     */
    public String render(Supplier<?> root) {
        Page page = new Page(newPageId());
        pages.put(page.id, page);
        String scriptTag = "<script src=\"" + basePath + "/web.js\" data-jx-page=\"" + page.id + "\" defer></script>";
        try {
            return page.render(root, scriptTag);
        } catch (RuntimeException | Error e) {
            // What rendered before the failure may already follow shared state: dispose it, or that state would
            // keep the page alive (and the sweeper no longer sees it).
            pages.remove(page.id);
            try {
                page.discard();
            } catch (RuntimeException | Error cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    /** Handles {@code POST <basePath>/event?page=ID} with the request body {@code json} (targets/web.md §8.3). */
    public Response event(String pageId, String json) {
        Page page = pageId != null ? pages.get(pageId) : null;
        if (page == null) {
            return Response.GONE;
        }
        Map<String, Object> event;
        try {
            event = Json.parseObject(json);
        } catch (IllegalArgumentException | ClassCastException e) {
            return new Response(400, "");
        }
        if (!(event.get("handler") instanceof Number)) {
            return new Response(400, "");
        }
        String batch = page.event(event);
        return batch != null ? new Response(200, batch) : Response.NOTHING_CHANGED;
    }

    /** Where {@link #stream} writes server-sent event frames: {@code data: ...} and comments. */
    public interface StreamSink {
        void send(String frame) throws IOException;
    }

    /**
     * Handles {@code GET <basePath>/stream?pages=A:12,B:7} (targets/web.md §6.1, §6.2, §8.1): one connection
     * for the pages of every tab of a browser. For each page, it sends the batches after the version its browser
     * applied, merged into one, then the page's pushed batches, each frame naming its page. It returns when the
     * connection fails or newer connections have taken all its pages. Blocks, so call it on a thread that may
     * block (a virtual thread). Answer with status 200 and {@code Content-Type: text/event-stream} before
     * calling it.
     *
     * <p>A page that is unknown, or that can't catch its browser up, gets a frame telling its tab to reload.
     *
     * @param pages each page's id and the last version its browser applied: {@link #streamPages} reads them
     */
    public void stream(Map<String, Long> pages, StreamSink sink) {
        new SharedStream().run(pages, this.pages::get, sink, HEARTBEAT_SECONDS);
    }

    /**
     * Reads the {@code pages} query parameter of a stream request, {@code A:12,B:7}: page ids with the last
     * version each browser applied. A missing or malformed version reads as 0 (the start); entries without an id,
     * and those beyond the first {@value SharedStream#MAX_PAGES}, are ignored.
     */
    public static Map<String, Long> streamPages(String parameter) {
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

    /**
     * Handles {@code POST <basePath>/ping?page=ID} (targets/web.md §6): in a browser whose tabs can't share a
     * stream (§6.2), a tab that is hidden closes its own, so that hidden tabs don't use up the browser's few
     * connections per server, and pings instead to keep its page alive.
     *
     * @return 204, or 410 for an unknown page
     */
    public Response ping(String pageId) {
        Page page = pageId != null ? pages.get(pageId) : null;
        if (page == null) {
            return Response.GONE;
        }
        page.touch();
        return Response.NOTHING_CHANGED;
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
    }

    private void discardExpiredPages() {
        pages.values().removeIf(page -> {
            boolean expired = page.isExpired(pageTimeoutNanos, firstConnectTimeoutNanos);
            if (expired) {
                page.discard();
            }
            return expired;
        });
    }

    /** 128 random bits: the page id is what lets a browser act on the page (targets/web.md §6). */
    private static String newPageId() {
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
