package jasx.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A file the user chose in a form's file field (jasx-spec/ui.md §4), as a {@link FormEvent} gives it:
 * {@code e.file("photo")}, or a record component of this type with {@link FormEvent#as}.
 *
 * <p>Its content is available while the event's handler runs; save what you need there ({@link #saveTo}).
 */
public interface UploadedFile {
    /** The file's name, as the user's system named it (without its folders). */
    String name();

    /** Its media type as the browser gave it, such as {@code image/png}; {@code application/octet-stream} if unknown. */
    String contentType();

    /** Its size in bytes. */
    long size();

    /** Opens its content. */
    InputStream open() throws IOException;

    /** Its whole content. */
    default byte[] bytes() throws IOException {
        try (InputStream in = open()) {
            return in.readAllBytes();
        }
    }

    /** Copies its content to {@code target}, replacing a file that is there. */
    default void saveTo(Path target) throws IOException {
        try (InputStream in = open()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
