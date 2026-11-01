package jasx.web;

import java.util.regex.Pattern;

/**
 * The cookie that ties each page to the browser that rendered it (targets/web.md §6.3).
 *
 * <p>{@link WebApp} gives a browser this cookie with the first page it renders for it, and every page remembers
 * its value. An event, ping or stream that names a page without carrying the same value is treated as naming an
 * unknown page, so a page id that leaks (in a log, a screenshot, a shared link) is of no use to anyone else.
 */
final class SessionCookie {
    static final String NAME = "jasx_session";

    /** What {@link WebApp} issues: 128 random bits, base64url without padding. Anything else is ignored. */
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_-]{22}");

    private SessionCookie() {}

    /** The session in a {@code Cookie} request header, or null if it has none (or a value WebApp never issues). */
    static String read(String cookieHeader) {
        if (cookieHeader == null) {
            return null;
        }
        for (String pair : cookieHeader.split(";")) {
            int equals = pair.indexOf('=');
            if (equals < 0 || !pair.substring(0, equals).strip().equals(NAME)) {
                continue;
            }
            String value = pair.substring(equals + 1).strip();
            return VALUE.matcher(value).matches() ? value : null;
        }
        return null;
    }

    /**
     * The {@code Set-Cookie} header value for a new session. {@code HttpOnly}: page scripts never need it.
     * {@code SameSite=Lax}: browsers send it when following a link from another site (so the page renders in the
     * existing session) but not with requests other sites make in the background. No expiry: it ends with the
     * browser, as its pages do.
     */
    static String header(String session, boolean secure) {
        return NAME + "=" + session + "; Path=/; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : "");
    }
}
