package web;

import jasx.web.State;
import jasx.web.Web;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Shared state that changes without any event of the page that shows it: chat messages posted from other
 * windows, and a clock ticking on a background thread. Both are pushed to every open page (targets/web.md §5).
 */
public final class ChatRoom {
    /** One message. */
    public record Message(String time, String name, String text) {}

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int KEPT = 20;

    private final State<List<Message>> messages = Web.state(List.of());
    private final State<String> clock = Web.state(now());

    public ChatRoom() {
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "clock");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleAtFixedRate(() -> clock.set(now()), 1, 1, TimeUnit.SECONDS);
    }

    public List<Message> messages() {
        return messages.get();
    }

    public String clock() {
        return clock.get();
    }

    /** Posts a message; the newest is first, and only the last {@value #KEPT} are kept. */
    public synchronized void post(String name, String text) {
        List<Message> next = new ArrayList<>();
        next.add(new Message(now(), name, text));
        next.addAll(messages.get());
        messages.set(List.copyOf(next.subList(0, Math.min(KEPT, next.size()))));
    }

    private static String now() {
        return LocalTime.now().format(TIME);
    }
}
