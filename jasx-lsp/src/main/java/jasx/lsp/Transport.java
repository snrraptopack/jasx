package jasx.lsp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads and writes Language Server Protocol messages on a pair of streams (normally stdin and stdout).
 *
 * <p>Each message is a header part and a JSON body, separated by an empty line:
 *
 * <pre>
 *   Content-Length: 52\r\n
 *   \r\n
 *   {"jsonrpc":"2.0","method":"initialized","params":{}}
 * </pre>
 *
 * The length is in bytes of the UTF-8 body. Other headers are allowed and ignored.
 */
final class Transport {
    private final InputStream in;
    private final OutputStream out;

    Transport(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /** The next message's JSON body, or null at the end of the input. */
    String read() throws IOException {
        int contentLength = -1;
        while (true) {
            String header = readHeaderLine();
            if (header == null) {
                return null; // the client closed the stream
            }
            if (header.isEmpty()) {
                break; // end of the headers
            }
            int colon = header.indexOf(':');
            boolean isLength = colon > 0
                    && header.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("content-length");
            if (isLength) {
                contentLength = Integer.parseInt(header.substring(colon + 1).trim());
            }
        }
        if (contentLength < 0) {
            throw new IOException("a message without Content-Length");
        }
        byte[] body = in.readNBytes(contentLength);
        if (body.length < contentLength) {
            return null;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    /** One header line without its CR LF, or null at the end of the input. */
    private String readHeaderLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
            }
            if (b == '\n') {
                String text = line.toString(StandardCharsets.US_ASCII);
                return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
            }
            line.write(b);
        }
    }

    /** Writes one message. Synchronized, because diagnostics may be sent while a request is answered. */
    synchronized void write(String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
