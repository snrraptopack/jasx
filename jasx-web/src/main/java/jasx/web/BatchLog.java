package jasx.web;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The batches of one page, numbered and kept for a while, so that a browser that missed some can catch up
 * (targets/web.md §6.1, §8.4).
 *
 * <p>Every batch goes in, whether it answers an event or is pushed. A stream sends the pushed ones as they come,
 * and when a browser (re)connects saying which version it applied last, it first receives everything after that
 * as one merged batch. Writing a batch to a socket doesn't prove the browser received it, and an event's answer
 * can be lost after its handler ran, so delivery is only ever settled by the version the browser reports.
 *
 * <p>The log is bounded. A browser that is further behind than the log reaches is told to reload.
 */
final class BatchLog {
    /** At most this many batches are kept... */
    static final int MAX_BATCHES = 1000;
    /** ...and at most this many characters of them. */
    static final long MAX_CHARS = 4L * 1024 * 1024;

    /** One batch: its patches, the JSON sent for it alone, and whether it answered an event. */
    record Batch(long version, List<Map<String, Object>> patches, String json, boolean answer) {}

    private final ArrayDeque<Batch> history = new ArrayDeque<>();
    private long lastVersion;
    private long chars;

    /**
     * Numbers {@code patches} as the next batch, keeps it, and wakes the streams waiting for it.
     *
     * @param answer whether it answers an event (its event request carries it, so streams don't push it)
     * @return the batch's JSON, {@code {"version":N,"patches":[...]}}
     */
    synchronized String add(List<Map<String, Object>> patches, boolean answer) {
        long version = ++lastVersion;
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("version", version);
        message.put("patches", patches);
        String json = Json.write(message);

        history.addLast(new Batch(version, patches, json, answer));
        chars += json.length();
        while (history.size() > 1 && (history.size() > MAX_BATCHES || chars > MAX_CHARS)) {
            chars -= history.removeFirst().json().length();
        }
        notifyAll();
        return json;
    }

    /** The version of the last batch, 0 if there is none. */
    synchronized long lastVersion() {
        return lastVersion;
    }

    /**
     * Whether a browser that applied every batch up to {@code since} can catch up from the log: it has applied
     * them all, or the log still holds the first one it is missing.
     */
    synchronized boolean covers(long since) {
        if (since > lastVersion || since < 0) {
            return false; // a version this page never sent: the browser belongs to another page
        }
        long oldestKept = history.isEmpty() ? lastVersion + 1 : history.peekFirst().version();
        return since == lastVersion || since + 1 >= oldestKept;
    }

    /** The batches after {@code version}, in order. */
    synchronized List<Batch> after(long version) {
        List<Batch> result = new ArrayList<>();
        for (Batch batch : history) {
            if (batch.version() > version) {
                result.add(batch);
            }
        }
        return result;
    }

    /**
     * The batches after {@code version}, waiting up to {@code timeoutMillis} for one if there is none yet.
     *
     * @return the new batches, or an empty list after the timeout
     */
    synchronized List<Batch> awaitAfter(long version, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (lastVersion <= version) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return List.of();
            }
            wait(left);
        }
        return after(version);
    }

    /** Wakes every waiting stream, so a stream that was replaced or a discarded page stops at once. */
    synchronized void wakeAll() {
        notifyAll();
    }

    /**
     * Several batches as one, {@code {"from":A,"version":B,"patches":[...]}}, for a browser catching up.
     *
     * <p>A patch is left out when a later one in the merge makes it pointless: an {@code html} patch of a hole
     * replaced again later, or an {@code attr} patch of an attribute set again later. Applying the rest in order
     * gives the same page, because the later patch overwrites whatever the dropped one did, and patches aimed at
     * holes inside replaced content find nothing and are ignored by the client anyway (§8.4). So a browser that
     * slept through a minute of a clock receives one value, not sixty.
     */
    static String merged(List<Batch> batches) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Batch batch : batches) {
            all.addAll(batch.patches());
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("from", batches.get(0).version());
        message.put("version", batches.get(batches.size() - 1).version());
        message.put("patches", withoutSuperseded(all));
        return Json.write(message);
    }

    /** {@code patches} without those a later patch supersedes (see {@link #merged}). */
    private static List<Map<String, Object>> withoutSuperseded(List<Map<String, Object>> patches) {
        Set<String> seenLater = new HashSet<>();
        List<Map<String, Object>> keptReversed = new ArrayList<>();
        for (int i = patches.size() - 1; i >= 0; i--) {
            Map<String, Object> patch = patches.get(i);
            String target = supersedingKey(patch);
            if (target != null && !seenLater.add(target)) {
                continue; // a later patch overwrites this one
            }
            keptReversed.add(patch);
        }
        List<Map<String, Object>> kept = new ArrayList<>(keptReversed.size());
        for (int i = keptReversed.size() - 1; i >= 0; i--) {
            kept.add(keptReversed.get(i));
        }
        return kept;
    }

    /** What a patch overwrites entirely, or null if it must always be applied (css, navigate, reset). */
    private static String supersedingKey(Map<String, Object> patch) {
        return switch (String.valueOf(patch.get("op"))) {
            case "html" -> "html " + patch.get("id");
            case "attr" -> "attr " + patch.get("id") + " " + patch.get("name");
            default -> null;
        };
    }
}
