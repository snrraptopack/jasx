package jasx.web.server;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The request for a page, as a page function of {@link WebServer} receives it: its path and query. */
public final class WebRequest {
    private final String path;
    private final Map<String, List<String>> query;

    WebRequest(URI uri) {
        this.path = uri.getPath();
        this.query = parseQuery(uri.getRawQuery());
    }

    /** The path, such as {@code /todos}. */
    public String path() {
        return path;
    }

    /** The first value of query parameter {@code name}, or null if there is none. */
    public String query(String name) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** Every value of query parameter {@code name}. */
    public List<String> queryAll(String name) {
        return query.getOrDefault(name, List.of());
    }

    static Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String name = decode(equals >= 0 ? pair.substring(0, equals) : pair);
            String value = equals >= 0 ? decode(pair.substring(equals + 1)) : "";
            result.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return result;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
