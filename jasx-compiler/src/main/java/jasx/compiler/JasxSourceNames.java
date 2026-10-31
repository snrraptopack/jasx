package jasx.compiler;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Makes compiled classes name their {@code .jasx} source file, so that stack traces and debuggers point at
 * the file the user wrote: {@code at app.Pages.Card(Pages.jasx:12)} rather than {@code Pages.java:12}.
 *
 * <p>javac records the name of the source file in each class file, in its {@code SourceFile} attribute
 * (JVM specification §4.7.10): {@code Pages.java} for classes compiled from the generated
 * {@code app/Pages.java}. Because lowering keeps every line where it was (semantics.md §6.5), the line
 * numbers javac records are already the {@code .jasx} line numbers; only the file name is wrong. This class
 * replaces it with {@code Pages.jasx}, after compilation.
 *
 * <p>It is not a class file library. It reads just enough of the format to find the {@code SourceFile}
 * attribute: the constant pool, then the fields and methods (skipped), then the class's attributes. The new
 * name is added as a new constant at the end of the pool, so nothing else in the class changes.
 */
public final class JasxSourceNames {
    private JasxSourceNames() {}

    /** The {@code CONSTANT_Utf8} tag, the only constant whose contents are read here. */
    private static final int UTF8 = 1;

    /**
     * For every class file under {@code classesDirectory} compiled from one of {@code generatedFiles} (paths
     * of generated Java files relative to the source root, such as {@code app/Pages.java}), replaces the
     * source file name with the {@code .jasx} name. Returns how many class files were changed.
     *
     * <p>A class belongs to {@code app/Pages.java} if it is in the directory {@code app} and its source file
     * name is {@code Pages.java}: that covers its nested, local and anonymous classes too. Running this again
     * changes nothing, because the names then end in {@code .jasx}.
     */
    public static int rewrite(Path classesDirectory, Set<String> generatedFiles) {
        if (!Files.isDirectory(classesDirectory) || generatedFiles.isEmpty()) {
            return 0;
        }
        // Only the package directories of generated files can hold their classes: look in those alone.
        Set<String> packageDirectories = new TreeSet<>();
        for (String generated : generatedFiles) {
            int slash = generated.lastIndexOf('/');
            packageDirectories.add(slash < 0 ? "" : generated.substring(0, slash));
        }
        List<Path> classFiles = new ArrayList<>();
        for (String packageDirectory : packageDirectories) {
            Path directory = classesDirectory.resolve(packageDirectory);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> list = Files.list(directory)) {
                list.filter(p -> p.toString().endsWith(".class")).forEach(classFiles::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        int changed = 0;
        for (Path classFile : classFiles) {
            try {
                byte[] bytes = Files.readAllBytes(classFile);
                String sourceFile = sourceFile(bytes);
                if (sourceFile == null || !sourceFile.endsWith(".java")) {
                    continue;
                }
                Path directory = classesDirectory.relativize(classFile).getParent();
                String packagePath = directory == null ? "" : directory.toString().replace('\\', '/') + "/";
                if (generatedFiles.contains(packagePath + sourceFile)) {
                    String jasxName = sourceFile.substring(0, sourceFile.length() - ".java".length()) + ".jasx";
                    Files.write(classFile, withSourceFile(bytes, jasxName));
                    changed++;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return changed;
    }

    /** The source file name recorded in a class file, or null if it has none. */
    public static String sourceFile(byte[] classFile) {
        ClassLayout layout = ClassLayout.read(classFile);
        return layout.sourceFileIndexOffset < 0 ? null : layout.utf8(layout.readU2(layout.sourceFileIndexOffset));
    }

    /**
     * The class file with its source file name replaced by {@code name}. A class without a
     * {@code SourceFile} attribute is returned unchanged.
     */
    public static byte[] withSourceFile(byte[] classFile, String name) {
        ClassLayout layout = ClassLayout.read(classFile);
        if (layout.sourceFileIndexOffset < 0) {
            return classFile;
        }
        int newIndex = layout.constantCount; // the next free index in the constant pool

        ByteArrayOutputStream out = new ByteArrayOutputStream(classFile.length + name.length() + 3);
        try (DataOutputStream data = new DataOutputStream(out)) {
            data.write(classFile, 0, 8);                 // magic, minor and major version
            data.writeShort(layout.constantCount + 1);   // one more constant
            data.write(classFile, 10, layout.poolEnd - 10);
            data.writeByte(UTF8);                         // the new constant: the .jasx name
            data.writeUTF(name);                          // u2 length + modified UTF-8, as class files use
            // The rest of the class, with the SourceFile attribute pointing at the new constant.
            data.write(classFile, layout.poolEnd, layout.sourceFileIndexOffset - layout.poolEnd);
            data.writeShort(newIndex);
            int afterIndex = layout.sourceFileIndexOffset + 2;
            data.write(classFile, afterIndex, classFile.length - afterIndex);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // writing to memory doesn't fail
        }
        return out.toByteArray();
    }

    /**
     * Where things are in one class file (JVM specification §4.1): the constant pool's entries and end, and
     * the offset of the {@code SourceFile} attribute's constant index ({@code -1} if there is none).
     */
    private static final class ClassLayout {
        final byte[] bytes;
        /** {@code constant_pool_count}: one more than the number of constant slots. */
        int constantCount;
        /** The offset of each constant, by index (index 0 is unused, as are the slots after a long or double). */
        int[] constantOffsets;
        int poolEnd;
        int sourceFileIndexOffset = -1;

        private ClassLayout(byte[] bytes) {
            this.bytes = bytes;
        }

        static ClassLayout read(byte[] bytes) {
            ClassLayout layout = new ClassLayout(bytes);
            if (bytes.length < 10 || layout.readU2(0) != 0xCAFE || layout.readU2(2) != 0xBABE) {
                throw new IllegalArgumentException("not a class file");
            }
            layout.readConstantPool();
            layout.findSourceFileAttribute();
            return layout;
        }

        private void readConstantPool() {
            constantCount = readU2(8);
            constantOffsets = new int[constantCount];
            int offset = 10;
            for (int index = 1; index < constantCount; index++) {
                constantOffsets[index] = offset;
                int tag = bytes[offset] & 0xFF;
                offset += 1 + constantSize(tag, offset + 1);
                boolean takesTwoSlots = tag == 5 || tag == 6; // CONSTANT_Long, CONSTANT_Double (§4.4.5)
                if (takesTwoSlots) {
                    index++;
                }
            }
            poolEnd = offset;
        }

        /** The size of a constant's contents after its tag (JVM specification §4.4). */
        private int constantSize(int tag, int contentStart) {
            return switch (tag) {
                case UTF8 -> 2 + readU2(contentStart);
                case 7, 8, 16, 19, 20 -> 2;   // Class, String, MethodType, Module, Package
                case 15 -> 3;                 // MethodHandle
                case 3, 4, 9, 10, 11, 12, 17, 18 -> 4; // Integer, Float, refs, NameAndType, Dynamic, InvokeDynamic
                case 5, 6 -> 8;               // Long, Double
                default -> throw new IllegalArgumentException("unknown constant pool tag " + tag);
            };
        }

        private void findSourceFileAttribute() {
            int offset = poolEnd + 6;                    // access_flags, this_class, super_class
            offset += 2 + 2 * readU2(offset);            // interfaces
            offset = skipMembers(offset);                // fields
            offset = skipMembers(offset);                // methods
            int attributeCount = readU2(offset);
            offset += 2;
            for (int i = 0; i < attributeCount; i++) {
                String name = utf8(readU2(offset));
                int length = readU4(offset + 2);
                if (name.equals("SourceFile")) {
                    sourceFileIndexOffset = offset + 6;
                }
                offset += 6 + length;
            }
        }

        /** Skips a {@code fields} or {@code methods} table, returning the offset after it. */
        private int skipMembers(int offset) {
            int count = readU2(offset);
            offset += 2;
            for (int i = 0; i < count; i++) {
                offset += 6;                             // access_flags, name_index, descriptor_index
                int attributeCount = readU2(offset);
                offset += 2;
                for (int a = 0; a < attributeCount; a++) {
                    offset += 6 + readU4(offset + 2);
                }
            }
            return offset;
        }

        /**
         * The text of a {@code CONSTANT_Utf8}. Class files use modified UTF-8; for the names compared here
         * (attribute names, file names) it is the same as UTF-8 except for characters that don't occur in them.
         */
        String utf8(int index) {
            int offset = constantOffsets[index];
            int length = readU2(offset + 1);
            return new String(bytes, offset + 3, length, StandardCharsets.UTF_8);
        }

        int readU2(int offset) {
            return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
        }

        int readU4(int offset) {
            return (readU2(offset) << 16) | readU2(offset + 2);
        }
    }
}
