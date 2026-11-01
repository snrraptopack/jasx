package jasx.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One browser connection carrying the streams of several pages (targets/web.md §6.2).
 *
 * <p>Over HTTP/1.1 a browser opens only about six connections per server, and every open stream holds one. So
 * the tabs of an application share one stream: one tab opens it for every tab's page,
 * {@code stream?pages=A:12,B:7}, and passes each frame on to the tab it is for. Each frame names its page:
 * {@code data: {"page":"A","batch":{...}}}.
 *
 * <p>For each page, the connection first sends what the browser missed after the version it reported, merged
 * into one batch (§6.1), then each batch the page pushes. A page is streamed by one connection at a time: when a
 * newer connection takes it (the tabs changed, or the browser reconnected), or the page is discarded, this one
 * lets it go, and once it has no page left it ends.
 */
final class SharedStream {
    /** At most this many pages on one connection; the rest of a longer list is ignored. */
    static final int MAX_PAGES = 100;

    /** Where the connection writes: one server-sent event frame (or comment) at a time. */
    interface FrameSink {
        void send(String frame) throws IOException;
    }

    /** A page this connection streams, and the version up to which the browser has it (or has been sent it). */
    private static final class Member {
        final String id;
        final Page page;
        final int generation;
        long cursor;
        List<BatchLog.Batch> missed = List.of();

        Member(String id, Page page, int generation, long since) {
            this.id = id;
            this.page = page;
            this.generation = generation;
            this.cursor = since;
        }
    }

    private final Object signal = new Object();
    /** Set when one of the pages has a new batch (or lets go), so the connection looks again. */
    private boolean woken;
    private final Runnable wake = this::wake;

    /** The frame carrying {@code batchJson} (a batch, a merged batch, or a reload message) for page {@code pageId}. */
    static String frame(String pageId, String batchJson) {
        return "data: {\"page\":" + Json.write(pageId) + ",\"batch\":" + batchJson + "}\n\n";
    }

    /** The frame that tells the browser to load page {@code pageId} afresh (§6.1). */
    static String reloadFrame(String pageId) {
        return frame(pageId, "{\"reload\":true}");
    }

    /**
     * Streams {@code requested} (page id to the last version its browser applied) until the connection fails or
     * no page is left to stream. Blocks; a comment every {@code heartbeatSeconds} keeps the connection open.
     *
     * <p>A page that is unknown, or that can't catch its browser up, gets a reload frame, and the others go on.
     * Batches that answer events are not pushed: their requests carried them. If one was lost, the browser
     * notices the missing version and asks again, and the catch-up includes it.
     */
    void run(Map<String, Long> requested, Function<String, Page> pages, FrameSink sink, int heartbeatSeconds) {
        List<Member> members = new ArrayList<>();
        try {
            // What each browser missed is taken before the greeting, so that from the greeting on the stream is
            // live: a batch made later is pushed (or, for an event's answer, carried by its request).
            List<String> toReload = new ArrayList<>();
            for (Map.Entry<String, Long> entry : requested.entrySet()) {
                Member member = attach(entry.getKey(), entry.getValue(), pages);
                if (member == null) {
                    toReload.add(entry.getKey());
                } else {
                    members.add(member);
                }
            }
            sink.send(": connected\n\n");
            for (String pageId : toReload) {
                sink.send(reloadFrame(pageId));
            }
            for (Member member : members) {
                sendMissed(member, sink);
            }

            while (!members.isEmpty()) {
                synchronized (signal) {
                    woken = false; // batches made from now on wake the wait below
                }
                letGoOfTakenPages(members);
                for (Member member : members) {
                    sendNewBatches(member, sink);
                }
                if (!members.isEmpty() && !awaitWake(heartbeatSeconds * 1000L)) {
                    sink.send(": ping\n\n");
                }
            }
        } catch (IOException e) {
            // The browser went away; it reconnects if it is still there.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            for (Member member : members) {
                member.page.detachStream(member.generation, wake);
            }
        }
    }

    /** Takes page {@code pageId} for this connection; null if it is unknown or can't catch its browser up. */
    private Member attach(String pageId, long since, Function<String, Page> pages) {
        Page page = pages.apply(pageId);
        if (page == null) {
            return null;
        }
        int generation = page.attachStream(wake);
        if (!page.canCatchUp(since)) {
            page.detachStream(generation, wake);
            return null;
        }
        Member member = new Member(pageId, page, generation, since);
        member.missed = page.batchesAfter(since);
        return member;
    }

    private static void sendMissed(Member member, FrameSink sink) throws IOException {
        if (member.missed.isEmpty()) {
            return;
        }
        sink.send(frame(member.id, BatchLog.merged(member.missed)));
        member.cursor = member.missed.get(member.missed.size() - 1).version();
        member.missed = List.of();
    }

    private static void sendNewBatches(Member member, FrameSink sink) throws IOException {
        for (BatchLog.Batch batch : member.page.batchesAfter(member.cursor)) {
            if (!batch.answer()) {
                sink.send(frame(member.id, batch.json()));
            }
            member.cursor = batch.version();
        }
    }

    /** Drops the pages a newer connection took, or that were discarded. */
    private void letGoOfTakenPages(List<Member> members) {
        members.removeIf(member -> {
            boolean taken = !member.page.isStreamedBy(member.generation);
            if (taken) {
                member.page.detachStream(member.generation, wake);
            }
            return taken;
        });
    }

    private void wake() {
        synchronized (signal) {
            woken = true;
            signal.notifyAll();
        }
    }

    /** Waits until a page wakes the connection, at most {@code timeoutMillis}; false if the time ran out. */
    private boolean awaitWake(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (signal) {
            while (!woken) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                signal.wait(left);
            }
            return true;
        }
    }
}
